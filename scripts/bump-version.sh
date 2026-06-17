#!/usr/bin/env bash
#
# bump-version.sh — stamp ONE version into all five release-coupled sources.
#
# The repo ships five package coordinates that MUST agree, or the Release
# workflow's "Verify version sources agree" step hard-fails before publishing:
#
#   VERSION                                      → workflow tag, release title, artifact names
#   kotlin/gradle.properties:VERSION_NAME        → Maven Central + JitPack coordinate
#   python/pyproject.toml:version                → PyPI wheel/sdist
#   typescript/package.json:version              → npm tarball
#   csharp/.../Meshtastic.TAK.csproj:<Version>   → NuGet package
#
# JitPack and source builds read the COMMITTED gradle.properties (not any
# CI-injected -P flag), so the files themselves must carry the version. Edit
# one place — here — instead of five by hand.
#
# Usage:  scripts/bump-version.sh <version>     e.g. scripts/bump-version.sh 0.7.0
#
set -euo pipefail

VERSION="${1:-}"
VERSION="${VERSION#v}" # tolerate a leading "v"; VERSION file carries no prefix

if [[ -z "$VERSION" ]]; then
  echo "usage: $0 <version>   (e.g. $0 0.7.0)" >&2
  exit 2
fi

# Semver: N.N.N with an optional -prerelease / +build suffix.
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
  echo "error: '$VERSION' is not a valid semantic version (expected e.g. 0.7.0)" >&2
  exit 2
fi

# Resolve the repo root from this script's location so it works from any cwd.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE="kotlin/gradle.properties"
PYPROJECT="python/pyproject.toml"
PACKAGE_JSON="typescript/package.json"
CSPROJ="csharp/src/Meshtastic.TAK/Meshtastic.TAK.csproj"

# Portable in-place sed (GNU takes `-i`, BSD/macOS needs `-i ''`).
sedi() {
  if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi
}

# 1) VERSION — whole file, preserve the trailing newline.
printf '%s\n' "$VERSION" > VERSION

# 2) kotlin/gradle.properties — the VERSION_NAME line.
sedi -E "s/^VERSION_NAME=.*/VERSION_NAME=${VERSION}/" "$GRADLE"

# 3) python/pyproject.toml — the [project] version (anchored at column 0 so it
#    never touches requires-python or dependency version pins).
sedi -E "s/^(version[[:space:]]*=[[:space:]]*\")[^\"]*(\".*)$/\1${VERSION}\2/" "$PYPROJECT"

# 4) typescript/package.json — the top-level "version" (2-space indent => the
#    package's own version, not a nested dependency field).
sedi -E "s/^(  \"version\"[[:space:]]*:[[:space:]]*\")[^\"]*(\".*)$/\1${VERSION}\2/" "$PACKAGE_JSON"

# 5) csharp csproj — <Version>…</Version> (does not match <FileVersion> etc.).
sedi -E "s|<Version>[^<]*</Version>|<Version>${VERSION}</Version>|" "$CSPROJ"

# ── Verify, using the SAME extraction the Release workflow uses, so a green run
#    here guarantees a green "Verify version sources agree" step. ──────────────
V_FILE="$(tr -d '[:space:]' < VERSION)"
V_GRADLE="$(grep -E '^VERSION_NAME=' "$GRADLE" | cut -d'=' -f2 | tr -d '[:space:]')"
V_PYTHON="$(grep -E '^version[[:space:]]*=' "$PYPROJECT" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
V_NPM="$(grep -E '^[[:space:]]*"version"[[:space:]]*:' "$PACKAGE_JSON" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
V_CSHARP="$(grep -oE '<Version>[^<]+</Version>' "$CSPROJ" | head -1 | sed -E 's|<Version>([^<]+)</Version>|\1|')"

printf '\nVersion sources after bump:\n'
printf '  %-32s %s\n' "VERSION"                  "$V_FILE"
printf '  %-32s %s\n' "kotlin/gradle.properties" "$V_GRADLE"
printf '  %-32s %s\n' "python/pyproject.toml"    "$V_PYTHON"
printf '  %-32s %s\n' "typescript/package.json"  "$V_NPM"
printf '  %-32s %s\n' "csharp .csproj"           "$V_CSHARP"

FAIL=0
for pair in "VERSION:$V_FILE" "$GRADLE:$V_GRADLE" "$PYPROJECT:$V_PYTHON" \
            "$PACKAGE_JSON:$V_NPM" "$CSPROJ:$V_CSHARP"; do
  if [[ "${pair##*:}" != "$VERSION" ]]; then
    echo "error: ${pair%%:*} did not update cleanly (got '${pair##*:}', wanted '$VERSION')" >&2
    FAIL=1
  fi
done
if [[ $FAIL -ne 0 ]]; then
  echo "error: bump incomplete — inspect the files above before committing." >&2
  exit 1
fi

printf '\n✓ All five sources at %s. Commit, then dispatch the Release workflow.\n' "$VERSION"
