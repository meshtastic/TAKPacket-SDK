namespace Meshtastic.TAK;

public static class DictionaryProvider
{
    public const int DictIdNonAircraft = 0;
    public const int DictIdAircraft = 1;
    public const int DictIdUncompressed = 0xFF;

    private static byte[]? _nonAircraftDict;
    private static byte[]? _aircraftDict;

    private static readonly string ResourceDir = Path.Combine(
        AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "Resources");

    public static byte[] NonAircraftDict => _nonAircraftDict ??=
        File.ReadAllBytes(Path.Combine(ResourceDir, "dict_non_aircraft.zstd"));

    public static byte[] AircraftDict => _aircraftDict ??=
        File.ReadAllBytes(Path.Combine(ResourceDir, "dict_aircraft.zstd"));

    public static byte[]? GetDictionary(int dictId) => dictId switch
    {
        DictIdNonAircraft => NonAircraftDict,
        DictIdAircraft => AircraftDict,
        _ => null,
    };

    public static int SelectDictId(int cotTypeId, string? cotTypeStr = null)
    {
        if (cotTypeId != 0)
            return CotTypeMapper.IsAircraft(cotTypeId) ? DictIdAircraft : DictIdNonAircraft;
        if (cotTypeStr != null && CotTypeMapper.IsAircraftString(cotTypeStr))
            return DictIdAircraft;
        return DictIdNonAircraft;
    }
}
