import Foundation
import CZstd

/// Compresses ``TAKPacketV2`` protobuf bytes into the LoRa wire payload and back,
/// using zstd with the pre-trained dictionaries from ``DictionaryProvider``.
///
/// ## Wire format
/// `[1 byte flags][zstd-compressed protobuf body]`, total ≤ 237 bytes (the LoRa
/// MTU). In the flags byte, bits 0–5 are the dictionary ID and bits 6–7 are
/// reserved (zeroed on send, masked off on receive). The sentinel value `0xFF`
/// means the body is raw, uncompressed protobuf.
///
/// ## Encode pipeline
/// `compress(_:)` serializes the packet, selects the dictionary from the CoT
/// type, zstd-compresses with `dictID`/`contentSize`/`checksum` all disabled,
/// then strips the 4-byte zstd magic (`28 B5 2F FD`). A **skip-compress** guard
/// emits `[0xFF][raw protobuf]` whenever the raw bytes are no larger than the
/// compressed body, so tiny or incompressible packets never expand on the wire.
/// `decompress(_:)` reverses this: re-prepend the magic, decompress, and reject
/// anything over 4096 bytes (a decompression-bomb guard).
///
/// ## Resilience invariant
/// Every frame is independently decodable from its own bytes plus the static
/// shipped dictionary — there is zero cross-packet state. Only zstd's one-shot
/// API is used (never the streaming API), so a lost LoRa packet never affects
/// any other.
///
/// ## Topics
/// ### Creating a compressor
/// - ``init(compressionLevel:)``
/// ### Encoding and decoding
/// - ``compress(_:)``
/// - ``decompress(_:)``
/// ### MTU-aware encoding
/// - ``compressWithRemarksFallback(_:maxWireBytes:)``
/// - ``compressWithRemarksFallbackDetailed(_:maxWireBytes:)``
/// - ``RemarksFallbackResult``
/// ### Reporting
/// - ``compressWithStats(_:)``
public class TakCompressor {

    /// Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.
    private let maxDecompressedSize = 4096

    /// The 4-byte zstd frame magic (little-endian 0xFD2FB528). Stripped on
    /// compress and prepended on decompress: the SDK is both ends and identifies
    /// the frame from its own 1-byte flags prefix, so the magic is pure overhead.
    /// Done in app code (not a native magicless flag) so the wire bytes stay
    /// byte-identical across all 5 language bindings — TypeScript's zstd-napi
    /// cannot set the experimental magicless parameter. The magic is a fixed
    /// constant, so this stays fully stateless: every frame is independently
    /// reconstructable.
    private let zstdMagic = Data([0x28, 0xB5, 0x2F, 0xFD])

    private let compressionLevel: Int32

    /// Create a compressor.
    ///
    /// - Parameter compressionLevel: The zstd compression level. Defaults to
    ///   `19` (zstd maximum), which is what the SDK uses on the wire and what
    ///   the golden fixtures are generated with.
    public init(compressionLevel: Int32 = 19) {
        self.compressionLevel = compressionLevel
    }

    /// Compress a ``TAKPacketV2`` into a wire payload — `[flags byte][zstd body]`,
    /// or `[0xFF][raw protobuf]` when compression doesn't pay.
    ///
    /// Serializes the packet, selects the dictionary from its CoT type (via
    /// ``DictionaryProvider/selectDictId(cotTypeId:cotTypeStr:)``), compresses
    /// with the redundant frame fields disabled and the zstd magic stripped, and
    /// applies the skip-compress guard. The returned payload is not checked
    /// against the 237-byte MTU — use ``compressWithRemarksFallback(_:maxWireBytes:)``
    /// when you need to enforce the limit.
    ///
    /// - Parameter packet: The packet to encode.
    /// - Returns: The wire payload bytes.
    /// - Throws: ``TakCompressorError/noDictionary(_:)`` if the selected
    ///   dictionary is unavailable, ``TakCompressorError/dictCreationFailed`` if
    ///   the dictionary fails to load, or ``TakCompressorError/compressionFailed(_:)``
    ///   on a zstd error or unexpected frame header. May also rethrow a protobuf
    ///   serialization error.
    public func compress(_ packet: TAKPacketV2) throws -> Data {
        let protobufBytes = try packet.serializedData()
        let dictId = DictionaryProvider.selectDictId(
            cotTypeId: packet.cotTypeID,
            cotTypeStr: packet.cotTypeStr.isEmpty ? nil : packet.cotTypeStr
        )
        guard let dictData = DictionaryProvider.getDictionary(dictId) else {
            throw TakCompressorError.noDictionary(dictId)
        }

        // Magic-stripped compressed body (frame slimming applied inside).
        let body = try compressWithDict(protobufBytes, dict: dictData)

        // Skip compression when it doesn't pay (tiny payloads where frame +
        // dict-reference overhead exceeds entropy saved). The 0xFF uncompressed
        // path is already understood by every decoder. Ties -> raw (cheaper decode).
        if protobufBytes.count <= body.count {
            var raw = Data(capacity: 1 + protobufBytes.count)
            raw.append(UInt8(DictionaryProvider.DICT_ID_UNCOMPRESSED))
            raw.append(protobufBytes)
            return raw
        }
        var wirePayload = Data(capacity: 1 + body.count)
        wirePayload.append(UInt8(dictId & 0x3F))
        wirePayload.append(body)
        return wirePayload
    }

