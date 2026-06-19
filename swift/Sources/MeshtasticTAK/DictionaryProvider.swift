import Foundation

/// Loads and provides the zstd compression dictionaries embedded as bundle resources.
///
/// The SDK ships two pre-trained zstd dictionaries that are referenced by the
/// dictionary ID carried in the wire-format flags byte (see ``TakCompressor``):
///
/// - **Non-aircraft** (ID `0`, ~512 KB, proto-trained): the default for PLI,
///   GeoChat, ground units, shapes, markers, routes, ranging, alerts, casevac,
///   emergency, task, TAK-Talk, and delete events.
/// - **Aircraft** (ID `1`, ~4 KB): ADS-B / military air tracks (CoT types whose
///   third hierarchical atom is `A`).
///
/// Both dictionaries are trained on serialized `TAKPacketV2` **protobuf** bytes
/// (not raw CoT XML), so they must match across every node on a mesh for frames
/// to be cross-decodable. Each frame is independently decompressible from its
/// own bytes plus the static shipped dictionary — there is no cross-packet state.
///
/// ## Topics
/// ### Dictionary IDs
/// - ``DICT_ID_NON_AIRCRAFT``
/// - ``DICT_ID_AIRCRAFT``
/// - ``DICT_ID_UNCOMPRESSED``
/// ### Loaded dictionaries
/// - ``nonAircraftDict``
/// - ``aircraftDict``
/// ### Lookup
/// - ``getDictionary(_:)``
/// - ``selectDictId(cotTypeId:cotTypeStr:)``
public enum DictionaryProvider {

    /// Dictionary ID for the non-aircraft dictionary (`0`). Occupies bits 0–5 of
    /// the wire-format flags byte for all non-aircraft event types.
    public static let DICT_ID_NON_AIRCRAFT = 0

    /// Dictionary ID for the aircraft dictionary (`1`). Used for CoT types whose
    /// third hierarchical atom is `A` (e.g. `a-n-A-C-F`).
    public static let DICT_ID_AIRCRAFT = 1

    /// Sentinel flags-byte value (`0xFF`) meaning the payload is raw, uncompressed
    /// `TAKPacketV2` protobuf with no dictionary applied. Emitted by the encoder's
    /// skip-compress path when compression would not shrink the payload.
    public static let DICT_ID_UNCOMPRESSED = 0xFF

    /// The non-aircraft zstd dictionary bytes, loaded once from the bundle resource
    /// `dict_non_aircraft.zstd`. Traps at first access if the resource is missing.
    public static let nonAircraftDict: Data = loadResource("dict_non_aircraft", ext: "zstd")

    /// The aircraft zstd dictionary bytes, loaded once from the bundle resource
    /// `dict_aircraft.zstd`. Traps at first access if the resource is missing.
    public static let aircraftDict: Data = loadResource("dict_aircraft", ext: "zstd")

    /// Look up the dictionary bytes for a given dictionary ID.
    ///
    /// - Parameter dictId: The dictionary ID, typically extracted from the
    ///   wire-format flags byte (bits 0–5).
    /// - Returns: The dictionary bytes for ``DICT_ID_NON_AIRCRAFT`` or
    ///   ``DICT_ID_AIRCRAFT``, or `nil` for any unknown ID (including
    ///   ``DICT_ID_UNCOMPRESSED``, which has no associated dictionary).
    public static func getDictionary(_ dictId: Int) -> Data? {
        switch dictId {
        case DICT_ID_NON_AIRCRAFT: return nonAircraftDict
        case DICT_ID_AIRCRAFT: return aircraftDict
        default: return nil
        }
    }

    /// Select the dictionary ID to compress a packet with, based on its CoT type.
    ///
    /// Returns ``DICT_ID_AIRCRAFT`` when the type classifies as aircraft (third
    /// hierarchical atom `A`) and ``DICT_ID_NON_AIRCRAFT`` otherwise. When the
    /// well-known enum is ``CotType/other`` (an unknown type carried as a raw
    /// string), the raw `cotTypeStr` is consulted instead.
    ///
    /// - Parameters:
    ///   - cotTypeId: The well-known CoT type enum from the packet envelope.
    ///   - cotTypeStr: The raw CoT type string fallback, consulted only when
    ///     `cotTypeId` is ``CotType/other``. Pass `nil` when absent.
    /// - Returns: ``DICT_ID_AIRCRAFT`` or ``DICT_ID_NON_AIRCRAFT``.
    public static func selectDictId(cotTypeId: CotType, cotTypeStr: String?) -> Int {
        if cotTypeId != .other {
            return CotTypeMapper.isAircraft(cotTypeId) ? DICT_ID_AIRCRAFT : DICT_ID_NON_AIRCRAFT
        }
        if let str = cotTypeStr, CotTypeMapper.isAircraftString(str) {
            return DICT_ID_AIRCRAFT
        }
        return DICT_ID_NON_AIRCRAFT
    }

    private static func loadResource(_ name: String, ext: String) -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: ext) else {
            fatalError("Dictionary resource not found: \(name).\(ext)")
        }
        do {
            return try Data(contentsOf: url)
        } catch {
            fatalError("Failed to load dictionary: \(error)")
        }
    }
}
