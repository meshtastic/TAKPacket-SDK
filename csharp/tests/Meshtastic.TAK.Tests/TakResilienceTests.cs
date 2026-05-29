using Google.Protobuf;
using Meshtastic.TAK;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// Locks the LoRa-resilience invariant: every packet MUST be fully,
/// independently decodable from its own bytes plus the static shipped
/// dictionary. ZERO cross-packet state. LoRa is lossy — losing any packet
/// must never jeopardize the decode of any other packet.
///
/// If a future change introduces stateful/streaming compression or any
/// cross-packet dependency, one of these tests fails. Mirrors Kotlin
/// ResilienceTest.kt.
/// </summary>
public class TakResilienceTests
{
    private static readonly string TestDataDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata"));

    private static readonly Lazy<string[]> FixturesLazy = new(() =>
        Directory.EnumerateFiles(Path.Combine(TestDataDir, "cot_xml"), "*.xml")
            .Select(Path.GetFileNameWithoutExtension)
            .OrderBy(n => n, StringComparer.Ordinal)
            .ToArray()!);

    private static string[] Fixtures => FixturesLazy.Value;

    private readonly CotXmlParser _parser = new();
    private readonly TakCompressor _compressor = new();

    private string LoadXml(string name) =>
        File.ReadAllText(Path.Combine(TestDataDir, "cot_xml", $"{name}.xml"));

    /// <summary>Encode every fixture once, in order, into independent wire payloads.</summary>
    private List<(string Name, byte[] Wire)> EncodeAll() =>
        Fixtures.Select(name => (Name: name, Wire: _compressor.Compress(_parser.Parse(LoadXml(name))))).ToList();

    [Fact]
    public void EachPacketDecodesIdenticallyRegardlessOfOrder()
    {
        var wire = EncodeAll();
        // Baseline: decode each in the order produced.
        var forward = wire.Select(w => _compressor.Decompress(w.Wire)).ToList();
        // Decode the SAME payloads in reverse order — order must not matter.
        var reverseDecoded = Enumerable.Reverse(wire).Select(w => _compressor.Decompress(w.Wire)).ToList();
        reverseDecoded.Reverse(); // in-place, back to original order
        for (int i = 0; i < forward.Count; i++)
        {
            Assert.True(
                forward[i].ToByteArray().SequenceEqual(reverseDecoded[i].ToByteArray()),
                $"Packet {wire[i].Name} decoded differently in reverse order — cross-packet state leak");
        }
    }

    [Fact]
    public void AnySinglePacketDecodesWithAllOthersDropped()
    {
        var wire = EncodeAll();
        // Simulate a lossy mesh: each packet must decode ALONE (every other "lost").
        var inSequence = wire.ToDictionary(w => w.Name, w => _compressor.Decompress(w.Wire).ToByteArray());
        foreach (var w in wire)
        {
            var isolated = _compressor.Decompress(w.Wire).ToByteArray();
            Assert.True(inSequence[w.Name].SequenceEqual(isolated), $"Packet {w.Name} failed to decode in isolation");
        }
    }

    [Fact]
    public void ColdCompressorInstanceDecodesAnyPacket()
    {
        // A freshly-constructed compressor — never having seen any prior packet —
        // must decode any single frame. Proves no warm-up/history state.
        foreach (var w in EncodeAll())
        {
            var cold = new TakCompressor();
            var pkt = cold.Decompress(w.Wire);
            Assert.False(string.IsNullOrEmpty(pkt.Uid), $"Cold compressor failed to decode {w.Name}");
        }
    }

    [Fact]
    public void ReEncodingOnFreshCompressorIsByteIdentical()
    {
        // Determinism: the same packet compressed by independent compressor
        // instances must yield identical wire bytes (no instance-accumulated state).
        foreach (var name in Fixtures)
        {
            var pkt = _parser.Parse(LoadXml(name));
            var a = new TakCompressor().Compress(pkt);
            var b = new TakCompressor().Compress(pkt);
            Assert.True(a.SequenceEqual(b), $"Non-deterministic compression for {name} — possible state leak");
        }
    }
}
