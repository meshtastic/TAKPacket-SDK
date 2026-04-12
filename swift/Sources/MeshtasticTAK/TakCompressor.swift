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

    /// Compress a packet using whichever format yields the smaller wire
    /// payload: the fully-typed `TAKPacketV2` (the default `compress(_:)`
    /// path) or a `raw_detail` fallback carrying the original `<detail>`
    /// bytes.
    ///
    /// On every bundled fixture the typed path wins — delta-encoded
    /// geometry and palette-enum colors compress much tighter than raw
    /// XML tag names, even with a 16KB zstd dictionary.  This method is a
    /// safety net for CoT types the structured parser can't decompose or
    /// for shapes with geometry beyond `MAX_VERTICES` that would otherwise
    /// be silently truncated.
    ///
    /// The fallback path strips detail-derived top-level fields (callsign,
    /// takVersion, …) from the alternate packet so the `<detail>` content
    /// isn't duplicated on the wire; the receiver re-parses those fields
    /// out of the raw bytes when needed.
    ///
    /// - Parameters:
    ///   - packet:         The typed-variant packet from
    ///                     `CotXmlParser().parse(_:)`.
    ///   - rawDetailBytes: The raw `<detail>` inner bytes from
    ///                     `CotXmlParser.extractRawDetailBytes(_:)`.
    /// - Returns: Whichever wire payload is smaller.  Ties go to the typed
    ///            packet since it preserves strong typing on decode.
    public func compressBestOf(_ packet: TAKPacketV2, rawDetailBytes: Data) throws -> Data {
        let typedWire = try compress(packet)
        if rawDetailBytes.isEmpty { return typedWire }

        // Clear every detail-derived top-level field in the fallback
        // packet — contact, __group, status, track, takv, precisionlocation
        // and the <uid Droid="…"/> hint all live inside <detail>, so they
        // would otherwise be shipped twice (once as proto fields, once
        // inside the raw_detail bytes).  The envelope (uid, cotTypeId, how,
        // stale, lat/lon/alt) stays intact.
        var rawPacket = packet
        rawPacket.callsign = ""
        rawPacket.team = .unspecifedColor
        rawPacket.role = .unspecifed
        rawPacket.battery = 0
        rawPacket.speed = 0
        rawPacket.course = 0
        rawPacket.deviceCallsign = ""
        rawPacket.takVersion = ""
        rawPacket.takDevice = ""
        rawPacket.takPlatform = ""
        rawPacket.takOs = ""
        rawPacket.endpoint = ""
        rawPacket.phone = ""
        rawPacket.geoSrc = .unspecified
        rawPacket.altSrc = .unspecified
        rawPacket.rawDetail = rawDetailBytes  // sets the oneof to raw_detail

        let rawWire = try compress(rawPacket)
        return rawWire.count < typedWire.count ? rawWire : typedWire
    }

    /// Compress a packet, stripping remarks if the result exceeds `maxWireBytes`.
    ///
    /// First attempts compression with remarks intact. If the wire payload
    /// fits within `maxWireBytes`, returns it as-is. Otherwise, clears the
    /// remarks field and re-compresses. Returns `nil` if even the stripped
    /// packet exceeds the limit (caller should drop the packet).
    public func compressWithRemarksFallback(_ packet: TAKPacketV2, maxWireBytes: Int) throws -> Data? {
        let full = try compress(packet)
        if full.count <= maxWireBytes { return full }

        guard !packet.remarks.isEmpty else { return nil }
        var stripped = packet
        stripped.remarks = ""
        let strippedWire = try compress(stripped)
        return strippedWire.count <= maxWireBytes ? strippedWire : nil
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
