#!/usr/bin/env bash
set -euo pipefail

# Regenerate protobuf code for every binding that checks generated code in.
#
# Toolchain versions are pinned below and MUST match what CI uses, because the
# generated files carry the generator version and a mismatch rewrites that
# marker across the tree even when the schema has not moved. If you do not have
# these to hand, do not improvise: run the "Update protobufs" workflow and take
# its pull request.
#
#   protoc            PROTOC_VERSION, below
#   protoc-gen-swift  SWIFT_PROTOBUF_VERSION, which tracks the `from:` pin in
#                     swift/Package.swift
#
# Kotlin regenerates on every gradle build and TypeScript loads the .proto at
# runtime via protobufjs, so neither appears here.

PROTOC_VERSION="${PROTOC_VERSION:-33.2}"
SWIFT_PROTOBUF_VERSION="${SWIFT_PROTOBUF_VERSION:-1.39.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
PROTO_DIR="$ROOT/protobufs"

if [ ! -f "$PROTO_DIR/meshtastic/atak.proto" ]; then
    echo "Error: protobufs submodule not found. Run: git submodule update --init"
    exit 1
fi

have() { command -v "$1" >/dev/null 2>&1; }

if ! have protoc; then
    echo "Error: protoc not on PATH. Expected ${PROTOC_VERSION}."
    exit 1
fi

ACTUAL_PROTOC="$(protoc --version | awk '{print $2}')"
if [ "$ACTUAL_PROTOC" != "$PROTOC_VERSION" ]; then
    echo "Error: protoc ${ACTUAL_PROTOC} on PATH, but this tree is generated with ${PROTOC_VERSION}."
    echo "       Generating anyway would rewrite the version marker in every file."
    echo "       Override with PROTOC_VERSION=${ACTUAL_PROTOC} only if you mean to re-baseline."
    exit 1
fi

if ! have protoc-gen-swift; then
    echo "Error: protoc-gen-swift not on PATH. Expected ${SWIFT_PROTOBUF_VERSION}."
    echo "       It ships no prebuilt Linux binary; build it from the pinned tag, or"
    echo "       use the Update protobufs workflow, which does that for you."
    exit 1
fi

# Version-checked for the same reason protoc is: the generated file records the
# generator, so a mismatch rewrites that marker with no schema change. This pin also
# has to agree with the SwiftProtobuf the generated code links against, which is the
# `from:` pin in swift/Package.swift.
ACTUAL_SWIFT_GEN="$(protoc-gen-swift --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
if [ "$ACTUAL_SWIFT_GEN" != "$SWIFT_PROTOBUF_VERSION" ]; then
    echo "Error: protoc-gen-swift ${ACTUAL_SWIFT_GEN:-unknown} on PATH, expected ${SWIFT_PROTOBUF_VERSION}."
    echo "       That pin tracks swift/Package.swift; change both together or not at all."
    # Printed because an unparseable version usually means the binary did not RUN, not
    # that it reported something odd, and the two look identical from here. A Swift
    # binary built inside a container and copied out needs the Swift runtime unless it
    # was linked with --static-swift-stdlib, and fails in the dynamic loader with no
    # version string at all.
    echo "       It reported:"
    protoc-gen-swift --version 2>&1 | sed 's/^/         /' | head -5 || true
    exit 1
fi

# Every tool is validated above, before the first mutation below. Pruning deletes
# files, so a precondition discovered late would leave the tree worse than it was.

