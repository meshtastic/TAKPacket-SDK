import Foundation

/// Loads and provides zstd compression dictionaries embedded as bundle resources.
public enum DictionaryProvider {

    public static let DICT_ID_NON_AIRCRAFT = 0
    public static let DICT_ID_AIRCRAFT = 1
    public static let DICT_ID_UNCOMPRESSED = 0xFF

    public static let nonAircraftDict: Data = loadResource("dict_non_aircraft", ext: "zstd")
    public static let aircraftDict: Data = loadResource("dict_aircraft", ext: "zstd")

    public static func getDictionary(_ dictId: Int) -> Data? {
        switch dictId {
        case DICT_ID_NON_AIRCRAFT: return nonAircraftDict
        case DICT_ID_AIRCRAFT: return aircraftDict
        default: return nil
        }
    }

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
