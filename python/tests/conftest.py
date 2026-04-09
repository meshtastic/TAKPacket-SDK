import os
import sys

# Add src to path so tests can import the package
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

TESTDATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "testdata")
FIXTURES_DIR = os.path.join(TESTDATA_DIR, "cot_xml")
GOLDEN_DIR = os.path.join(TESTDATA_DIR, "golden")
PROTOBUF_DIR = os.path.join(TESTDATA_DIR, "protobuf")

# Dynamically enumerate all XML fixtures in the shared testdata directory so new
# fixtures can be added without editing this list. Kotlin's CompressionTest is
# the canonical generator for the corresponding .pb and .bin files — run it
# first when adding new fixtures, then the Python suite will pick them up on
# the next pytest invocation. Sorted for stable test ordering.
FIXTURE_NAMES = sorted(
    os.path.splitext(f)[0]
    for f in os.listdir(FIXTURES_DIR)
    if f.endswith(".xml")
)


def load_fixture_xml(name: str) -> str:
    with open(os.path.join(FIXTURES_DIR, f"{name}.xml"), "r") as f:
        return f.read()


def load_golden(name: str) :
    path = os.path.join(GOLDEN_DIR, f"{name}.bin")
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        return f.read()