    /// Decompress a wire payload back into a ``TAKPacketV2``.
    ///
    /// Reads the flags byte: `0xFF` means the remaining bytes are raw protobuf;
    /// otherwise bits 0–5 select the dictionary, the 4-byte zstd magic is
    /// re-prepended, and the body is decompressed. The decompressed size is
    /// capped at 4096 bytes as a decompression-bomb guard. Each payload is
    /// decoded purely from its own bytes plus the static dictionary.
    ///
    /// - Parameter wirePayload: The received wire bytes (flags byte + body).
    /// - Returns: The decoded packet.
    /// - Throws: ``TakCompressorError/payloadTooShort(_:)`` for payloads under
    ///   2 bytes, ``TakCompressorError/unknownDictionary(_:)`` for an
    ///   unrecognized dictionary ID, ``TakCompressorError/dictCreationFailed``
    ///   if the dictionary fails to load, or
    ///   ``TakCompressorError/decompressionFailed(_:)`` on a zstd error or when
    ///   the decompressed size exceeds the 4096-byte limit. May also rethrow a
    ///   protobuf parse error.
    public func decompress(_ wirePayload: Data) throws -> TAKPacketV2 {
        guard wirePayload.count >= 2 else {
            throw TakCompressorError.payloadTooShort(wirePayload.count)
        }

        let flagsByte = Int(wirePayload[0])
        let compressedBytes = wirePayload.subdata(in: 1..<wirePayload.count)

        let protobufBytes: Data
        if flagsByte == DictionaryProvider.DICT_ID_UNCOMPRESSED {
            protobufBytes = compressedBytes
        } else {
            let dictId = flagsByte & 0x3F
            guard let dictData = DictionaryProvider.getDictionary(dictId) else {
                throw TakCompressorError.unknownDictionary(dictId)
            }
            protobufBytes = try decompressWithDict(compressedBytes, dict: dictData)
        }

        if protobufBytes.count > maxDecompressedSize {
            throw TakCompressorError.decompressionFailed("Payload size \(protobufBytes.count) exceeds limit \(maxDecompressedSize)")
        }

        return try TAKPacketV2(serializedBytes: protobufBytes)
    }

    /// Compress a packet, stripping remarks if the result exceeds `maxWireBytes`.
    ///
    /// First attempts compression with remarks intact. If the wire payload
    /// fits within `maxWireBytes`, returns it as-is. Otherwise, clears the
    /// remarks field and re-compresses. Returns `nil` if even the stripped
    /// packet exceeds the limit (caller should drop the packet).
    ///
    /// This is a thin wrapper over `compressWithRemarksFallbackDetailed(_:maxWireBytes:)`
    /// that discards the `remarksStripped` flag. Use the Detailed variant if you
    /// need to tell "fit as-is", "fit after strip", and "dropped" apart — e.g.
    /// for observability or metrics.
    public func compressWithRemarksFallback(_ packet: TAKPacketV2, maxWireBytes: Int) throws -> Data? {
        try compressWithRemarksFallbackDetailed(packet, maxWireBytes: maxWireBytes).wirePayload
    }

