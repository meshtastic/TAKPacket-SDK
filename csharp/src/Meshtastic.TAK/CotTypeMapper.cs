namespace Meshtastic.TAK;

/// <summary>
/// Maps CoT type strings to/from <c>CotType</c> enum values and classifies aircraft types.
/// </summary>
/// <remarks>
/// <para><b>Forward-compatibility contract:</b> When a CoT type string is not
/// in the known mapping — either because it's new (a v2.1 peer added a type
/// the v2 receiver doesn't know) or because it's legitimately niche —
/// <see cref="TypeToEnum(string)"/> returns <c>COTTYPE_OTHER</c> (0) and the
/// caller populates <c>cot_type_str</c> (field 23) with the full original
/// string. On the wire, the combination <c>cot_type_id = 0</c> +
/// <c>cot_type_str = "…"</c> is the canonical way to carry unknown types
/// without losing information: the reconstructed CoT XML uses
/// <c>cot_type_str</c> directly, so <c>&lt;event type="…"&gt;</c> comes back
/// byte-identical regardless of whether the receiver's enum knows the value.</para>
/// <para>Receivers that want to detect the downgrade should check
/// <c>cot_type_id == 0 &amp;&amp; !string.IsNullOrEmpty(cot_type_str)</c>.</para>
/// </remarks>
public static class CotTypeMapper
{
    private static readonly Dictionary<string, int> StringToType = new()
    {
        ["a-f-G-U-C"] = 1, ["a-f-G-U-C-I"] = 2, ["a-n-A-C-F"] = 3, ["a-n-A-C-H"] = 4,
        ["a-n-A-C"] = 5, ["a-f-A-M-H"] = 6, ["a-f-A-M"] = 7, ["a-f-A-M-F-F"] = 8,
        ["a-f-A-M-H-A"] = 9, ["a-f-A-M-H-U-M"] = 10, ["a-h-A-M-F-F"] = 11, ["a-h-A-M-H-A"] = 12,
        ["a-u-A-C"] = 13, ["t-x-d-d"] = 14, ["a-f-G-E-S-E"] = 15, ["a-f-G-E-V-C"] = 16,
        ["a-f-S"] = 17, ["a-f-A-M-F"] = 18, ["a-f-A-M-F-C-H"] = 19, ["a-f-A-M-F-U-L"] = 20,
        ["a-f-A-M-F-L"] = 21, ["a-f-A-M-F-P"] = 22, ["a-f-A-C-H"] = 23, ["a-n-A-M-F-Q"] = 24,
        ["b-t-f"] = 25, ["b-r-f-h-c"] = 26, ["b-a-o-pan"] = 27, ["b-a-o-opn"] = 28,
        ["b-a-o-can"] = 29, ["b-a-o-tbl"] = 30, ["b-a-g"] = 31, ["a-f-G"] = 32,
        ["a-f-G-U"] = 33, ["a-h-G"] = 34, ["a-u-G"] = 35, ["a-n-G"] = 36,
        ["b-m-r"] = 37, ["b-m-p-w"] = 38, ["b-m-p-s-p-i"] = 39, ["u-d-f"] = 40,
        ["u-d-r"] = 41, ["u-d-c-c"] = 42, ["u-rb-a"] = 43, ["a-h-A"] = 44,
        ["a-u-A"] = 45, ["a-f-A-M-H-Q"] = 46,
        ["a-f-A-C-F"] = 47, ["a-f-A-C"] = 48, ["a-f-A-C-L"] = 49, ["a-f-A"] = 50,
        ["a-f-A-M-H-C"] = 51, ["a-n-A-M-F-F"] = 52, ["a-u-A-C-F"] = 53,
        ["a-f-G-U-C-F-T-A"] = 54, ["a-f-G-U-C-V-S"] = 55, ["a-f-G-U-C-R-X"] = 56,
        ["a-f-G-U-C-I-Z"] = 57, ["a-f-G-U-C-E-C-W"] = 58, ["a-f-G-U-C-I-L"] = 59,
        ["a-f-G-U-C-R-O"] = 60, ["a-f-G-U-C-R-V"] = 61, ["a-f-G-U-H"] = 62,
        ["a-f-G-U-U-M-S-E"] = 63, ["a-f-G-U-S-M-C"] = 64, ["a-f-G-E-S"] = 65,
        ["a-f-G-E"] = 66, ["a-f-G-E-V-C-U"] = 67, ["a-f-G-E-V-C-ps"] = 68,
        ["a-u-G-E-V"] = 69, ["a-f-S-N-N-R"] = 70, ["a-f-F-B"] = 71,
        ["b-m-p-s-p-loc"] = 72, ["b-i-v"] = 73, ["b-f-t-r"] = 74, ["b-f-t-a"] = 75,
        // Typed geometry additions (v2 protocol extension)
        ["u-d-f-m"] = 76, ["u-d-p"] = 77, ["b-m-p-s-m"] = 78, ["b-m-p-c"] = 79,
        ["u-r-b-c-c"] = 80, ["u-r-b-bullseye"] = 81,
        // Expanded coverage (values 82-124)
        ["a-f-G-E-V-A"] = 82, ["a-n-A"] = 83,
        ["a-u-G-U-C-F"] = 84, ["a-n-G-U-C-F"] = 85, ["a-h-G-U-C-F"] = 86, ["a-f-G-U-C-F"] = 87,
        ["a-u-G-I"] = 88, ["a-n-G-I"] = 89, ["a-h-G-I"] = 90, ["a-f-G-I"] = 91,
        ["a-u-G-E-X-M"] = 92, ["a-n-G-E-X-M"] = 93, ["a-h-G-E-X-M"] = 94, ["a-f-G-E-X-M"] = 95,
        ["a-u-S"] = 96, ["a-n-S"] = 97, ["a-h-S"] = 98,
        ["a-u-G-U-C-I-d"] = 99, ["a-n-G-U-C-I-d"] = 100, ["a-h-G-U-C-I-d"] = 101, ["a-f-G-U-C-I-d"] = 102,
        ["a-u-G-E-V-A-T"] = 103, ["a-n-G-E-V-A-T"] = 104, ["a-h-G-E-V-A-T"] = 105, ["a-f-G-E-V-A-T"] = 106,
        ["a-u-G-U-C-I"] = 107, ["a-n-G-U-C-I"] = 108, ["a-h-G-U-C-I"] = 109,
        ["a-n-G-E-V"] = 110, ["a-h-G-E-V"] = 111, ["a-f-G-E-V"] = 112,
        ["b-m-p-w-GOTO"] = 113, ["b-m-p-c-ip"] = 114, ["b-m-p-c-cp"] = 115, ["b-m-p-s-p-op"] = 116,
        ["u-d-v"] = 117, ["u-d-v-m"] = 118, ["u-d-c-e"] = 119,
        ["b-i-x-i"] = 120, ["b-t-f-d"] = 121, ["b-t-f-r"] = 122, ["b-a-o-c"] = 123, ["t-s"] = 124,
        // TAKTALK plugin shapes. "y-" literally has a trailing dash and no
        // second atom — that's the wire format ATAK + TAKTALK emit for room
        // broadcasts. Not a typo.
        ["m-t-t"] = 125, ["y-"] = 126,
    };

