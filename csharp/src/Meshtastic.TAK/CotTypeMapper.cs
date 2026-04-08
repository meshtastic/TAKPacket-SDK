namespace Meshtastic.TAK;

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

    public static int TypeToEnum(string s) => StringToType.GetValueOrDefault(s, 0);
    public static string? TypeToString(int id) => TypeToStr.GetValueOrDefault(id);
    public static int HowToEnum(string s) => StringToHow.GetValueOrDefault(s, 0);
    public static string? HowToString(int id) => HowToStr.GetValueOrDefault(id);

    public static bool IsAircraft(int id)
    {
        var s = TypeToString(id);
        return s != null && IsAircraftString(s);
    }

    public static bool IsAircraftString(string s)
    {
        var atoms = s.Split('-');
        return atoms.Length >= 3 && atoms[2] == "A";
    }
}
