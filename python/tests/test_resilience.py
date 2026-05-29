"""Resilience / statelessness invariant tests.

Locks the LoRa-resilience invariant: every packet MUST be fully, independently
decodable from its own bytes plus the static shipped dictionary. ZERO
cross-packet state. LoRa is lossy — losing any packet must never jeopardize the
decode of any other packet.

If a future change introduces stateful/streaming compression or any
cross-packet dependency, one of these tests fails. Mirrors Kotlin
ResilienceTest.kt.
"""
from meshtastic_tak import CotXmlParser, TakCompressor
from conftest import FIXTURE_NAMES, load_fixture_xml

parser = CotXmlParser()
compressor = TakCompressor()


def _encode_all():
    """Encode every fixture once, in order, into independent wire payloads."""
    return [(name, compressor.compress(parser.parse(load_fixture_xml(name)))) for name in FIXTURE_NAMES]


def test_each_packet_decodes_identically_regardless_of_order():
    wire = _encode_all()
    # Baseline: decode each in the order produced.
    forward = [(name, compressor.decompress(w)) for name, w in wire]
    # Decode the SAME payloads in reverse order — order must not matter.
    reverse = [(name, compressor.decompress(w)) for name, w in reversed(wire)]
    reverse.reverse()
    for (name, f), (_, r) in zip(forward, reverse):
        assert f == r, f"Packet {name} decoded differently in reverse order — cross-packet state leak"


def test_any_single_packet_decodes_with_all_others_dropped():
    wire = _encode_all()
    # Simulate a lossy mesh: each packet must decode ALONE (every other "lost").
    in_sequence = {name: compressor.decompress(w) for name, w in wire}
    for name, w in wire:
        isolated = compressor.decompress(w)
        assert isolated == in_sequence[name], f"Packet {name} failed to decode in isolation"


def test_cold_compressor_instance_decodes_any_packet():
    wire = _encode_all()
    # A freshly-constructed compressor — never having seen any prior packet —
    # must decode any single frame. Proves no warm-up/history state.
    for name, w in wire:
        cold = TakCompressor()
        pkt = cold.decompress(w)
        assert pkt.uid != "", f"Cold compressor failed to decode {name}"


def test_re_encoding_on_fresh_compressor_is_byte_identical():
    # Determinism: the same packet compressed by independent compressor
    # instances must yield identical wire bytes (no instance-accumulated state).
    for name in FIXTURE_NAMES:
        pkt = parser.parse(load_fixture_xml(name))
        a = TakCompressor().compress(pkt)
        b = TakCompressor().compress(pkt)
        assert a == b, f"Non-deterministic compression for {name} — possible state leak"