    private static readonly Dictionary<int, string> TypeToStr =
        StringToType.ToDictionary(kv => kv.Value, kv => kv.Key);

    private static readonly Dictionary<string, int> StringToHow = new()
    {
        ["h-e"] = 1, ["m-g"] = 2, ["h-g-i-g-o"] = 3, ["m-r"] = 4,
        ["m-f"] = 5, ["m-p"] = 6, ["m-s"] = 7,
    };

    private static readonly Dictionary<int, string> HowToStr =
        StringToHow.ToDictionary(kv => kv.Value, kv => kv.Key);

    /// <summary>
    /// Map a CoT type string (e.g. <c>"a-f-G-U-C"</c>) to its <c>CotType</c> enum ID.
    /// </summary>
    /// <param name="s">The CoT type string from the <c>&lt;event type="…"&gt;</c> attribute.</param>
    /// <returns>The matching enum ID, or <c>0</c> (<c>COTTYPE_OTHER</c>) for any
    /// unknown type. When 0 is returned, the caller is expected to preserve the
    /// original string in <c>cot_type_str</c> (field 23) so the type survives
    /// the round trip — see the class-level forward-compatibility contract.</returns>
    public static int TypeToEnum(string s) => StringToType.GetValueOrDefault(s, 0);

    /// <summary>
    /// Map a <c>CotType</c> enum ID back to its canonical CoT type string.
    /// </summary>
    /// <param name="id">A <c>CotType</c> enum ID.</param>
    /// <returns>The CoT type string, or <c>null</c> if the ID is not a known type
    /// (including <c>0</c>/<c>COTTYPE_OTHER</c>, whose string lives in
    /// <c>cot_type_str</c> instead).</returns>
    public static string? TypeToString(int id) => TypeToStr.GetValueOrDefault(id);

    /// <summary>
    /// Map a CoT <c>how</c> string (e.g. <c>"m-g"</c>, <c>"h-e"</c>) to its
    /// <c>CotHow</c> enum ID.
    /// </summary>
    /// <param name="s">The CoT <c>how</c> string from the <c>&lt;event how="…"&gt;</c> attribute.</param>
    /// <returns>The matching enum ID, or <c>0</c> for an unknown/absent value.</returns>
    public static int HowToEnum(string s) => StringToHow.GetValueOrDefault(s, 0);

    /// <summary>
    /// Map a <c>CotHow</c> enum ID back to its CoT <c>how</c> string.
    /// </summary>
    /// <param name="id">A <c>CotHow</c> enum ID.</param>
    /// <returns>The <c>how</c> string, or <c>null</c> if the ID is unknown.</returns>
    public static string? HowToString(int id) => HowToStr.GetValueOrDefault(id);

    /// <summary>
    /// Determine whether a CoT type enum ID classifies as an aircraft type.
    /// </summary>
    /// <remarks>Resolves the ID to its CoT type string and applies the same
    /// 3rd-atom rule as <see cref="IsAircraftString(string)"/>. Used to select the
    /// aircraft compression dictionary (see <see cref="DictionaryProvider.SelectDictId"/>).</remarks>
    /// <param name="id">A <c>CotType</c> enum ID.</param>
    /// <returns><c>true</c> if the type is an aircraft type; otherwise <c>false</c>
    /// (including for unknown IDs).</returns>
    public static bool IsAircraft(int id)
    {
        var s = TypeToString(id);
        return s != null && IsAircraftString(s);
    }

    /// <summary>
    /// Determine whether a CoT type string classifies as an aircraft type.
    /// </summary>
    /// <remarks>The rule is structural: the 3rd dash-delimited atom of the type
    /// string is <c>"A"</c> (e.g. <c>a-n-A-C-F</c>). This drives selection of the
    /// aircraft compression dictionary.</remarks>
    /// <param name="s">A CoT type string.</param>
    /// <returns><c>true</c> if the 3rd atom is <c>"A"</c>; otherwise <c>false</c>.</returns>
    public static bool IsAircraftString(string s)
    {
        var atoms = s.Split('-');
        return atoms.Length >= 3 && atoms[2] == "A";
    }
}
