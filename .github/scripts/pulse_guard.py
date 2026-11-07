#!/usr/bin/env python3
"""
pulse_guard.py - Project Nox 24/7 Cloud Pulse & Anti-Duplication Watchdog Guard (Fail-Closed).

Coordinates the 5-minute recursive pulse and the 15-minute watchdog cron:
1. Mode detection: heartbeat (5-min self-trigger), watchdog (15-min safety cron), manual.
2. Watchdog liveness guard: if scheduled watchdog fires while a heartbeat run is
   already active/queued, watchdog immediately exits (WATCHDOG_ALREADY_ALIVE=true).
   If heartbeat chain is dead/absent, watchdog executes sync and restarts pulse (WATCHDOG_RESTART=true).
   FAIL-CLOSED: if query fails, watchdog refuses to restart (WATCHDOG_ACTIVE_STATE_UNKNOWN=true).
3. Pre-dispatch anti-split brain guard: queries GitHub API for other active runs before
   dispatching next pulse. If another run is queued/in_progress, skips dispatch (SKIPPED_ALREADY_ACTIVE).
   FAIL-CLOSED: if query fails, heartbeat refuses to dispatch in the dark (SKIPPED_ACTIVE_STATE_UNKNOWN).
4. Controlled retry backoff: retries dispatch up to 3 times (10s, 20s, 30s + jitter) with Retry-After respect.
5. Error differentiation: distinguishes 401 (fatal auth) from 403/429 (rate limits) and 500/502/503 (server errors).
6. Resilient continuity: optionally defers transient dispatch failures to the 15-min watchdog cron (--defer-transient).
7. Structured pulse status logging with ACTIVE_RUN_CHECK, ACTIVE_RUN_CHECK_ATTEMPTS, ACTIVE_RUN_CHECK_ERROR.
"""

import argparse
from dataclasses import dataclass
import json
import os
import random
import re
import subprocess
import sys
import time
from typing import List, Optional, Tuple


@dataclass
class ActiveRunsResult:
    status: str  # "OK" or "FAILED"
    runs: List[int]
    attempts: int
    error: Optional[str] = None
    error_type: Optional[str] = None
    retry_after: Optional[int] = None

    @property
    def is_ok(self) -> bool:
        return self.status == "OK"

    @property
    def is_failed(self) -> bool:
        return self.status == "FAILED"


