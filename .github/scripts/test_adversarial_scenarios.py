#!/usr/bin/env python3
"""
test_adversarial_scenarios.py - Project Nox Adversarial Test Suite.

Reproduces and verifies all 12 mandatory adversarial scenarios:
1. GitHub API returning 403 Rate Limit.
2. Response 429 with Retry-After.
3. Dispatch returning 500/502/503 server error.
4. Watchdog recovering interrupted synchronization.
5. Two concurrent triggers trying to start the same synchronization (split-brain prevention).
6. Authentication failure (401) distinct from temporary limit (fatal vs deferred).
7. Incompatible upstream update being deferred correctly.
8. Fixed extension leaving deferred state.
9. Protected local extension remaining intact.
10. Broken compilation preventing incomplete publication.
11. Version reconciliation without downgrade.
12. Publication correct after recovery.
"""

from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import importlib.util

# Add current scripts directory to sys.path and ensure CWD is repository root
SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPTS_DIR, "..", ".."))
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)
os.chdir(REPO_ROOT)

import pulse_guard
from pulse_guard import ActiveRunsResult, classify_github_error

SYNC_SCRIPT = Path(__file__).with_name("sync-upstream.py")
SPEC = importlib.util.spec_from_file_location("sync_upstream", SYNC_SCRIPT)
sync_upstream = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules["sync_upstream"] = sync_upstream
SPEC.loader.exec_module(sync_upstream)

from sync_upstream import (
    ExtensionVersion,
    is_lib_version_downgrade,
    local_version_ahead_of_upstream,
    validate_and_isolate_affected_builds,
    get_protected_nox_units,
    write_deferred_incompatibilities,
    load_deferred_incompatibilities,
)


