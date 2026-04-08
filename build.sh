#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_FILE="$SCRIPT_DIR/VERSION"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${CYAN}==>${NC} $*"; }
pass() { echo -e "${GREEN}PASS${NC} $*"; }
fail() { echo -e "${RED}FAIL${NC} $*"; }
warn() { echo -e "${YELLOW}WARN${NC} $*"; }

read_version() {
    cat "$VERSION_FILE" | tr -d '[:space:]'
}

# Find a python3 that has the needed modules
find_python() {
    local candidates=(
        python3 python3.12 python3.11 python3.10 python3.9
        /Library/Developer/CommandLineTools/usr/bin/python3
        /usr/bin/python3
    )
    for py in "${candidates[@]}"; do
        if command -v "$py" &>/dev/null && "$py" -c "import pytest" &>/dev/null 2>&1; then
            echo "$py"; return
        fi
    done
    echo "python3"
}

# ── Test commands ────────────────────────────────────────────────────────────

test_kotlin() {
    log "Testing Kotlin..."
    cd "$SCRIPT_DIR/kotlin"
    local rc=0
    if [ -x ./gradlew ]; then
        ./gradlew test --quiet 2>&1 || rc=$?
    else
        gradle test --quiet 2>&1 || rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        fail "Kotlin: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(grep -c 'testcase' build/test-results/test/*.xml 2>/dev/null | awk -F: '{sum+=$2} END{print sum}')
    pass "Kotlin: $count tests"
}

test_swift() {
    log "Testing Swift..."
    cd "$SCRIPT_DIR/swift"
    local output rc=0
    output=$(DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun swift test 2>&1) || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "$output" | tail -5
        fail "Swift: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(echo "$output" | grep "Executed.*tests" | tail -1 | grep -oE 'Executed [0-9]+' | grep -oE '[0-9]+')
    pass "Swift: $count tests"
}

test_python() {
    log "Testing Python..."
    cd "$SCRIPT_DIR/python"
    local py rc=0
    py=$(find_python)
    local output
    output=$($py -m pytest tests/ -q 2>&1) || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "$output" | tail -10
        fail "Python: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(echo "$output" | grep -oE '^[0-9]+ passed' | grep -oE '[0-9]+')
    pass "Python: $count tests"
}

test_typescript() {
    log "Testing TypeScript..."
    cd "$SCRIPT_DIR/typescript"
    local output rc=0
    output=$(npx vitest run 2>&1) || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "$output" | tail -10
        fail "TypeScript: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(echo "$output" | grep "Tests" | grep -oE '[0-9]+ passed' | grep -oE '[0-9]+')
    pass "TypeScript: $count tests"
}

test_csharp() {
    log "Testing C#..."
    cd "$SCRIPT_DIR/csharp"
    local output rc=0
    output=$(dotnet test --verbosity quiet 2>&1) || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "$output" | tail -10
        fail "C#: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(echo "$output" | grep -oE 'Passed:\s+[0-9]+' | grep -oE '[0-9]+')
    pass "C#: $count tests"
}

test_all() {
    log "Running all platform tests..."
    echo ""

    local failed=0
    local results=()

    for platform in kotlin swift python typescript csharp; do
        if "test_$platform"; then
            results+=("$platform: PASS")
        else
            results+=("$platform: FAIL")
            failed=1
        fi
        echo ""
    done

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "Results (v$(read_version)):"
    for r in "${results[@]}"; do
        if [[ "$r" == *FAIL* ]]; then
            fail "  $r"
        else
            pass "  $r"
        fi
    done
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if [ "$failed" -eq 1 ]; then
        fail "Some platforms failed!"
        exit 1
    else
        pass "All platforms passed!"
    fi
}

# ── Version commands ─────────────────────────────────────────────────────────

stamp_version() {
    local ver
    ver=$(read_version)
    log "Stamping version $ver into all platform configs..."

    # Kotlin - build.gradle.kts
    sed -i '' "s/version = \"[^\"]*\"/version = \"$ver\"/" "$SCRIPT_DIR/kotlin/build.gradle.kts"
    echo "  kotlin/build.gradle.kts"

    # Python - pyproject.toml
    sed -i '' "s/^version = \"[^\"]*\"/version = \"$ver\"/" "$SCRIPT_DIR/python/pyproject.toml"
    echo "  python/pyproject.toml"

    # TypeScript - package.json
    cd "$SCRIPT_DIR/typescript"
    node -e "
        const fs = require('fs');
        const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'));
        pkg.version = '$ver';
        fs.writeFileSync('package.json', JSON.stringify(pkg, null, 2) + '\n');
    "
    echo "  typescript/package.json"

    # C# - Meshtastic.TAK.csproj
    sed -i '' "s/<Version>[^<]*<\/Version>/<Version>$ver<\/Version>/" \
        "$SCRIPT_DIR/csharp/src/Meshtastic.TAK/Meshtastic.TAK.csproj"
    echo "  csharp/src/Meshtastic.TAK/Meshtastic.TAK.csproj"

    # Swift uses git tags, not Package.swift version
    echo "  swift: uses git tags (no file to stamp)"

    pass "All configs stamped to v$ver"
}