def set_github_output(key: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def classify_github_error(error_msg: str, returncode: int = 1) -> Tuple[str, Optional[int]]:
    """
    Classify GitHub CLI / API error message and extract Retry-After or reset delay.
    Returns: (error_type, retry_after_seconds)
    """
    msg = (error_msg or "").lower()
    retry_after: Optional[int] = None

    # Check for Retry-After or wait hints
    retry_match = re.search(r"(?:retry-after[:=\s]+|retry after\s+|wait\s+)(\d+)", error_msg or "", re.IGNORECASE)
    if retry_match:
        try:
            retry_after = int(retry_match.group(1))
        except ValueError:
            pass

    # Check for x-ratelimit-reset epoch timestamp
    reset_match = re.search(r"x-ratelimit-reset[:=\s]+(\d+)", error_msg or "", re.IGNORECASE)
    if reset_match:
        try:
            epoch = int(reset_match.group(1))
            now = int(time.time())
            if epoch > now:
                retry_after = epoch - now
        except ValueError:
            pass

    # 1. Authentication failure (fatal, human action required)
    if any(k in msg for k in [
        "401", "bad credentials", "requires authentication",
        "authentication failed", "could not read username"
    ]) and not ("rate limit" in msg or "secondary rate" in msg):
        return ("AUTH_ERROR", retry_after)

    # 2. Rate limit (403 rate limit, 429)
    if "rate limit" in msg or "secondary rate" in msg or "429" in msg or "too many requests" in msg:
        return ("RATE_LIMIT", retry_after)

    # 3. Server errors (500, 502, 503, 504)
    if any(k in msg for k in [
        "http 500", "http 502", "http 503", "http 504",
        "500 internal", "502 bad gateway", "503 service", "504 gateway",
        "server error", "bad gateway", "service unavailable", "gateway timeout"
    ]):
        return ("SERVER_ERROR", retry_after)

    return ("UNKNOWN", retry_after)


def log_pulse_status(
    pulse_mode: str,
    current_run_id: str,
    active_run_check: str,
    active_run_check_attempts: int,
    active_run_check_error: Optional[str],
    other_active_runs: str,
    next_pulse_required: bool,
    trigger_attempt: str,
    trigger_result: str,
    watchdog_restart: bool,
    watchdog_already_alive: Optional[bool] = None,
    watchdog_active_state_unknown: Optional[bool] = None,
) -> None:
    print("==================================================")
    print("PROJECT NOX — PULSE STATUS")
    print("==================================================")
    print(f"PULSE_MODE={pulse_mode}")
    print(f"CURRENT_RUN_ID={current_run_id}")
    print(f"ACTIVE_RUN_CHECK={active_run_check}")
    print(f"ACTIVE_RUN_CHECK_ATTEMPTS={active_run_check_attempts}")
    print(f"ACTIVE_RUN_CHECK_ERROR={active_run_check_error or 'NONE'}")
    print(f"OTHER_ACTIVE_RUNS={other_active_runs}")
    print(f"NEXT_PULSE_REQUIRED={'true' if next_pulse_required else 'false'}")
    print(f"NEXT_PULSE_TRIGGER_ATTEMPT={trigger_attempt}")
    print(f"NEXT_PULSE_TRIGGER_RESULT={trigger_result}")
    print(f"WATCHDOG_RESTART={'true' if watchdog_restart else 'false'}")
    if watchdog_already_alive is not None:
        print(f"WATCHDOG_ALREADY_ALIVE={'true' if watchdog_already_alive else 'false'}")
    if watchdog_active_state_unknown is not None:
        print(f"WATCHDOG_ACTIVE_STATE_UNKNOWN={'true' if watchdog_active_state_unknown else 'false'}")
    print("==================================================")
    sys.stdout.flush()


def get_active_runs(
    workflow_file: str,
    current_run_id: str,
    repo: Optional[str] = None,
    max_retries: int = 3,
    backoff_delays: Optional[List[int]] = None,
    allow_jitter: bool = True,
) -> ActiveRunsResult:
    """
    Queries gh run list to find active runs (queued or in_progress) of the given workflow,
    excluding current_run_id.
    FAIL-CLOSED: Returns status="FAILED" if all retries are exhausted. Never returns [] on error.
    Respects rate limits, Retry-After, exponential backoff with jitter, and fatal auth classification.
    """
    if backoff_delays is None:
        backoff_delays = [2, 4, 6]

    cmd = [
        "gh", "run", "list",
        "--workflow", workflow_file,
        "--json", "databaseId,status",
        "-L", "25",
    ]
    if repo:
        cmd.extend(["-R", repo])

    last_error = ""
    err_type = "UNKNOWN"
    retry_after = None

    for attempt in range(1, max_retries + 1):
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            if res.returncode == 0:
                data = json.loads(res.stdout or "[]")
                active = []
                for run in data:
                    run_id = str(run.get("databaseId"))
                    status = str(run.get("status", "")).lower()
                    if run_id != str(current_run_id) and status in {
                        "queued", "in_progress", "waiting", "requested", "pending"
                    }:
                        try:
                            active.append(int(run_id))
                        except ValueError:
                            pass
                return ActiveRunsResult(
                    status="OK",
                    runs=active,
                    attempts=attempt,
                    error=None,
                    error_type=None,
                    retry_after=None,
                )
            else:
                last_error = res.stderr.strip() or f"exit code {res.returncode}"
                err_type, retry_after = classify_github_error(last_error, res.returncode)
                print(
                    f"[Pulse Guard] Warning: 'gh run list' attempt {attempt}/{max_retries} failed ({err_type}): {last_error}",
                    file=sys.stderr,
                )
        except Exception as e:
            last_error = str(e)
            err_type, retry_after = classify_github_error(last_error)
            print(
                f"[Pulse Guard] Warning: Exception querying 'gh run list' (attempt {attempt}/{max_retries}): {e}",
                file=sys.stderr,
            )

        # Fatal auth error -> do not retry
        if err_type == "AUTH_ERROR":
            return ActiveRunsResult(
                status="FAILED",
                runs=[],
                attempts=attempt,
                error=last_error,
                error_type="AUTH_ERROR",
                retry_after=None,
            )

        # Rate limit with long reset -> do not hammer API
        if err_type == "RATE_LIMIT" and retry_after is not None and retry_after > 15:
            print(
                f"[Pulse Guard] Rate limit reset is in {retry_after}s. Halting query retries to protect quota.",
                file=sys.stderr,
            )
            return ActiveRunsResult(
                status="FAILED",
                runs=[],
                attempts=attempt,
                error=last_error,
                error_type="RATE_LIMIT",
                retry_after=retry_after,
            )

        if attempt < max_retries:
            if retry_after is not None and 0 < retry_after <= 15:
                delay = float(retry_after)
            else:
                delay = float(backoff_delays[min(attempt - 1, len(backoff_delays) - 1)])
            if allow_jitter and delay > 0:
                delay += random.uniform(0.1, 1.0)
            time.sleep(delay)

    return ActiveRunsResult(
        status="FAILED",
        runs=[],
        attempts=max_retries,
        error=last_error or "Unknown failure querying gh run list",
        error_type=err_type,
        retry_after=retry_after,
    )


def determine_pulse_mode(event_name: str, trigger_source: str) -> str:
    if event_name == "schedule":
        return "watchdog"
    src = (trigger_source or "").strip().lower()
    if src in {"heartbeat", "watchdog", "manual"}:
        return src
    return "manual"


def cmd_watchdog_check(args: argparse.Namespace) -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", args.event_name or "")
    trigger_source = os.environ.get("TRIGGER_SOURCE", args.trigger_source or "manual")
    current_run_id = os.environ.get(
        "CURRENT_RUN_ID", os.environ.get("GITHUB_RUN_ID", args.current_run_id or "0")
    )
    repo = os.environ.get("GITHUB_REPOSITORY", args.repo)

    pulse_mode = determine_pulse_mode(event_name, trigger_source)

    if pulse_mode != "watchdog":
        set_github_output("should_run_sync", "true")
        set_github_output("pulse_mode", pulse_mode)
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "false")
        set_github_output("watchdog_active_state_unknown", "false")
        set_github_output("active_run_check", "OK")
        print(f"[Pulse Guard] Mode is '{pulse_mode}'. Watchdog check passed; proceed to sync.")
        return 0

    # Watchdog mode: check if heartbeat is currently alive
    backoff = [0, 0, 0] if args.no_backoff else [2, 4, 6]
    result = get_active_runs(
        args.workflow, current_run_id, repo,
        backoff_delays=backoff, allow_jitter=not args.no_backoff
    )

    if result.is_failed:
        # FAIL-CLOSED: cannot confirm state, do NOT assume dead, do NOT restart
        print(
            f"[Pulse Guard] Watchdog active run query failed: {result.error}. "
            "Fail-closed: refusing to restart. Watchdog will retry on next 15-minute cron.",
            file=sys.stderr,
        )
        set_github_output("should_run_sync", "false")
        set_github_output("pulse_mode", "watchdog")
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "false")
        set_github_output("watchdog_active_state_unknown", "true")
        set_github_output("active_run_check", "FAILED")
        log_pulse_status(
            pulse_mode="watchdog",
            current_run_id=current_run_id,
            active_run_check="FAILED",
            active_run_check_attempts=result.attempts,
            active_run_check_error=result.error,
            other_active_runs="UNKNOWN",
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ACTIVE_STATE_UNKNOWN",
            watchdog_restart=False,
            watchdog_already_alive=False,
            watchdog_active_state_unknown=True,
        )
        if result.error_type == "AUTH_ERROR":
            return 1
        if (getattr(args, "defer_transient", None) is True) and (
            result.error_type in ("RATE_LIMIT", "SERVER_ERROR") or "rate limit" in (result.error or "").lower()
        ):
            print("[Pulse Guard] Transient error during watchdog check; safely deferring to next cron schedule.", file=sys.stderr)
            return 0
        return 1

    if result.runs:
        print(f"[Pulse Guard] Watchdog found active run(s): {result.runs}. Heartbeat is already alive.")
        set_github_output("should_run_sync", "false")
        set_github_output("pulse_mode", "watchdog")
        set_github_output("watchdog_restart", "false")
        set_github_output("watchdog_already_alive", "true")
        set_github_output("watchdog_active_state_unknown", "false")
        set_github_output("active_run_check", "OK")
        log_pulse_status(
            pulse_mode="watchdog",
            current_run_id=current_run_id,
            active_run_check="OK",
            active_run_check_attempts=result.attempts,
            active_run_check_error=None,
            other_active_runs=str(len(result.runs)),
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=False,
            watchdog_already_alive=True,
            watchdog_active_state_unknown=False,
        )
        return 0

    print("[Pulse Guard] Watchdog detected heartbeat is dead (0 active runs). Restarting pulse!")
    set_github_output("should_run_sync", "true")
    set_github_output("pulse_mode", "watchdog")
    set_github_output("watchdog_restart", "true")
    set_github_output("watchdog_already_alive", "false")
    set_github_output("watchdog_active_state_unknown", "false")
    set_github_output("active_run_check", "OK")
    log_pulse_status(
        pulse_mode="watchdog",
        current_run_id=current_run_id,
        active_run_check="OK",
        active_run_check_attempts=result.attempts,
        active_run_check_error=None,
        other_active_runs="0",
        next_pulse_required=True,
        trigger_attempt="NONE",
        trigger_result="WATCHDOG_RESTART",
        watchdog_restart=True,
        watchdog_already_alive=False,
        watchdog_active_state_unknown=False,
    )
    return 0