    /// Compress a packet, stripping remarks if needed, and return a detailed result
    /// that distinguishes the four possible outcomes:
    ///
    /// | `wirePayload` | `remarksStripped` | Meaning                              |
    /// |---------------|-------------------|--------------------------------------|
    /// | bytes         | false             | Fit as-is, no stripping needed       |
    /// | bytes         | true              | Stripped remarks to make it fit      |
    /// | nil           | false             | Too big, had no remarks to strip     |
    /// | nil           | true              | Stripped remarks, still too big      |
    ///
    /// Callers that want to log/meter "how often does remarks-stripping save a
    /// packet" should use this variant; `compressWithRemarksFallback` loses the
    /// distinction.
    public func compressWithRemarksFallbackDetailed(
        _ packet: TAKPacketV2,
        maxWireBytes: Int
    ) throws -> RemarksFallbackResult {
        let full = try compress(packet)
        if full.count <= maxWireBytes {
            return RemarksFallbackResult(wirePayload: full, remarksStripped: false)
        }

        guard !packet.remarks.isEmpty else {
            return RemarksFallbackResult(wirePayload: nil, remarksStripped: false)
        }

        var stripped = packet
        stripped.remarks = ""
        let strippedWire = try compress(stripped)
        if strippedWire.count <= maxWireBytes {
            return RemarksFallbackResult(wirePayload: strippedWire, remarksStripped: true)
        } else {
            return RemarksFallbackResult(wirePayload: nil, remarksStripped: true)
        }
    }

    /// Result of ``compressWithRemarksFallbackDetailed(_:maxWireBytes:)``.
    ///
    /// - `wirePayload`: the compressed wire bytes if the packet fit under the
    ///   limit, or `nil` if the caller should drop the packet.
    /// - `remarksStripped`: `true` if this call stripped the remarks field
    ///   before compressing — either successfully (`wirePayload` is non-nil)
    ///   or unsuccessfully (`wirePayload` is nil).
    public struct RemarksFallbackResult: Equatable {
        /// The compressed wire bytes that fit within the limit, or `nil` if the
        /// caller should drop the packet because even the stripped form was too
        /// big.
        public let wirePayload: Data?
        /// `true` if this call cleared the remarks field before compressing —
        /// whether or not the result ultimately fit (see the outcome table on
        /// ``compressWithRemarksFallbackDetailed(_:maxWireBytes:)``).
        public let remarksStripped: Bool

        /// Did this call produce a sendable wire payload?
        public var fits: Bool { wirePayload != nil }
    }

    /// Compress a packet and return a ``CompressionResult`` carrying the sizes,
    /// the dictionary actually used, and the wire payload — for compression
    /// reporting and metrics.
    ///
    /// The reported `dictId` reflects the mode actually emitted (it is `0xFF`
    /// when the skip-compress path sent raw protobuf), not the dictionary that
    /// would have been chosen for compression.
    ///
    /// - Parameter packet: The packet to encode.
    /// - Returns: A ``CompressionResult`` with the uncompressed protobuf size,
    ///   the wire size, the emitted dictionary ID, and the wire payload.
    /// - Throws: The same errors as ``compress(_:)``.
    public func compressWithStats(_ packet: TAKPacketV2) throws -> CompressionResult {
        let protobufBytes = try packet.serializedData()
        let wirePayload = try compress(packet)
        // Report the ACTUAL emitted mode from the flags byte — skip-compress may
        // have emitted 0xFF (uncompressed) when compression didn't pay.
        let flags = Int(wirePayload[0])
        let dictId = flags == DictionaryProvider.DICT_ID_UNCOMPRESSED ? flags : (flags & 0x3F)
        return CompressionResult(
            protobufSize: protobufBytes.count,
            compressedSize: wirePayload.count,
            dictId: dictId,
            wirePayload: wirePayload
        )
    }

    // MARK: - Zstd wrappers

