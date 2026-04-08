import os
import sys

# Add src to path so tests can import the package
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

TESTDATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "testdata")
FIXTURES_DIR = os.path.join(TESTDATA_DIR, "cot_xml")
GOLDEN_DIR = os.path.join(TESTDATA_DIR, "golden")
PROTOBUF_DIR = os.path.join(TESTDATA_DIR, "protobuf")

FIXTURE_NAMES = [
    "pli_basic", "pli_full", "pli_webtak",
    "geochat_simple", "aircraft_adsb", "aircraft_hostile",
    "delete_event", "casevac", "alert_tic",
]


def load_fixture_xml(name: str) -> str:
    with open(os.path.join(FIXTURES_DIR, f"{name}.xml"), "r") as f:
        return f.read()


def load_golden(name: str) :
    path = os.path.join(GOLDEN_DIR, f"{name}.bin")
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        return f.read()
