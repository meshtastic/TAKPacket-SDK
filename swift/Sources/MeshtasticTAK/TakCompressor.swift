import Foundation
import CZstd

/// Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries.
///
/// Wire format: [1 byte flags][zstd-compressed protobuf bytes]
/// Flags byte bits 0-5 = dictionary ID, bits 6-7 = reserved.
/// Special value 0xFF = uncompressed raw protobuf.
public class TakCompressor {

    /// Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.
    private let maxDecompressedSize = 4096

    private let compressionLevel: Int32

    public init(compressionLevel: Int32 = 19) {
        self.compressionLevel = compressionLevel
    }

    /// Compress a TAKPacketV2 into wire payload: [flags byte][zstd compressed protobuf]
    public func compress(_ packet: TAKPacketV2) throws -> Data {
        let protobufBytes = try packet.serializedData()
        let dictId = DictionaryProvider.selectDictId(
            cotTypeId: packet.cotTypeID,
            cotTypeStr: packet.cotTypeStr.isEmpty ? nil : packet.cotTypeStr
        )
        guard let dictData = DictionaryProvider.getDictionary(dictId) else {
            throw TakCompressorError.noDictionary(dictId)
        }

        let compressed = try compressWithDict(protobufBytes, dict: dictData)
        var wirePayload = Data(capacity: 1 + compressed.count)
        wirePayload.append(UInt8(dictId & 0x3F))
        wirePayload.append(compressed)
        return wirePayload
    }

    /// Decompress wire payload back to TAKPacketV2.
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
        public let wirePayload: Data?
        public let remarksStripped: Bool

        /// Did this call produce a sendable wire payload?
        public var fits: Bool { wirePayload != nil }
    }

    /// Compress and return stats for reporting.
    public func compressWithStats(_ packet: TAKPacketV2) throws -> CompressionResult {
        let protobufBytes = try packet.serializedData()
        let wirePayload = try compress(packet)
        let dictId = DictionaryProvider.selectDictId(
            cotTypeId: packet.cotTypeID,
            cotTypeStr: packet.cotTypeStr.isEmpty ? nil : packet.cotTypeStr
        )
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

        let cdict = dict.withUnsafeBytes { dictPtr -> OpaquePointer? in
            ZSTD_createCDict(dictPtr.baseAddress, dictPtr.count, compressionLevel)
        }
        defer { if let cdict { ZSTD_freeCDict(cdict) } }
        guard let cdict else { throw TakCompressorError.dictCreationFailed }

        let bound = ZSTD_compressBound(input.count)
        var output = Data(count: bound)

        let compressedSize = output.withUnsafeMutableBytes { outPtr in
            input.withUnsafeBytes { inPtr in
                ZSTD_compress_usingCDict(cctx, outPtr.baseAddress, outPtr.count,
                                          inPtr.baseAddress, inPtr.count, cdict)
            }
        }

        if ZSTD_isError(compressedSize) != 0 {
            let msg = String(cString: ZSTD_getErrorName(compressedSize))
            throw TakCompressorError.compressionFailed(msg)
        }

        return output.prefix(compressedSize)
    }

    private func decompressWithDict(_ input: Data, dict: Data) throws -> Data {
        let dctx = ZSTD_createDCtx()
        defer { ZSTD_freeDCtx(dctx) }

        let ddict = dict.withUnsafeBytes { dictPtr -> OpaquePointer? in
            ZSTD_createDDict(dictPtr.baseAddress, dictPtr.count)
        }
        defer { if let ddict { ZSTD_freeDDict(ddict) } }
        guard let ddict else { throw TakCompressorError.dictCreationFailed }

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

public struct CompressionResult {
    public let protobufSize: Int
    public let compressedSize: Int
    public let dictId: Int
    public let wirePayload: Data

    public var dictName: String {
        switch dictId {
        case DictionaryProvider.DICT_ID_NON_AIRCRAFT: return "non-aircraft"
        case DictionaryProvider.DICT_ID_AIRCRAFT: return "aircraft"
        default: return "unknown"
        }
    }
}

public enum TakCompressorError: Error {
    case noDictionary(Int)
    case unknownDictionary(Int)
    case payloadTooShort(Int)
    case dictCreationFailed
    case compressionFailed(String)
    case decompressionFailed(String)
}