    private func compressWithDict(_ input: Data, dict: Data) throws -> Data {
        let cctx = ZSTD_createCCtx()
        defer { ZSTD_freeCCtx(cctx) }

        // Advanced API so we can strip redundant frame fields: dict ID lives in
        // our flags byte; content-size / checksum are dead weight on tiny
        // dict-compressed payloads. One independent frame per packet (ZSTD_compress2
        // is one-shot, never the streaming API).
        _ = ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, compressionLevel)
        _ = ZSTD_CCtx_setParameter(cctx, ZSTD_c_contentSizeFlag, 0)
        _ = ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag, 0)
        _ = ZSTD_CCtx_setParameter(cctx, ZSTD_c_dictIDFlag, 0)

        let loadResult = dict.withUnsafeBytes { dictPtr in
            ZSTD_CCtx_loadDictionary(cctx, dictPtr.baseAddress, dictPtr.count)
        }
        if ZSTD_isError(loadResult) != 0 { throw TakCompressorError.dictCreationFailed }

        let bound = ZSTD_compressBound(input.count)
        var output = Data(count: bound)

        let compressedSize = output.withUnsafeMutableBytes { outPtr in
            input.withUnsafeBytes { inPtr in
                ZSTD_compress2(cctx, outPtr.baseAddress, outPtr.count,
                               inPtr.baseAddress, inPtr.count)
            }
        }

        if ZSTD_isError(compressedSize) != 0 {
            let msg = String(cString: ZSTD_getErrorName(compressedSize))
            throw TakCompressorError.compressionFailed(msg)
        }

        let framed = Data(output.prefix(compressedSize))
        // Strip the 4-byte magic (see zstdMagic). Defensive: the frame must start
        // with the known magic or our strip/prepend contract is broken.
        guard framed.count >= 4, framed.prefix(4) == zstdMagic else {
            throw TakCompressorError.compressionFailed("Unexpected zstd frame header (magic mismatch)")
        }
        return framed.subdata(in: 4..<framed.count)
    }

    private func decompressWithDict(_ rawInput: Data, dict: Data) throws -> Data {
        let dctx = ZSTD_createDCtx()
        defer { ZSTD_freeDCtx(dctx) }

        let ddict = dict.withUnsafeBytes { dictPtr -> OpaquePointer? in
            ZSTD_createDDict(dictPtr.baseAddress, dictPtr.count)
        }
        defer { if let ddict { ZSTD_freeDDict(ddict) } }
        guard let ddict else { throw TakCompressorError.dictCreationFailed }

        // Re-attach the 4-byte magic stripped on compress (see zstdMagic),
        // yielding a standard frame the stock decoder accepts. The supplied
        // dict (not a frame-embedded dict ID) selects the dictionary.
        var input = zstdMagic
        input.append(rawInput)

        let frameSize = input.withUnsafeBytes { ZSTD_getFrameContentSize($0.baseAddress, $0.count) }
        let maxSize: Int
        if frameSize > 0 && frameSize != UInt64(ZSTD_CONTENTSIZE_UNKNOWN) && frameSize != UInt64(ZSTD_CONTENTSIZE_ERROR) && frameSize <= UInt64(maxDecompressedSize) {
            maxSize = Int(frameSize)
        } else {
            maxSize = maxDecompressedSize
        }
        var output = Data(count: maxSize)

        let decompressedSize = output.withUnsafeMutableBytes { outPtr in
            input.withUnsafeBytes { inPtr in
                ZSTD_decompress_usingDDict(dctx, outPtr.baseAddress, outPtr.count,
                                            inPtr.baseAddress, inPtr.count, ddict)
            }
        }

        if ZSTD_isError(decompressedSize) != 0 {
            let msg = String(cString: ZSTD_getErrorName(decompressedSize))
            throw TakCompressorError.decompressionFailed(msg)
        }

        return output.prefix(decompressedSize)
    }
}

/// Size and dictionary statistics for a single ``TakCompressor/compressWithStats(_:)``
/// call, used for compression reporting.
public struct CompressionResult {
    /// Size in bytes of the uncompressed serialized ``TAKPacketV2`` protobuf.
    public let protobufSize: Int
    /// Size in bytes of the emitted wire payload (flags byte + body).
    public let compressedSize: Int
    /// The dictionary ID actually emitted: `0` (non-aircraft), `1` (aircraft),
    /// or `0xFF` (uncompressed, via the skip-compress path).
    public let dictId: Int
    /// The emitted wire payload bytes.
    public let wirePayload: Data

    /// A human-readable name for ``dictId``: `"non-aircraft"`, `"aircraft"`,
    /// `"uncompressed"`, or `"unknown"`.
    public var dictName: String {
        switch dictId {
        case DictionaryProvider.DICT_ID_NON_AIRCRAFT: return "non-aircraft"
        case DictionaryProvider.DICT_ID_AIRCRAFT: return "aircraft"
        case DictionaryProvider.DICT_ID_UNCOMPRESSED: return "uncompressed"
        default: return "unknown"
        }
    }
}

/// Errors thrown by ``TakCompressor`` during encode and decode.
public enum TakCompressorError: Error {
    /// No dictionary is available for the ID selected during ``TakCompressor/compress(_:)``.
    /// The associated value is the dictionary ID.
    case noDictionary(Int)
    /// The received flags byte selected a dictionary ID that isn't recognized.
    /// The associated value is the offending ID.
    case unknownDictionary(Int)
    /// The received wire payload was shorter than the 2-byte minimum. The
    /// associated value is the actual byte count.
    case payloadTooShort(Int)
    /// A zstd dictionary handle could not be created from the dictionary bytes.
    case dictCreationFailed
    /// zstd compression failed, or the produced frame did not begin with the
    /// expected magic number. The associated value is the underlying message.
    case compressionFailed(String)
    /// zstd decompression failed, or the decompressed size exceeded the
    /// 4096-byte limit. The associated value is the underlying message.
    case decompressionFailed(String)
}
