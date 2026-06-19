using Google.Protobuf;
using Meshtastic.Protobufs;
using ZstdSharp;
using ZstdSharp.Unsafe;

namespace Meshtastic.TAK;

/// <summary>
/// Compression statistics returned by <see cref="TakCompressor.CompressWithStats"/>.
/// </summary>
/// <param name="ProtobufSize">Size in bytes of the serialized protobuf before compression.</param>
/// <param name="CompressedSize">Size in bytes of the full wire payload (1-byte flags prefix + body).</param>
/// <param name="DictId">The dictionary ID actually emitted in the flags byte —
/// <see cref="DictionaryProvider.DictIdNonAircraft"/>,
/// <see cref="DictionaryProvider.DictIdAircraft"/>, or
/// <see cref="DictionaryProvider.DictIdUncompressed"/> when the skip-compress path
/// emitted a raw protobuf body.</param>
/// <param name="WirePayload">The full wire payload bytes.</param>
public record CompressionResult(int ProtobufSize, int CompressedSize, int DictId, byte[] WirePayload)
{
    /// <summary>
    /// Human-readable name of the emitted dictionary mode (<c>"non-aircraft"</c>,
    /// <c>"aircraft"</c>, <c>"uncompressed"</c>, or <c>"unknown"</c>), derived from
    /// <see cref="DictId"/>.
    /// </summary>
    public string DictName => DictId switch
    {
        DictionaryProvider.DictIdNonAircraft => "non-aircraft",
        DictionaryProvider.DictIdAircraft => "aircraft",
        DictionaryProvider.DictIdUncompressed => "uncompressed",
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

/// <summary>
/// Compresses and decompresses <c>TAKPacketV2</c> protobuf packets to and from
/// the on-wire LoRa payload format.
/// </summary>
/// <remarks>
/// <para>The wire payload is <c>[1 byte flags][zstd body]</c>. Bits 0–5 of the
/// flags byte carry the dictionary ID; the reserved value
/// <see cref="DictionaryProvider.DictIdUncompressed"/> (<c>0xFF</c>) marks a raw,
/// uncompressed protobuf body. Compression uses zstd dictionary compression with
/// a statically shipped dictionary selected per packet.</para>
/// <para>Two frame-slimming optimizations apply on the wire:</para>
/// <list type="bullet">
/// <item><description>The 4-byte zstd frame magic is stripped on compress and
/// re-prepended on decompress (see <see cref="ZstdMagic"/>).</description></item>
/// <item><description>Skip-compress: if the raw protobuf is no larger than the
/// zstd body, the raw bytes are emitted under the <c>0xFF</c> flag so tiny or
/// incompressible packets never expand.</description></item>
/// </list>
/// <para>Resilience invariant: every packet is fully and independently decodable
/// from its own bytes plus the static dictionary. Only the one-shot zstd API is
/// used — never the streaming API — so there is zero cross-packet state, which
/// matters on a lossy LoRa mesh where any packet may be lost.</para>
/// </remarks>
public class TakCompressor
{
    /// <summary>Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.</summary>
    public const int MaxDecompressedSize = 4096;

    /// <summary>
    /// The 4-byte zstd frame magic (little-endian 0xFD2FB528). Stripped on
    /// compress and prepended on decompress: the SDK is both ends and identifies
    /// the frame from its own 1-byte flags prefix, so the magic is pure overhead.
    /// Done in app code (not a native magicless flag) so the wire bytes stay
    /// byte-identical across all 5 language bindings — TypeScript's zstd-napi
    /// cannot set the experimental magicless parameter. The magic is a fixed
    /// constant, so this stays fully stateless: every frame is independently
    /// reconstructable.
    /// </summary>
    private static readonly byte[] ZstdMagic = { 0x28, 0xB5, 0x2F, 0xFD };

    private readonly int _level;

    /// <summary>
    /// Create a compressor at the given zstd compression level.
    /// </summary>
    /// <param name="level">The zstd compression level. Defaults to 19 (zstd
    /// maximum), the level used to produce the canonical golden wire payloads.</param>
    public TakCompressor(int level = 19) => _level = level;

    /// <summary>
    /// Compress a packet into its on-wire payload <c>[1 byte flags][zstd body]</c>.
    /// </summary>
    /// <remarks>
    /// <para>Selects the dictionary from the packet's CoT type (see
    /// <see cref="DictionaryProvider.SelectDictId"/>), compresses with content-size,
    /// checksum, and dictionary-ID frame fields all disabled, then strips the 4-byte
    /// zstd magic (see <see cref="ZstdMagic"/>). If the raw protobuf is no larger
    /// than the compressed body, emits <c>[0xFF][raw protobuf]</c> instead so the
    /// packet never expands. The dictionary ID is written into bits 0–5 of the flags
    /// byte; bits 6–7 are left zero.</para>
    /// </remarks>
    /// <param name="packet">The packet to compress.</param>
    /// <returns>The wire payload bytes.</returns>
    /// <exception cref="InvalidOperationException">Thrown if no dictionary maps to
    /// the selected ID, or if the zstd frame does not begin with the expected magic
    /// (which would break the strip/prepend contract).</exception>
    public byte[] Compress(Meshtastic.Protobufs.TAKPacketV2 packet)
    {
        var protoBytes = packet.ToByteArray();
        var dictId = DictionaryProvider.SelectDictId(packet.CotTypeId != 0 ? (int)packet.CotTypeId : 0,
            string.IsNullOrEmpty(packet.CotTypeStr) ? null : packet.CotTypeStr);
        var dict = DictionaryProvider.GetDictionary(dictId)
            ?? throw new InvalidOperationException($"No dictionary for ID {dictId}");

        // Strip redundant frame fields: dict ID lives in our flags byte;
        // content-size / checksum are dead weight on tiny dict-compressed payloads.
        using var compressor = new ZstdSharp.Compressor(_level);
        compressor.SetParameter(ZSTD_cParameter.ZSTD_c_contentSizeFlag, 0);
        compressor.SetParameter(ZSTD_cParameter.ZSTD_c_checksumFlag, 0);
        compressor.SetParameter(ZSTD_cParameter.ZSTD_c_dictIDFlag, 0);
        compressor.LoadDictionary(dict);
        var framed = compressor.Wrap(protoBytes).ToArray();

        // Strip the 4-byte magic (see ZstdMagic). Defensive: the frame must start
        // with the known magic or our strip/prepend contract is broken.
        if (framed.Length < 4 || framed[0] != ZstdMagic[0] || framed[1] != ZstdMagic[1]
            || framed[2] != ZstdMagic[2] || framed[3] != ZstdMagic[3])
        {
            throw new InvalidOperationException("Unexpected zstd frame header (magic mismatch)");
        }
        var body = framed.AsSpan(4).ToArray();

        // Skip compression when it doesn't pay (tiny payloads where frame +
        // dict-reference overhead exceeds entropy saved). The 0xFF uncompressed
        // path is already understood by every decoder. Ties -> raw (cheaper decode).
        if (protoBytes.Length <= body.Length)
        {
            var raw = new byte[1 + protoBytes.Length];
            raw[0] = unchecked((byte)DictionaryProvider.DictIdUncompressed);
            protoBytes.CopyTo(raw.AsSpan(1));
            return raw;
        }

        var wire = new byte[1 + body.Length];
        wire[0] = (byte)(dictId & 0x3F);
        body.CopyTo(wire.AsSpan(1));
        return wire;
    }

    /// <summary>
    /// Decompress a wire payload back into a <c>TAKPacketV2</c> packet.
    /// </summary>
    /// <remarks>
    /// <para>Reads the dictionary ID from bits 0–5 of the flags byte (or recognizes
    /// the <see cref="DictionaryProvider.DictIdUncompressed"/> raw path), re-attaches
    /// the stripped 4-byte zstd magic (see <see cref="ZstdMagic"/>), and decompresses
    /// using the matching shipped dictionary. The packet is decoded entirely from its
    /// own bytes plus the static dictionary — no cross-packet state.</para>
    /// </remarks>
    /// <param name="wirePayload">The wire payload bytes (flags prefix + body).</param>
    /// <returns>The decoded packet.</returns>
    /// <exception cref="ArgumentException">Thrown if the payload is too short or its
    /// flags byte names an unknown dictionary ID.</exception>
    /// <exception cref="InvalidOperationException">Thrown if zstd decompression fails,
    /// the decompressed size exceeds <see cref="MaxDecompressedSize"/>, or protobuf
    /// parsing fails.</exception>
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
                // Re-attach the 4-byte magic stripped on compress (see ZstdMagic),
                // yielding a standard frame the stock decoder accepts. The supplied
                // dict (not a frame-embedded dict ID) selects the dictionary.
                var restored = new byte[ZstdMagic.Length + compressedBytes.Length];
                ZstdMagic.CopyTo(restored.AsSpan(0));
                compressedBytes.CopyTo(restored.AsSpan(ZstdMagic.Length));
                protoBytes = decompressor.Unwrap(restored).ToArray();
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

    /// <summary>
    /// Compress a packet and report size statistics alongside the wire payload.
    /// </summary>
    /// <remarks>
    /// The reported <see cref="CompressionResult.DictId"/> reflects the mode actually
    /// emitted: the skip-compress path may report
    /// <see cref="DictionaryProvider.DictIdUncompressed"/> when compression did not pay.
    /// </remarks>
    /// <param name="packet">The packet to compress.</param>
    /// <returns>A <see cref="CompressionResult"/> with the protobuf size, wire size,
    /// emitted dictionary ID, and wire payload.</returns>
    public CompressionResult CompressWithStats(Meshtastic.Protobufs.TAKPacketV2 packet)
    {
        var protoBytes = packet.ToByteArray();
        var wire = Compress(packet);
        // Report the ACTUAL emitted mode from the flags byte — skip-compress may
        // have emitted 0xFF (uncompressed) when compression didn't pay.
        int flags = wire[0];
        var dictId = flags == DictionaryProvider.DictIdUncompressed ? flags : (flags & 0x3F);
        return new CompressionResult(protoBytes.Length, wire.Length, dictId, wire);
    }
}
