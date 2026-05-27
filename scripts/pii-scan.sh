#!/usr/bin/env bash
# pii-scan.sh — flag candidate PII in staged or working-tree files.
#
# Two modes:
#   ./scripts/pii-scan.sh                       # scan entire working tree
#   ./scripts/pii-scan.sh --staged              # scan only files staged for commit
#
# Install as a pre-commit hook:
#   ln -s ../../scripts/pii-scan.sh .git/hooks/pre-commit
#
# Exit code: 0 = clean, 1 = PII candidate found (review before commit).
#
# What it flags:
#   - GPS coords with 5+ decimal places that aren't on the known-synthetic whitelist
#   - ANDROID-[16+ hex chars] device IDs (allows ANDROID-0+\d+ test placeholders)
#   - RFC 1918 private network IPs (allows 192.0.2.x / 198.51.100.x / 203.0.113.x docs ranges)
#
# What it does NOT flag (intentional false negatives):
#   - High-entropy UUIDs (high-entropy, not identifying alone)
#   - Real names embedded in callsign strings (callsigns vary; impossible to regex reliably)
#   - Coordinates with <5 decimals (too imprecise to be identifying)

set -euo pipefail

STAGED_ONLY=0
if [[ "${1:-}" == "--staged" ]]; then
    STAGED_ONLY=1
fi

# Pattern: matches coords with 5+ decimal places, ANDROID device IDs,
# and RFC 1918 IPs in one pass.
PII_PATTERN='\b[0-9]{1,3}\.[0-9]{5,}\b|ANDROID-[0-9a-f]{12,}|\b(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]+\.[0-9]+\b'

# Whitelist (allowlist): things that match PII_PATTERN but are KNOWN safe.
#   ANDROID-0+\d+              — sequential test placeholders (ANDROID-0000000000000001, ...02, ...)
#   ANDROID-589520ccfcd20f01   — specific ATAK-CIV upstream sample device ID (public)
#   192\.0\.2\.|198\.51\.100\.|203\.0\.113\.  — RFC 5737 docs IPs
#   \b38\.[0-9]+               — DC-area lat (Washington Monument neighborhood, public landmarks); covers high-precision ATAK-CIV upstream samples
#   \b77\.0[0-9]+              — DC-area lon (matches both -77.0x and 77.0x); public landmarks
#   \b33\.12[0-9]+             — established synthetic NM coords used in Meshtastic-Android fixtures
#   \b107\.25[0-9]+            — same NM range
#   \b12\.0{4,5}\b             — established synthetic Bay-of-Bengal placeholders (low-precision)
#   \b9[01]\.0{4,5}\b          — same Bay-of-Bengal range
#
# Numbers > 200 with 5+ decimals are very unlikely to be lat/lon (lat is
# -90..90, lon is -180..180); they're almost always ellipse radii, bearings,
# or other non-coord measurements. Filter those out too.
ALLOW_PATTERN='ANDROID-0+[0-9]+|ANDROID-589520ccfcd20f01|\b192\.0\.2\.|\b198\.51\.100\.|\b203\.0\.113\.|\b38\.[0-9]+|\b77\.0[0-9]+|\b33\.1[0-9]+|\b107\.2[0-9]+|\b12\.0{4,5}\b|\b9[01]\.0{4,5}\b|\b16\.1[0-9]+|\b10\.00[0-9]+|\b95\.00[0-9]+|\b15\.181230\b|\b33\.30720360300985\b|\b45\.5965567[0-9]+\b|\b[2-9][0-9]{2,}\.[0-9]+\b|\b1[0-9]{3,}\.[0-9]+\b'

# Files to scan: source + docs + fixtures + manifests.
INCLUDE_GLOBS=(
    "--include=*.xml" "--include=*.cot" "--include=*.md"
    "--include=*.kt" "--include=*.kts" "--include=*.swift"
    "--include=*.cs" "--include=*.py" "--include=*.ts" "--include=*.js"
    "--include=*.json"
)

# Always-excluded paths (build artifacts, vendor dirs, the corpus dir in ZTSD).
EXCLUDE_DIRS=(
    "--exclude-dir=.git" "--exclude-dir=node_modules" "--exclude-dir=build"
    "--exclude-dir=.build" "--exclude-dir=bin" "--exclude-dir=obj"
    "--exclude-dir=venv" "--exclude-dir=.venv" "--exclude-dir=DerivedData"
    "--exclude-dir=corpus" "--exclude-dir=Pods" "--exclude-dir=site-packages"
)

if [[ "$STAGED_ONLY" -eq 1 ]]; then
    # Pre-commit mode: only scan files actually staged for commit.
    FILES=$(git diff --cached --name-only --diff-filter=AM \
            | grep -E '\.(xml|cot|md|kt|kts|swift|cs|py|ts|js|json)$' || true)
    if [[ -z "$FILES" ]]; then
        echo "pii-scan: no relevant staged files."
        exit 0
    fi
    HITS=$(echo "$FILES" | xargs -I{} grep -EHno "$PII_PATTERN" "{}" 2>/dev/null \
           | grep -Ev "$ALLOW_PATTERN" || true)
else
    HITS=$(grep -rEHno "$PII_PATTERN" "${EXCLUDE_DIRS[@]}" "${INCLUDE_GLOBS[@]}" . 2>/dev/null \
           | grep -Ev "$ALLOW_PATTERN" || true)
fi

if [[ -n "$HITS" ]]; then
    echo "❌ pii-scan found candidate PII (review before commit):" >&2
    echo "" >&2
    echo "$HITS" >&2
    echo "" >&2
    echo "Sanitization guide: .github/copilot-instructions.md → \"PII and test-fixture sanitization\"" >&2
    echo "" >&2
    echo "If a flagged match is actually safe (e.g. a new public landmark coord)," >&2
    echo "extend ALLOW_PATTERN in scripts/pii-scan.sh." >&2
    exit 1
fi

echo "✅ pii-scan clean."
exit 0