bump_version() {
    local part="${1:-}"
    local ver
    ver=$(read_version)

    IFS='.' read -r major minor patch <<< "$ver"

    case "$part" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
        *)
            echo "Usage: $0 version-bump <major|minor|patch>"
            exit 1
            ;;
    esac

    local new_ver="$major.$minor.$patch"
    echo "$new_ver" > "$VERSION_FILE"
    log "Bumped version: $ver -> $new_ver"

    stamp_version
}

# ── Build artifacts command ──────────────────────────────────────────────────

build_artifacts() {
    local ver
    ver=$(read_version)
    local dist="$SCRIPT_DIR/dist"
    rm -rf "$dist"
    mkdir -p "$dist"

    log "Building artifacts for v$ver..."

    # Kotlin JAR
    log "Building Kotlin JAR..."
    cd "$SCRIPT_DIR/kotlin"
    if command -v ./gradlew &>/dev/null; then
        ./gradlew jar --quiet
    else
        gradle jar --quiet
    fi
    cp build/libs/*.jar "$dist/meshtastic-tak-${ver}.jar"
    pass "Kotlin: meshtastic-tak-${ver}.jar"

    # Swift source zip
    log "Building Swift source zip..."
    cd "$SCRIPT_DIR"
    zip -rq "$dist/meshtastic-tak-swift-${ver}.zip" swift/ \
        -x "swift/.build/*" "swift/.swiftpm/*"
    pass "Swift: meshtastic-tak-swift-${ver}.zip"

    # Python wheel + sdist
    log "Building Python wheel..."
    cd "$SCRIPT_DIR/python"
    local py
    py=$(find_python)
    $py -m build --outdir "$dist" 2>&1 | tail -3
    pass "Python: wheel + sdist"

    # TypeScript npm tarball
    log "Building TypeScript npm tarball..."
    cd "$SCRIPT_DIR/typescript"
    npm ci --silent 2>/dev/null || npm install --silent
    npm run build --silent
    npm pack --pack-destination "$dist" 2>&1 | tail -1
    pass "TypeScript: npm tarball"

    # C# NuGet package
    log "Building C# NuGet package..."
    cd "$SCRIPT_DIR/csharp"
    dotnet pack src/Meshtastic.TAK -c Release -o "$dist" --verbosity quiet 2>&1
    pass "C#: NuGet package"

    echo ""
    log "All artifacts in $dist/:"
    ls -lh "$dist/"
}

# ── Clean command ────────────────────────────────────────────────────────────

clean() {
    log "Cleaning all build artifacts..."

    rm -rf "$SCRIPT_DIR/kotlin/build" "$SCRIPT_DIR/kotlin/.gradle"
    echo "  kotlin: cleaned"

    rm -rf "$SCRIPT_DIR/swift/.build"
    echo "  swift: cleaned"

    rm -rf "$SCRIPT_DIR/python/__pycache__" "$SCRIPT_DIR/python/src/meshtastic_tak/__pycache__" \
           "$SCRIPT_DIR/python/tests/__pycache__" "$SCRIPT_DIR/python/.pytest_cache" \
           "$SCRIPT_DIR/python/build" "$SCRIPT_DIR/python/dist" "$SCRIPT_DIR/python/*.egg-info"
    echo "  python: cleaned"

    rm -rf "$SCRIPT_DIR/typescript/dist" "$SCRIPT_DIR/typescript/node_modules"
    echo "  typescript: cleaned"

    rm -rf "$SCRIPT_DIR/csharp/src/Meshtastic.TAK/bin" "$SCRIPT_DIR/csharp/src/Meshtastic.TAK/obj" \
           "$SCRIPT_DIR/csharp/tests/Meshtastic.TAK.Tests/bin" "$SCRIPT_DIR/csharp/tests/Meshtastic.TAK.Tests/obj"
    echo "  csharp: cleaned"

    pass "All platforms cleaned"
}

# ── Main ─────────────────────────────────────────────────────────────────────

case "${1:-help}" in
    test)           test_all ;;
    test-kotlin)    test_kotlin ;;
    test-swift)     test_swift ;;
    test-python)    test_python ;;
    test-typescript) test_typescript ;;
    test-csharp)    test_csharp ;;
    version)        echo "$(read_version)" ;;
    version-bump)   bump_version "${2:-}" ;;
    stamp)          stamp_version ;;
    build-artifacts) build_artifacts ;;
    clean)          clean ;;
    help|*)
        echo "TAKPacket-SDK Build Script v$(read_version)"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  test              Run all 5 platform test suites"
        echo "  test-kotlin       Run Kotlin tests only"
        echo "  test-swift        Run Swift tests only"
        echo "  test-python       Run Python tests only"
        echo "  test-typescript   Run TypeScript tests only"
        echo "  test-csharp       Run C# tests only"
        echo "  version           Print current version"
        echo "  version-bump      Bump version (major|minor|patch) and stamp all configs"
        echo "  stamp             Sync VERSION file into all platform configs"
        echo "  build-artifacts   Build all platform library artifacts into dist/"
        echo "  clean             Remove all build artifacts"
        ;;
esac