# atak.proto AND its transitive imports, derived from the files rather than listed.
#
# Listing atak.proto alone was the original bug: it gained
# `import "meshtastic/field_metadata.proto"` upstream and this script kept generating
# one file, so the result referenced a module that was never generated. A hand-written
# list drifts the moment upstream adds an import. A blanket glob is wrong the other way:
# this submodule carries the whole Meshtastic suite, and generating admin.proto and
# friends both fails and vendors code no binding here uses.
#
# google/* imports are skipped: protoc has those built in and generates nothing for them.
declare -a PROTOS=()
collect() {
    local rel="$1"
    case "$rel" in google/*) return ;; esac
    local seen
    for seen in ${PROTOS[@]+"${PROTOS[@]}"}; do
        [ "$seen" = "$rel" ] && return
    done
    PROTOS+=("$rel")
    local imp
    while IFS= read -r imp; do
        [ -n "$imp" ] && collect "$imp"
    done < <(grep -oE '^import "[^"]+"' "$PROTO_DIR/$rel" 2>/dev/null | sed 's/^import "//; s/"$//')
}
collect "meshtastic/atak.proto"

ABS_PROTOS=()
for rel in "${PROTOS[@]}"; do ABS_PROTOS+=("$PROTO_DIR/$rel"); done

# field_metadata.proto extends FieldOptions, so descriptor.proto has to resolve.
# protoc ships the well-known types beside the binary rather than embedding them, and
# does not add that directory to the import path on its own.
WELL_KNOWN="$(cd "$(dirname "$(command -v protoc)")/../include" 2>/dev/null && pwd || true)"
if [ -z "$WELL_KNOWN" ] || [ ! -f "$WELL_KNOWN/google/protobuf/descriptor.proto" ]; then
    echo "Error: could not find protoc's include/ directory next to the binary."
    echo "       google/protobuf/descriptor.proto must resolve for field_metadata.proto."
    exit 1
fi
echo "Regenerating ${#PROTOS[@]} proto file(s) (${PROTOS[*]}) with protoc ${PROTOC_VERSION}..."

# Drop generated files whose proto is no longer in the closure. Without this an
# upstream import that goes away leaves its binding on disk, and since the workflow
# stages whole directories, the stale file ships in the package.
#
# Identified by the generator's own marker rather than by filename, because in the C#
# target generated and hand-written sources share a directory with no naming convention
# between them: Atak.cs is generated, AtakPalette.cs is not.
prune_stale() {
    local dir="$1" pattern="$2" marker="$3"
    shift 3
    local keep=" $* "
    local f base
    for f in "$dir"/$pattern; do
        [ -e "$f" ] || continue
        base="$(basename "$f")"
        case "$keep" in *" $base "*) continue ;; esac
        if [ -n "$marker" ] && ! head -5 "$f" | grep -q "$marker"; then
            continue
        fi
        echo "    removing stale $base"
        rm -f "$f"
    done
}

SWIFT_KEEP=() PY_KEEP=() CS_KEEP=()
for rel in "${PROTOS[@]}"; do
    stem="$(basename "$rel" .proto)"
    SWIFT_KEEP+=("${stem}.pb.swift")
    PY_KEEP+=("${stem}_pb2.py")
    # protoc PascalCases the stem for C#: field_metadata.proto -> FieldMetadata.cs
    CS_KEEP+=("$(echo "$stem" | awk -F_ '{for(i=1;i<=NF;i++) printf "%s%s", toupper(substr($i,1,1)), substr($i,2); print ""}').cs")
done

prune_stale "$ROOT/swift/Sources/MeshtasticTAK" '*.pb.swift' '' "${SWIFT_KEEP[@]}"
prune_stale "$ROOT/python/src/meshtastic_tak" '*_pb2.py' '' "${PY_KEEP[@]}"
prune_stale "$ROOT/csharp/src/Meshtastic.TAK" '*.cs' 'auto-generated' "${CS_KEEP[@]}"

# ---------------------------------------------------------------- Swift
echo "  Swift..."
protoc \
    --swift_opt=Visibility=Public \
    --swift_out="$ROOT/swift/Sources/MeshtasticTAK" \
    --proto_path="$PROTO_DIR" \
    --proto_path="$WELL_KNOWN" \
    "${ABS_PROTOS[@]}"
# protoc mirrors the proto's package path; this target is flat.
if [ -d "$ROOT/swift/Sources/MeshtasticTAK/meshtastic" ]; then
    mv "$ROOT/swift/Sources/MeshtasticTAK/meshtastic"/*.pb.swift "$ROOT/swift/Sources/MeshtasticTAK/"
    rmdir "$ROOT/swift/Sources/MeshtasticTAK/meshtastic"
fi

# ---------------------------------------------------------------- Python
echo "  Python..."
protoc \
    --python_out="$ROOT/python/src/meshtastic_tak" \
    --proto_path="$PROTO_DIR" \
    --proto_path="$WELL_KNOWN" \
    "${ABS_PROTOS[@]}"
if [ -d "$ROOT/python/src/meshtastic_tak/meshtastic" ]; then
    mv "$ROOT/python/src/meshtastic_tak/meshtastic"/*_pb2.py "$ROOT/python/src/meshtastic_tak/"
    rmdir "$ROOT/python/src/meshtastic_tak/meshtastic"
fi
# Flattening breaks protoc's cross-proto imports, which are absolute and rooted at
# the proto package: `from meshtastic import field_metadata_pb2`. Keeping protoc's
# meshtastic/ directory is not an option, because it would shadow the unrelated
# `meshtastic` package on PyPI for anyone who has both installed. So the imports are
# rewritten to be relative, which is how the rest of this package already refers to
# generated modules (`from . import atak_pb2`).
for f in "$ROOT/python/src/meshtastic_tak"/*_pb2.py; do
    sed -i.bak -E 's/^from meshtastic import ([a-z_]+_pb2)/from . import \1/' "$f"
    rm -f "$f.bak"
done

# ---------------------------------------------------------------- C#
echo "  C#..."
protoc \
    --csharp_out="$ROOT/csharp/src/Meshtastic.TAK" \
    --proto_path="$PROTO_DIR" \
    --proto_path="$WELL_KNOWN" \
    "${ABS_PROTOS[@]}"

echo "  Kotlin: regenerates on gradle build (no action needed)"
echo "  TypeScript: loads the .proto at runtime (no action needed)"
echo "Done. Review the diff: a change to a generator version marker without a"
echo "schema change means the toolchain, not the schema, moved."
