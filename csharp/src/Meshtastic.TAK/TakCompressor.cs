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

/// <summary>
/// Result of <see cref="TakCompressor.CompressWithRemarksFallbackDetailed"/>.
/// </summary>
/// <remarks>
/// <list type="table">
/// <listheader><term>WirePayload</term><term>RemarksStripped</term><description>Meaning</description></listheader>
/// <item><term>bytes</term><term>false</term><description>Fit as-is, no stripping needed</description></item>
/// <item><term>bytes</term><term>true</term><description>Stripped remarks to make it fit</description></item>
/// <item><term>null</term><term>false</term><description>Too big, had no remarks to strip</description></item>
/// <item><term>null</term><term>true</term><description>Stripped remarks, still too big</description></item>
/// </list>
/// </remarks>
/// <param name="WirePayload">The compressed wire bytes if the packet fit under the limit,
/// or <c>null</c> if the caller should drop the packet.</param>
/// <param name="RemarksStripped">true if this call stripped the remarks field before
/// compressing — either successfully (<see cref="WirePayload"/> is non-null) or
/// unsuccessfully (<see cref="WirePayload"/> is null).</param>
public record RemarksFallbackResult(byte[]? WirePayload, bool RemarksStripped)
{
    /// <summary>Convenience: did this call produce a sendable wire payload?</summary>
    public bool Fits => WirePayload is not null;
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
    /// Compress a packet, stripping remarks if the result exceeds
    /// <paramref name="maxWireBytes"/>.
    /// </summary>
    /// <remarks>
    /// <para>First attempts compression with remarks intact. If the wire
    /// payload fits within <paramref name="maxWireBytes"/>, returns it as-is.
    /// Otherwise, clears the remarks field and re-compresses. Returns
    /// <c>null</c> if even the stripped packet exceeds the limit (caller
    /// should drop the packet).</para>
    /// <para>This is a thin wrapper over
    /// <see cref="CompressWithRemarksFallbackDetailed"/> that discards the
    /// <c>RemarksStripped</c> flag. Use the Detailed variant if you need to
    /// tell "fit as-is", "fit after strip", and "dropped" apart — e.g. for
    /// observability or metrics.</para>
    /// </remarks>
    /// <param name="packet">The packet with remarks populated.</param>
    /// <param name="maxWireBytes">Maximum allowed wire payload size (e.g. 225).</param>
    /// <returns>The wire payload, or <c>null</c> if the packet is too large
    /// even without remarks.</returns>
    public byte[]? CompressWithRemarksFallback(TAKPacketV2 packet, int maxWireBytes) =>
        CompressWithRemarksFallbackDetailed(packet, maxWireBytes).WirePayload;

    /// <summary>
    /// Compress a packet, stripping remarks if needed, and return a detailed
    /// result that distinguishes the four possible outcomes. See
    /// <see cref="RemarksFallbackResult"/> for the outcome table.
    /// </summary>
    /// <remarks>
    /// Callers that want to log/meter "how often does remarks-stripping save
    /// a packet" should use this variant;
    /// <see cref="CompressWithRemarksFallback"/> loses the distinction.
    /// </remarks>
    public RemarksFallbackResult CompressWithRemarksFallbackDetailed(TAKPacketV2 packet, int maxWireBytes)
    {
        var full = Compress(packet);
        if (full.Length <= maxWireBytes)
        {
            return new RemarksFallbackResult(full, RemarksStripped: false);
        }

        if (string.IsNullOrEmpty(packet.Remarks))
        {
            return new RemarksFallbackResult(null, RemarksStripped: false);
        }

        var stripped = packet.Clone();
        stripped.Remarks = "";
        var strippedWire = Compress(stripped);
        return strippedWire.Length <= maxWireBytes
            ? new RemarksFallbackResult(strippedWire, RemarksStripped: true)
            : new RemarksFallbackResult(null, RemarksStripped: true);
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
