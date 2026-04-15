using Meshtastic.TAK;

namespace Meshtastic.TAK.Tests;

public class TakMalformedTests
{
    private static readonly string MalformedDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata", "malformed"));

    private readonly TakCompressor _compressor = new();

    private byte[] Load(string name) => File.ReadAllBytes(Path.Combine(MalformedDir, name));

    [Fact]
    public void RejectsEmptyPayload()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Array.Empty<byte>()));
    }

    [Fact]
    public void RejectsSingleByte()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(new byte[] { 0x00 }));
    }

    [Fact]
    public void RejectsInvalidDictId()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("invalid_dict_id.bin")));
    }

    [Fact]
    public void RejectsTruncatedZstd()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("truncated_zstd.bin")));
    }

    [Fact]
    public void RejectsCorruptedZstd()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("corrupted_zstd.bin")));
    }

    [Fact]
    public void HandlesInvalidProtobufWithoutCrash()
    {
        // 0xFF + garbage bytes — no crash is the key assertion
        try { _compressor.Decompress(Load("invalid_protobuf.bin")); }
        catch { /* Expected */ }
    }

    [Fact]
    public void IgnoresReservedBitsInFlagsByte()
    {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        var pkt = _compressor.Decompress(Load("reserved_bits_set.bin"));
        Assert.NotEmpty(pkt.Uid);
    }

    // Security attack tests

    [Fact]
    public void RejectsXmlWithDoctype()
    {
        var xml = File.ReadAllText(Path.Combine(MalformedDir, "xml_doctype.xml"));
        var parser = new CotXmlParser();
        Assert.ThrowsAny<Exception>(() => parser.Parse(xml));
    }

    [Fact]
    public void RejectsXmlWithEntityExpansion()
    {
        var xml = File.ReadAllText(Path.Combine(MalformedDir, "xml_entity_expansion.xml"));
        var parser = new CotXmlParser();
        Assert.ThrowsAny<Exception>(() => parser.Parse(xml));
    }

    [Fact]
    public void RejectsOversizedFields()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("oversized_callsign.bin")));
    }

    [Fact]
    public void RejectsDecompressionBomb()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("decompression_bomb.bin")));
    }
}
