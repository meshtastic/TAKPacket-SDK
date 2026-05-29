import os

from meshtastic_tak.cot_mesh_sanitizer import (
    CotMeshSanitizer,
    normalize_cot_xml,
    strip_non_essential_for_mesh,
)

from conftest import TESTDATA_DIR

SANITIZER_DIR = os.path.join(TESTDATA_DIR, "sanitizer")


def _read(name: str) -> str:
    with open(os.path.join(SANITIZER_DIR, name), "r") as f:
        return f.read()


def test_strip_matches_golden():
    got = strip_non_essential_for_mesh(_read("strip.in.xml"))
    expected = _read("strip.out.xml")
    assert got.rstrip() == expected.rstrip()


def test_normalize_matches_golden():
    got = normalize_cot_xml(_read("normalize.in.xml"))
    expected = _read("normalize.out.xml")
    assert got.rstrip() == expected.rstrip()


def test_strip_preserves_taktalk_and_drops_display_only():
    got = strip_non_essential_for_mesh(_read("strip.in.xml"))
    # TAK-Talk essentials are preserved.
    assert "<voice/>" in got
    assert 'dest callsign="ETHEL"' in got
    # Display-only content is stripped.
    assert "<takv" not in got
    assert 'uid="LINK-UUID' not in got


def test_class_facade_delegates():
    xml = _read("strip.in.xml")
    assert CotMeshSanitizer.strip_non_essential_for_mesh(xml) == strip_non_essential_for_mesh(xml)
    norm = _read("normalize.in.xml")
    assert CotMeshSanitizer.normalize_cot_xml(norm) == normalize_cot_xml(norm)
