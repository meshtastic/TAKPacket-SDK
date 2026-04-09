using Google.Protobuf;
using Meshtastic.Protobufs;
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

    /// <summary>
    /// Compress a packet using whichever format yields the smaller wire
    /// payload: the fully-typed <see cref="TAKPacketV2"/> (the default
    /// <see cref="Compress"/> path) or a <c>raw_detail</c> fallback
    /// carrying the original <c>&lt;detail&gt;</c> bytes.
    /// </summary>
    /// <remarks>
    /// <para>
    /// On every bundled fixture the typed path wins — delta-encoded
    /// geometry and palette-enum colors compress much tighter than raw
    /// XML tag names, even with a 16KB zstd dictionary.  This method is a
    /// safety net for CoT types the structured parser can't decompose or
    /// for shapes with geometry beyond <c>MAX_VERTICES</c> that would
    /// otherwise be silently truncated.
    /// </para>
    /// <para>
    /// The fallback path strips detail-derived top-level fields
    /// (callsign, takVersion, …) from the alternate packet so the
    /// <c>&lt;detail&gt;</c> content isn't duplicated on the wire; the
    /// receiver re-parses those fields out of the raw bytes when needed.
    /// </para>
    /// </remarks>
    /// <param name="packet">Typed-variant packet from <see cref="CotXmlParser.Parse"/>.</param>
    /// <param name="rawDetailBytes">Raw <c>&lt;detail&gt;</c> inner bytes
    /// from <see cref="CotXmlParser.ExtractRawDetailBytes"/>.</param>
    /// <returns>Whichever wire payload is smaller.  Ties go to the typed
    /// packet since it preserves strong typing on decode.</returns>
    public byte[] CompressBestOf(TAKPacketV2 packet, byte[] rawDetailBytes)
    {
        var typedWire = Compress(packet);
        if (rawDetailBytes is null || rawDetailBytes.Length == 0) return typedWire;

        // Clone the packet and clear every detail-derived top-level field.
        // The envelope (Uid, CotTypeId, How, Stale, Lat/Lon/Alt) stays
        // intact; assigning RawDetail clears the existing oneof case.
        var rawPacket = packet.Clone();
        rawPacket.Callsign = "";
        rawPacket.Team = Team.UnspecifedColor;
        rawPacket.Role = MemberRole.Unspecifed;
        rawPacket.Battery = 0;
        rawPacket.Speed = 0;
        rawPacket.Course = 0;
        rawPacket.DeviceCallsign = "";
        rawPacket.TakVersion = "";
        rawPacket.TakDevice = "";
        rawPacket.TakPlatform = "";
        rawPacket.TakOs = "";
        rawPacket.Endpoint = "";
        rawPacket.Phone = "";
        rawPacket.GeoSrc = GeoPointSource.Unspecified;
        rawPacket.AltSrc = GeoPointSource.Unspecified;
        rawPacket.RawDetail = ByteString.CopyFrom(rawDetailBytes);

        var rawWire = Compress(rawPacket);
        return rawWire.Length < typedWire.Length ? rawWire : typedWire;
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
