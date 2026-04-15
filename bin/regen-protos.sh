#!/usr/bin/env bash
set -euo pipefail

# Regenerate protobuf code for all platforms from the protobufs submodule.
# Requires: protoc, protoc-gen-swift (brew install swift-protobuf)
# Kotlin uses the gradle protobuf plugin and regenerates on build.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
PROTO_DIR="$ROOT/protobufs"

if [ ! -f "$PROTO_DIR/meshtastic/atak.proto" ]; then
    echo "Error: protobufs submodule not found. Run: git submodule update --init"
    exit 1
fi

echo "Regenerating protobuf code from protobufs submodule..."

# Swift
echo "  Swift..."
protoc \
    --swift_opt=Visibility=Public \
    --swift_out="$ROOT/swift/Sources/MeshtasticTAK" \
    --proto_path="$PROTO_DIR" \
    "$PROTO_DIR/meshtastic/atak.proto"
# protoc puts it in a meshtastic/ subdirectory, move it up
if [ -f "$ROOT/swift/Sources/MeshtasticTAK/meshtastic/atak.pb.swift" ]; then
    mv "$ROOT/swift/Sources/MeshtasticTAK/meshtastic/atak.pb.swift" "$ROOT/swift/Sources/MeshtasticTAK/"
    rmdir "$ROOT/swift/Sources/MeshtasticTAK/meshtastic" 2>/dev/null || true
fi

# Python
echo "  Python..."
protoc \
    --python_out="$ROOT/python/src/meshtastic_tak" \
    --proto_path="$PROTO_DIR" \
    "$PROTO_DIR/meshtastic/atak.proto"
if [ -f "$ROOT/python/src/meshtastic_tak/meshtastic/atak_pb2.py" ]; then
    mv "$ROOT/python/src/meshtastic_tak/meshtastic/atak_pb2.py" "$ROOT/python/src/meshtastic_tak/"
    rmdir "$ROOT/python/src/meshtastic_tak/meshtastic" 2>/dev/null || true
fi

# C#
echo "  C#..."
protoc \
    --csharp_out="$ROOT/csharp/src/Meshtastic.TAK" \
    --proto_path="$PROTO_DIR" \
    "$PROTO_DIR/meshtastic/atak.proto"

# Kotlin regenerates via gradle protobuf plugin on build (no manual step needed)
echo "  Kotlin: regenerates on gradle build (no action needed)"

# TypeScript loads proto at runtime via protobufjs (no codegen needed)
echo "  TypeScript: loads proto at runtime (no action needed)"

echo "Done."
