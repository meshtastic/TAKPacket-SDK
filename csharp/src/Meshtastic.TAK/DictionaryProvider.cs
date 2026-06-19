namespace Meshtastic.TAK;

/// <summary>
/// Loads the shipped zstd compression dictionaries and maps between dictionary
/// IDs and the dictionary bytes used by <see cref="TakCompressor"/>.
/// </summary>
/// <remarks>
/// <para>The dictionary ID travels in bits 0–5 of the wire payload's 1-byte
/// flags prefix, so a receiver can pick the matching dictionary from the frame
/// alone — every packet is independently decodable from its own bytes plus the
/// statically shipped dictionary, with zero cross-packet state.</para>
/// <para>Two dictionaries ship: a ~512 KB proto-trained dictionary for
/// non-aircraft traffic (<see cref="DictIdNonAircraft"/>) and a ~4 KB
/// dictionary for aircraft traffic (<see cref="DictIdAircraft"/>). The reserved
/// value <see cref="DictIdUncompressed"/> (<c>0xFF</c>) marks a raw,
/// uncompressed protobuf body rather than a dictionary.</para>
/// <para>Dictionaries are read once from the resource directory and cached for
/// the lifetime of the process.</para>
/// </remarks>
public static class DictionaryProvider
{
    /// <summary>Dictionary ID for non-aircraft traffic (the ~512 KB proto-trained dictionary). Travels in bits 0–5 of the flags byte.</summary>
    public const int DictIdNonAircraft = 0;

    /// <summary>Dictionary ID for aircraft traffic (the ~4 KB dictionary). Travels in bits 0–5 of the flags byte.</summary>
    public const int DictIdAircraft = 1;

    /// <summary>Reserved flags-byte value (<c>0xFF</c>) marking a raw, uncompressed protobuf body — not a dictionary ID.</summary>
    public const int DictIdUncompressed = 0xFF;

    private static byte[]? _nonAircraftDict;
    private static byte[]? _aircraftDict;

    private static readonly string ResourceDir = Path.Combine(
        AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "Resources");

    /// <summary>
    /// The non-aircraft zstd dictionary bytes (<c>dict_non_aircraft.zstd</c>),
    /// loaded from the resource directory on first access and cached thereafter.
    /// </summary>
    public static byte[] NonAircraftDict => _nonAircraftDict ??=
        File.ReadAllBytes(Path.Combine(ResourceDir, "dict_non_aircraft.zstd"));

    /// <summary>
    /// The aircraft zstd dictionary bytes (<c>dict_aircraft.zstd</c>), loaded
    /// from the resource directory on first access and cached thereafter.
    /// </summary>
    public static byte[] AircraftDict => _aircraftDict ??=
        File.ReadAllBytes(Path.Combine(ResourceDir, "dict_aircraft.zstd"));

    /// <summary>
    /// Resolve the dictionary bytes for a given dictionary ID.
    /// </summary>
    /// <param name="dictId">A dictionary ID — <see cref="DictIdNonAircraft"/> or
    /// <see cref="DictIdAircraft"/>.</param>
    /// <returns>The dictionary bytes, or <c>null</c> for any ID that does not map
    /// to a shipped dictionary (including <see cref="DictIdUncompressed"/>).</returns>
    public static byte[]? GetDictionary(int dictId) => dictId switch
    {
        DictIdNonAircraft => NonAircraftDict,
        DictIdAircraft => AircraftDict,
        _ => null,
    };

    /// <summary>
    /// Choose the dictionary ID for a packet based on whether its CoT type
    /// classifies as aircraft.
    /// </summary>
    /// <remarks>
    /// Prefers the numeric CoT type enum ID when set; otherwise falls back to the
    /// raw CoT type string (used for unknown types carried as
    /// <c>cot_type_str</c>). Aircraft types select <see cref="DictIdAircraft"/>;
    /// everything else selects <see cref="DictIdNonAircraft"/>.
    /// </remarks>
    /// <param name="cotTypeId">The numeric <c>CotType</c> enum ID, or 0 if unknown.</param>
    /// <param name="cotTypeStr">The raw CoT type string, consulted only when
    /// <paramref name="cotTypeId"/> is 0. May be <c>null</c>.</param>
    /// <returns><see cref="DictIdAircraft"/> for aircraft types, otherwise
    /// <see cref="DictIdNonAircraft"/>.</returns>
    public static int SelectDictId(int cotTypeId, string? cotTypeStr = null)
    {
        if (cotTypeId != 0)
            return CotTypeMapper.IsAircraft(cotTypeId) ? DictIdAircraft : DictIdNonAircraft;
        if (cotTypeStr != null && CotTypeMapper.IsAircraftString(cotTypeStr))
            return DictIdAircraft;
        return DictIdNonAircraft;
    }
}
