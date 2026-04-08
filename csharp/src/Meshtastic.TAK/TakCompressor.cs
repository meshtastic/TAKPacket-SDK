using Google.Protobuf;
using ZstdSharp;

namespace Meshtastic.TAK;

public record CompressionResult(int ProtobufSize, int CompressedSize, int DictId, byte[] WirePayload)
{
    public string DictName => DictId switch
    {
        DictionaryProvider.DictIdNonAircraft => "non-aircraft",
        DictionaryProvider.DictIdAircraft => "aircraft",
        _ => "unknown",
    };
}

public class TakCompressor
{
    /// <summary>Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.</summary>
    public const int MaxDecompressedSize = 4096;

    private readonly int _level;

    public TakCompressor(int level = 19) => _level = level;

    public byte[] Compress(Meshtastic.Protobufs.TAKPacketV2 packet)
    {
        var protoBytes = packet.ToByteArray();
        var dictId = DictionaryProvider.SelectDictId(packet.CotTypeId != 0 ? (int)packet.CotTypeId : 0,
            string.IsNullOrEmpty(packet.CotTypeStr) ? null : packet.CotTypeStr);
        var dict = DictionaryProvider.GetDictionary(dictId)
            ?? throw new InvalidOperationException($"No dictionary for ID {dictId}");

        using var compressor = new ZstdSharp.Compressor(_level);
        compressor.LoadDictionary(dict);
        var compressed = compressor.Wrap(protoBytes);

        var wire = new byte[1 + compressed.Length];
        wire[0] = (byte)(dictId & 0x3F);
        compressed.CopyTo(wire.AsSpan(1));
        return wire;
    }

    public Meshtastic.Protobufs.TAKPacketV2 Decompress(byte[] wirePayload)
    {
        if (wirePayload.Length < 2)
            throw new ArgumentException($"Payload too short: {wirePayload.Length}");

        var flagsByte = wirePayload[0];
        var compressedBytes = wirePayload.AsSpan(1).ToArray();

        byte[] protoBytes;
        if (flagsByte == DictionaryProvider.DictIdUncompressed)
        {
            protoBytes = compressedBytes;
        }
        else
        {
            var dictId = flagsByte & 0x3F;
            var dict = DictionaryProvider.GetDictionary(dictId)
                ?? throw new ArgumentException($"Unknown dictionary ID: {dictId}");

            try
            {
                using var decompressor = new ZstdSharp.Decompressor();
                decompressor.LoadDictionary(dict);
                protoBytes = decompressor.Unwrap(compressedBytes).ToArray();
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException($"Zstd decompression failed: {ex.Message}", ex);
            }
        }

        if (protoBytes.Length > MaxDecompressedSize)
            throw new InvalidOperationException($"Payload size {protoBytes.Length} exceeds limit {MaxDecompressedSize}");

        try
        {
            return Meshtastic.Protobufs.TAKPacketV2.Parser.ParseFrom(protoBytes);
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException($"Protobuf parsing failed: {ex.Message}", ex);
        }
    }

    public CompressionResult CompressWithStats(Meshtastic.Protobufs.TAKPacketV2 packet)
    {
        var protoBytes = packet.ToByteArray();
        var wire = Compress(packet);
        var dictId = DictionaryProvider.SelectDictId(packet.CotTypeId != 0 ? (int)packet.CotTypeId : 0,
            string.IsNullOrEmpty(packet.CotTypeStr) ? null : packet.CotTypeStr);
        return new CompressionResult(protoBytes.Length, wire.Length, dictId, wire);
    }
}
