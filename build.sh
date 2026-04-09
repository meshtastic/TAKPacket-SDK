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

check_runtime() {
    local name="$1" cmd="$2"
    if ! command -v "$cmd" &>/dev/null; then
        warn "$name: '$cmd' not found. Install it or add to PATH."
        warn "  Kotlin: brew install openjdk@17 (then export JAVA_HOME)"
        warn "  Swift:  included with Xcode"
        warn "  Python: brew install python3 && pip3 install pytest protobuf zstandard"
        warn "  Node:   brew install node"
        warn "  .NET:   brew install dotnet"
        return 1
    fi
    return 0
}

find_java() {
    # Prefer JDK 17-21 (what Kotlin/Gradle support), then fall back to any JDK
    for jdk in \
        /opt/homebrew/opt/openjdk@17/bin \
        /opt/homebrew/opt/openjdk@21/bin \
        /opt/homebrew/opt/openjdk@20/bin \
        /opt/homebrew/opt/openjdk@19/bin \
        /opt/homebrew/opt/openjdk@18/bin \
        /usr/local/opt/openjdk@17/bin \
        /usr/local/opt/openjdk@21/bin; do
        if [ -x "$jdk/java" ]; then
            export JAVA_HOME="${jdk%/bin}"
            export PATH="$jdk:$PATH"
            log "Found Java at $jdk"
            return 0
        fi
    done
    # Check SDKMAN
    if [ -d "$HOME/.sdkman/candidates/java/current/bin" ]; then
        export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi
    # Fall back to whatever java is on PATH (may be too new)
    if java -version &>/dev/null 2>&1; then return 0; fi
    return 1
}

test_kotlin() {
    log "Testing Kotlin..."
    if ! find_java; then
        fail "Kotlin: Java not found. Install JDK 17+ (brew install openjdk) and ensure java is on PATH."
        return 1
    fi
    cd "$SCRIPT_DIR/kotlin"
    local rc=0
    if [ -x ./gradlew ]; then
        ./gradlew jvmTest --quiet 2>&1 || rc=$?
    else
        gradle jvmTest --quiet 2>&1 || rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        fail "Kotlin: tests failed (exit code $rc)"
        return 1
    fi
    local count
    count=$(grep -c 'testcase' build/test-results/jvmTest/*.xml 2>/dev/null | awk -F: '{sum+=$2} END{print sum}')
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

    # Swift has two coexisting test frameworks in this target:
    #
    #   * XCTest reports its count as `Executed N tests, with M failures`
    #     on the final `All tests` summary line. Each `func test…()` is one
    #     test; parameterized tests are not supported natively.
    #
    #   * Swift Testing (`import Testing`) reports per-function summaries
    #     as `Test "…" with N test cases passed` when the function is
    #     declared with `@Test(arguments: …)`. The Swift Testing top-level
    #     `Test run with N tests` line counts @Test FUNCTIONS, not cases,
    #     so a parameterized test over 18 fixtures still shows as "1 test"
    #     in that line — misleading when comparing against the other
    #     platforms which all expand parameterized cases in their counts.
    #
    # We report the sum of both: XCTest executed count + Swift Testing
    # parameterized cases + any non-parameterized Swift Testing functions.
    local xct_count st_cases st_total st_param_funcs st_nonparam total
    xct_count=$(echo "$output" | grep "Executed.*tests" | tail -1 | grep -oE 'Executed [0-9]+' | grep -oE '[0-9]+')
    xct_count=${xct_count:-0}
    # Sum all "with N test cases passed" lines (one per parameterized @Test).
    st_cases=$(echo "$output" | grep -oE 'with [0-9]+ test cases passed' | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}')
    # Swift Testing's own top-level total counts @Test functions; parameterized
    # ones collapse to 1 and non-parameterized stay as 1. Subtract the
    # parameterized-function count to get the non-parameterized remainder.
    st_total=$(echo "$output" | grep -oE 'Test run with [0-9]+ tests? in' | tail -1 | grep -oE '[0-9]+' | head -1)
    st_total=${st_total:-0}
    st_param_funcs=$(echo "$output" | grep -cE 'with [0-9]+ test cases passed' || true)
    st_nonparam=$((st_total - st_param_funcs))
    [ $st_nonparam -lt 0 ] && st_nonparam=0
    total=$((xct_count + st_cases + st_nonparam))
    pass "Swift: $total tests"
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
    if [ -x ./gradlew ]; then
        ./gradlew jvmJar --quiet
    else
        gradle jvmJar --quiet
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