def cmd_pulse_dispatch(args: argparse.Namespace) -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", args.event_name or "")
    trigger_source = os.environ.get("TRIGGER_SOURCE", args.trigger_source or "manual")
    current_run_id = os.environ.get(
        "CURRENT_RUN_ID", os.environ.get("GITHUB_RUN_ID", args.current_run_id or "0")
    )
    repo = os.environ.get("GITHUB_REPOSITORY", args.repo)

    pulse_mode = determine_pulse_mode(event_name, trigger_source)
    is_watchdog = (pulse_mode == "watchdog")
    watchdog_restart = is_watchdog
    backoff = [0, 0, 0] if args.no_backoff else [2, 4, 6]

    # heartbeat and watchdog (when restarted) require scheduling the next pulse.
    # manual runs are one-off and do not schedule subsequent pulses.
    next_pulse_required = (pulse_mode in {"heartbeat", "watchdog"})

    if not next_pulse_required:
        check_result = get_active_runs(
            args.workflow, current_run_id, repo,
            backoff_delays=backoff, allow_jitter=not args.no_backoff
        )
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check=check_result.status,
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=check_result.error,
            other_active_runs=str(len(check_result.runs)) if check_result.is_ok else "UNKNOWN",
            next_pulse_required=False,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_NOT_REQUIRED",
            watchdog_restart=False,
        )
        return 0

    # Wait sleep interval before checking and dispatching
    if args.sleep_seconds > 0:
        print(f"[Pulse Guard] Waiting {args.sleep_seconds}s before checking and dispatching next pulse...")
        time.sleep(args.sleep_seconds)

    # Pre-dispatch anti-split brain check (Fail-Closed)
    check_result = get_active_runs(
        args.workflow, current_run_id, repo,
        backoff_delays=backoff, allow_jitter=not args.no_backoff
    )

    if check_result.is_failed:
        # FAIL-CLOSED: cannot confirm state, do NOT dispatch in the dark
        err_msg = check_result.error or "Unknown active run query error"
        err_type = check_result.error_type or classify_github_error(err_msg)[0]

        print(
            f"[Pulse Guard] Error: Failed to check active runs ({err_msg}). "
            "Fail-closed: skipping dispatch to avoid split-brain duplication. Watchdog will recover if needed.",
            file=sys.stderr,
        )

        is_transient = err_type in ("RATE_LIMIT", "SERVER_ERROR") or "rate limit" in err_msg.lower()
        can_defer = (getattr(args, "defer_transient", None) is True) and is_transient

        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check="FAILED",
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=check_result.error,
            other_active_runs="UNKNOWN",
            next_pulse_required=True,
            trigger_attempt="NONE",
            trigger_result="DEFERRED_TO_WATCHDOG" if can_defer else "SKIPPED_ACTIVE_STATE_UNKNOWN",
            watchdog_restart=watchdog_restart,
            watchdog_already_alive=False,
            watchdog_active_state_unknown=True if is_watchdog else None,
        )

        if can_defer:
            set_github_output("dispatch_result", "DEFERRED_TO_WATCHDOG")
            print("[Pulse Guard] Active run check transient failure deferred to 15-minute watchdog cron. Exiting clean.", file=sys.stderr)
            return 0
        return 1

    if check_result.runs:
        print(f"[Pulse Guard] Pre-dispatch check detected active runs: {check_result.runs}. Skipping next pulse.")
        log_pulse_status(
            pulse_mode=pulse_mode,
            current_run_id=current_run_id,
            active_run_check="OK",
            active_run_check_attempts=check_result.attempts,
            active_run_check_error=None,
            other_active_runs=str(len(check_result.runs)),
            next_pulse_required=True,
            trigger_attempt="NONE",
            trigger_result="SKIPPED_ALREADY_ACTIVE",
            watchdog_restart=watchdog_restart,
            watchdog_already_alive=False,
        )
        return 0

    # check_result.is_ok and 0 active runs -> safe to dispatch
    dispatch_backoffs = [10, 20, 30] if not args.no_backoff else [0, 0, 0]
    max_attempts = 3
    dispatch_cmd = [
        "gh", "workflow", "run", args.workflow,
        "--ref", args.ref,
        "-f", "trigger_source=heartbeat",
    ]
    if repo:
        dispatch_cmd.extend(["-R", repo])

    last_dispatch_err = ""
    last_err_type = "UNKNOWN"

    for attempt in range(1, max_attempts + 1):
        print(f"[Pulse Guard] Dispatching next pulse (attempt {attempt}/{max_attempts})...")
        res = subprocess.run(dispatch_cmd, capture_output=True, text=True, check=False)
        if res.returncode == 0:
            print(f"[Pulse Guard] Successfully dispatched next pulse on attempt {attempt}.")
            log_pulse_status(
                pulse_mode=pulse_mode,
                current_run_id=current_run_id,
                active_run_check="OK",
                active_run_check_attempts=check_result.attempts,
                active_run_check_error=None,
                other_active_runs="0",
                next_pulse_required=True,
                trigger_attempt=str(attempt),
                trigger_result="DISPATCHED",
                watchdog_restart=watchdog_restart,
                watchdog_already_alive=False,
            )
            set_github_output("dispatch_result", "DISPATCHED")
            return 0
        else:
            last_dispatch_err = res.stderr.strip()
            last_err_type, retry_after = classify_github_error(last_dispatch_err, res.returncode)
            print(f"[Pulse Guard] Dispatch attempt {attempt} failed ({last_err_type}): {last_dispatch_err}", file=sys.stderr)

            # Fatal auth error -> do not retry
            if last_err_type == "AUTH_ERROR":
                print(f"[Pulse Guard] Fatal authentication error during workflow dispatch: {last_dispatch_err}", file=sys.stderr)
                log_pulse_status(
                    pulse_mode=pulse_mode,
                    current_run_id=current_run_id,
                    active_run_check="OK",
                    active_run_check_attempts=check_result.attempts,
                    active_run_check_error=None,
                    other_active_runs="0",
                    next_pulse_required=True,
                    trigger_attempt=str(attempt),
                    trigger_result="FAILED_AUTHENTICATION",
                    watchdog_restart=watchdog_restart,
                    watchdog_already_alive=False,
                )
                set_github_output("dispatch_result", "FAILED_AUTHENTICATION")
                return 1

            # Rate limit with long wait -> do not hammer dispatch
            if last_err_type == "RATE_LIMIT" and retry_after is not None and retry_after > 15:
                print(f"[Pulse Guard] Dispatch rate limited. Reset in {retry_after}s. Halting dispatch retries.", file=sys.stderr)
                break

            if attempt < max_attempts:
                if retry_after is not None and 0 < retry_after <= 15:
                    delay = float(retry_after)
                else:
                    delay = float(dispatch_backoffs[attempt - 1])
                if not args.no_backoff and delay > 0:
                    delay += random.uniform(0.5, 2.0)
                print(f"[Pulse Guard] Backing off for {delay:.1f}s before retry...")
                time.sleep(delay)

    # All dispatch attempts exhausted
    is_transient = last_err_type in ("RATE_LIMIT", "SERVER_ERROR") or any(
        k in last_dispatch_err.lower() for k in ["500", "502", "503", "rate limit", "too many requests"]
    )
    can_defer = (getattr(args, "defer_transient", None) is True) and is_transient

    print(f"[Pulse Guard] All dispatch attempts failed (last error: {last_dispatch_err})", file=sys.stderr)
    log_pulse_status(
        pulse_mode=pulse_mode,
        current_run_id=current_run_id,
        active_run_check="OK",
        active_run_check_attempts=check_result.attempts,
        active_run_check_error=None,
        other_active_runs="0",
        next_pulse_required=True,
        trigger_attempt=str(max_attempts),
        trigger_result="DEFERRED_TO_WATCHDOG" if can_defer else "FAILED",
        watchdog_restart=watchdog_restart,
        watchdog_already_alive=False,
    )

    if can_defer:
        set_github_output("dispatch_result", "DEFERRED_TO_WATCHDOG")
        print("[Pulse Guard] Dispatch failure deferred to 15-minute watchdog cron. Exiting clean.", file=sys.stderr)
        return 0

    set_github_output("dispatch_result", "FAILED")
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Project Nox Pulse Guard (Fail-Closed)")
    parser.add_argument("--workflow", default="sync_upstream.yml", help="Workflow filename")
    parser.add_argument("--ref", default="main", help="Git ref to dispatch")
    parser.add_argument("--repo", default=None, help="GitHub repo (e.g. owner/repo)")
    parser.add_argument("--event-name", default=None, help="GitHub event name override")
    parser.add_argument("--trigger-source", default=None, help="Trigger source override")
    parser.add_argument("--current-run-id", default=None, help="Current run ID override")
    parser.add_argument("--no-backoff", action="store_true", help="Disable retry backoff (for tests)")
    parser.add_argument("--defer-transient", action="store_true", help="Defer transient 403/429/50x failures to 15-min watchdog cron instead of failing workflow")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # watchdog-check command
    subparsers.add_parser("watchdog-check", help="Check watchdog liveness before sync")

    # pulse-dispatch command
    dispatch_parser = subparsers.add_parser("pulse-dispatch", help="Sleep and dispatch next pulse")
    dispatch_parser.add_argument("--sleep-seconds", type=int, default=300, help="Seconds to wait before dispatch")

    args = parser.parse_args()

    if args.command == "watchdog-check":
        return cmd_watchdog_check(args)
    elif args.command == "pulse-dispatch":
        return cmd_pulse_dispatch(args)

    return 0


if __name__ == "__main__":
    sys.exit(main())
