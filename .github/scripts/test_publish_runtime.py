from importlib import import_module
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
import sys
import unittest

EXPECTED_VERSION = "7.35.1"
try:
    INSTALLED_VERSION = version("protobuf")
except PackageNotFoundError:
    INSTALLED_VERSION = None


class PublishRuntimeTest(unittest.TestCase):
    @unittest.skipIf(INSTALLED_VERSION is None, "run inside the publish virtual environment")
    def test_generated_protobuf_runtime_matches_lock(self):
        self.assertEqual(INSTALLED_VERSION, EXPECTED_VERSION)
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        index_pb2 = import_module("index_pb2")
        index_pb2.Index().SerializeToString(deterministic=True)
        print(f"PROTOBUF_RUNTIME_VERSION={INSTALLED_VERSION}")
        print("PROTOBUF_GENERATED_RUNTIME_CHECK=PASS")


if __name__ == "__main__":
    unittest.main()