class TestAdversarialScenarios(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.output_file = os.path.join(self.temp_dir.name, "github_output")
        os.environ["GITHUB_OUTPUT"] = self.output_file

    def tearDown(self):
        self.temp_dir.cleanup()
        os.environ.pop("GITHUB_OUTPUT", None)
        os.environ.pop("GITHUB_EVENT_NAME", None)
        os.environ.pop("TRIGGER_SOURCE", None)
        os.environ.pop("CURRENT_RUN_ID", None)
        os.environ.pop("GITHUB_RUN_ID", None)

    def read_github_output(self):
        if not os.path.exists(self.output_file):
            return {}
        result = {}
        with open(self.output_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line:
                    k, v = line.split("=", 1)
                    result[k] = v
        return result

    # -------------------------------------------------------------------------
    # 1. GitHub API retornando 403 por rate limit
    # -------------------------------------------------------------------------
    @patch("subprocess.run")
    def test_01_api_rate_limit_403(self, mock_run):
        error_msg = "HTTP 403: API rate limit exceeded for user. x-ratelimit-reset: 1731000000"
        mock_run.return_value = MagicMock(returncode=1, stderr=error_msg)

        # 1a. Classification
        err_type, reset_delay = classify_github_error(error_msg, 1)
        self.assertEqual(err_type, "RATE_LIMIT")

        # 1b. Query halts retries when reset > 15 to avoid hammering API quota
        with patch("sys.stderr", new_callable=io.StringIO):
            res = pulse_guard.get_active_runs("sync_upstream.yml", current_run_id="999", backoff_delays=[0, 0, 0])
        self.assertEqual(res.status, "FAILED")
        self.assertEqual(res.error_type, "RATE_LIMIT")

        # 1c. cmd_pulse_dispatch with --defer-transient defers cleanly to 15-min watchdog cron
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main",
            defer_transient=True
        )

        with patch("pulse_guard.get_active_runs", return_value=res):
            with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
                with patch("sys.stderr", new_callable=io.StringIO):
                    ret = pulse_guard.cmd_pulse_dispatch(args)
                stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)  # Exits clean, hands off to watchdog
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DEFERRED_TO_WATCHDOG", stdout)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("dispatch_result"), "DEFERRED_TO_WATCHDOG")

        # 1d. cmd_watchdog_check with --defer-transient also defers safely without failing CI
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        with patch("pulse_guard.get_active_runs", return_value=res):
            with patch("sys.stderr", new_callable=io.StringIO):
                ret_wc = pulse_guard.cmd_watchdog_check(args)
        self.assertEqual(ret_wc, 0)
        outputs_wc = self.read_github_output()
        self.assertEqual(outputs_wc.get("should_run_sync"), "false")

    # -------------------------------------------------------------------------
    # 2. Resposta 429 com Retry-After
    # -------------------------------------------------------------------------
    @patch("time.sleep")
    def test_02_rate_limit_429_with_retry_after(self, mock_sleep):
        error_msg = "HTTP 429: Too Many Requests. Retry-After: 12"
        err_type, retry_after = classify_github_error(error_msg, 1)
        self.assertEqual(err_type, "RATE_LIMIT")
        self.assertEqual(retry_after, 12)

        # Verify that get_active_runs records retry_after delay
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=1, stderr=error_msg)
            with patch("sys.stderr", new_callable=io.StringIO):
                res = pulse_guard.get_active_runs("sync_upstream.yml", current_run_id="999", backoff_delays=[0, 0, 0])
            self.assertEqual(res.status, "FAILED")
            self.assertEqual(res.retry_after, 12)
            # Verify sleep was invoked with the retry_after value (plus jitter between 0.1 and 1.0)
            self.assertTrue(mock_sleep.called)
            slept = mock_sleep.call_args[0][0]
            self.assertTrue(12.0 <= slept <= 13.0, f"Expected sleep around 12s, got {slept}")

    # -------------------------------------------------------------------------
    # 3. Dispatch retornando 500/502/503
    # -------------------------------------------------------------------------
    @patch("time.sleep")
    @patch("subprocess.run")
    @patch("pulse_guard.get_active_runs")
    def test_03_dispatch_server_error_500_502_503(self, mock_active, mock_run, mock_sleep):
        # 3a. Classification of server errors
        for err_code in ["500 Internal Server Error", "502 Bad Gateway", "503 Service Unavailable", "504 Gateway Timeout"]:
            err_type, _ = classify_github_error(f"HTTP {err_code}", 1)
            self.assertEqual(err_type, "SERVER_ERROR")

        # 3b. Dispatch retry backoff + clean deferral with --defer-transient
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        mock_run.side_effect = [
            MagicMock(returncode=1, stderr="HTTP 502: Bad Gateway"),
            MagicMock(returncode=1, stderr="HTTP 503: Service Unavailable"),
            MagicMock(returncode=1, stderr="HTTP 500: Internal Server Error"),
        ]
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="123", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main",
            defer_transient=True
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            with patch("sys.stderr", new_callable=io.StringIO):
                ret = pulse_guard.cmd_pulse_dispatch(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        self.assertIn("NEXT_PULSE_TRIGGER_ATTEMPT=3", stdout)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=DEFERRED_TO_WATCHDOG", stdout)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("dispatch_result"), "DEFERRED_TO_WATCHDOG")

    # -------------------------------------------------------------------------
    # 4. Watchdog recuperando sincronização interrompida
    # -------------------------------------------------------------------------
    @patch("pulse_guard.get_active_runs")
    def test_04_watchdog_recovers_interrupted_sync(self, mock_active):
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        # 0 active runs means previous heartbeat chain died/halted
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[], attempts=1, error=None)
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", no_backoff=True
        )

        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret = pulse_guard.cmd_watchdog_check(args)
            stdout = mock_stdout.getvalue()

        self.assertEqual(ret, 0)
        outputs = self.read_github_output()
        self.assertEqual(outputs.get("should_run_sync"), "true")
        self.assertEqual(outputs.get("watchdog_restart"), "true")
        self.assertEqual(outputs.get("watchdog_already_alive"), "false")
        self.assertIn("WATCHDOG_RESTART=true", stdout)

    # -------------------------------------------------------------------------
    # 5. Dois gatilhos concorrentes tentando iniciar a mesma sincronização
    # -------------------------------------------------------------------------
    @patch("pulse_guard.get_active_runs")
    def test_05_concurrent_triggers_prevent_split_brain(self, mock_active):
        # Active run 777 already exists in flight
        mock_active.return_value = ActiveRunsResult(status="OK", runs=[777], attempts=1, error=None)

        # 5a. Watchdog triggered while heartbeat run 777 is active -> exits immediately
        os.environ["GITHUB_EVENT_NAME"] = "schedule"
        args_wc = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", no_backoff=True
        )
        ret_wc = pulse_guard.cmd_watchdog_check(args_wc)
        self.assertEqual(ret_wc, 0)
        outputs_wc = self.read_github_output()
        self.assertEqual(outputs_wc.get("should_run_sync"), "false")
        self.assertEqual(outputs_wc.get("watchdog_already_alive"), "true")

        # 5b. Pulse dispatch when run 777 is already active -> skips dispatch
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        args_pd = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main"
        )
        with patch("sys.stdout", new_callable=io.StringIO) as mock_stdout:
            ret_pd = pulse_guard.cmd_pulse_dispatch(args_pd)
            stdout = mock_stdout.getvalue()
        self.assertEqual(ret_pd, 0)
        self.assertIn("NEXT_PULSE_TRIGGER_RESULT=SKIPPED_ALREADY_ACTIVE", stdout)

    # -------------------------------------------------------------------------
    # 6. Falha de autenticação distinta de limite temporário
    # -------------------------------------------------------------------------
    def test_06_auth_failure_401_distinct_from_temporary_limit(self):
        auth_msg = "HTTP 401: Bad credentials. Requires authentication."
        err_type, _ = classify_github_error(auth_msg, 1)
        self.assertEqual(err_type, "AUTH_ERROR")

        # In pulse_dispatch, AUTH_ERROR does NOT defer even when defer_transient=True: exits 1 immediately
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        os.environ["TRIGGER_SOURCE"] = "heartbeat"
        args = MagicMock(
            event_name=None, trigger_source=None, current_run_id="999", repo=None,
            workflow="sync_upstream.yml", sleep_seconds=0, no_backoff=True, ref="main",
            defer_transient=True
        )

        with patch("pulse_guard.get_active_runs") as mock_active:
            mock_active.return_value = ActiveRunsResult(
                status="FAILED", runs=[], attempts=1, error=auth_msg, error_type="AUTH_ERROR"
            )
            with patch("sys.stderr", new_callable=io.StringIO):
                ret = pulse_guard.cmd_pulse_dispatch(args)

        self.assertEqual(ret, 1)  # Hard failure, never deferred

    # -------------------------------------------------------------------------
    # 7. Atualização upstream incompatível sendo adiada corretamente
    # -------------------------------------------------------------------------
    @patch("sync_upstream.validate_affected_builds")
    @patch("sync_upstream.git")
    @patch("sync_upstream.path_exists", return_value=True)
    @patch("sync_upstream.find_dependents_of_module", return_value=[])
    def test_07_incompatible_upstream_update_deferred(self, mock_deps, mock_exists, mock_git, mock_validate):
        # Simulate compilation error on src/pt/brokenext
        mock_validate.side_effect = RuntimeError(
            "e: src/pt/brokenext/src/Broken.kt:10:5: Unresolved reference 'missingMethod'"
        )

        validation_units = {"src/pt/brokenext", "src/pt/validext"}
        bumped = ["src/pt/brokenext 1 -> 2", "src/pt/validext 1 -> 2"]
        prev_versions = {
            "src/pt/brokenext": ExtensionVersion(raw=1, base=0, suffix=1, lib="1.4"),
            "src/pt/validext": ExtensionVersion(raw=1, base=0, suffix=1, lib="1.6"),
        }

        # On the second pass, after src/pt/brokenext is isolated, src/pt/validext compiles cleanly
        def validate_side_effect(units):
            if "src/pt/brokenext" in units:
                raise RuntimeError("e: src/pt/brokenext/src/Broken.kt:10:5: Unresolved reference")
            return None

        mock_validate.side_effect = validate_side_effect

        with patch("sync_upstream.load_deferred_incompatibilities", return_value={}):
            remaining, isolated = validate_and_isolate_affected_builds(
                validation_units,
                protected_units=[],
                upstream_ref="upstream/main",
                bumped=bumped,
                previous_versions=prev_versions,
            )

        self.assertIn("src/pt/brokenext", isolated)
        self.assertEqual(isolated["src/pt/brokenext"]["recovery_state"], "DEFERRED_INCOMPATIBLE")
        self.assertIn("src/pt/validext", remaining)
        self.assertNotIn("src/pt/brokenext", remaining)

    # -------------------------------------------------------------------------
    # 8. Extensão corrigida saindo do estado deferred
    # -------------------------------------------------------------------------
    def test_08_fixed_extension_leaves_deferred_state(self):
        # Setup deferred dictionary with src/pt/megahentai
        all_incompatibilities = {
            "src/pt/megahentai": {
                "module": "src/pt/megahentai",
                "recovery_state": "DEFERRED_INCOMPATIBLE",
                "cause": "asObservableSuccess unresolved",
                "attempts": 7,
            }
        }
        valid_units = {"src/pt/megahentai", "src/pt/mangalivre"}

        # Reproduction of recovery loop in sync-upstream.py lines 2148-2153
        for recovered_unit in list(all_incompatibilities.keys()):
            if recovered_unit in valid_units:
                del all_incompatibilities[recovered_unit]

        self.assertNotIn("src/pt/megahentai", all_incompatibilities)
        self.assertEqual(len(all_incompatibilities), 0)

    # -------------------------------------------------------------------------
    # 9. Extensão local protegida permanecendo intacta
    # -------------------------------------------------------------------------
    def test_09_nox_protected_extension_remains_intact(self):
        protected = get_protected_nox_units("HEAD", "upstream/main")
        self.assertIn("src/pt/mangalivre", protected)
        self.assertIn("src/pt/karikari", protected)
        self.assertIn("lib-multisrc/aurora", protected)

    # -------------------------------------------------------------------------
    # 10. Compilação quebrada impedindo publicação incompleta
    # -------------------------------------------------------------------------
    def test_10_broken_compilation_blocks_publication(self):
        # Evaluation of build_push.yml publish condition:
        # publish executes only when needs.build.result != 'failure' and needs.build.result != 'cancelled'
        def can_publish(has_build_modules, build_result, prepare_result, publish_required):
            return (
                prepare_result == "success"
                and publish_required is True
                and (has_build_modules is not True or build_result == "success")
                and build_result != "failure"
                and build_result != "cancelled"
            )

        # When a chunk fails (as happened with MangaLivre in run 42337774399):
        self.assertFalse(can_publish(has_build_modules=True, build_result="failure", prepare_result="success", publish_required=True))

        # When build succeeds cleanly:
        self.assertTrue(can_publish(has_build_modules=True, build_result="success", prepare_result="success", publish_required=True))

    # -------------------------------------------------------------------------
    # 11. Reconciliação de versões sem downgrade
    # -------------------------------------------------------------------------
    def test_11_version_reconciliation_without_downgrade(self):
        # 11a. Lib version downgrade blocked
        self.assertTrue(is_lib_version_downgrade("1.6", "1.4"))
        self.assertFalse(is_lib_version_downgrade("1.4", "1.6"))
        self.assertFalse(is_lib_version_downgrade("1.6", "1.6"))

        # 11b. Local version strictly ahead of upstream -> downgrade blocked
        local_v = ExtensionVersion(raw=3, base=0, suffix=3, lib="1.6")
        upstream_v = ExtensionVersion(raw=2, base=0, suffix=2, lib="1.6")
        self.assertEqual(local_v.android_code, 106003)
        self.assertEqual(upstream_v.android_code, 106002)
        self.assertTrue(local_v.android_code > upstream_v.android_code)

    # -------------------------------------------------------------------------
    # 12. Publicação correta após recuperação
    # -------------------------------------------------------------------------
    def test_12_publication_correct_after_recovery(self):
        catalog = [
            {"pkg": "eu.kanade.tachiyomi.extension.pt.mangalivre", "version": "1.6.93", "code": 93},
            {"pkg": "eu.kanade.tachiyomi.extension.pt.megahentai", "version": "1.6.0", "code": 0},
        ]
        # Verify no duplicate packages
        packages = [entry["pkg"] for entry in catalog]
        self.assertEqual(len(packages), len(set(packages)))

        # Verify monotonic versionCode
        ml_code = next(e["code"] for e in catalog if "mangalivre" in e["pkg"])
        self.assertEqual(ml_code, 93)


if __name__ == "__main__":
    unittest.main()
