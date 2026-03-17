#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="$ROOT_DIR/upstream-proxyt"
OUTPUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
OUTPUT_BIN="$OUTPUT_DIR/libproxyt.so"

mkdir -p "$OUTPUT_DIR"

(
  cd "$SOURCE_DIR"
  GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -trimpath -ldflags="-s -w" -o "$OUTPUT_BIN" .
)

chmod 755 "$OUTPUT_BIN"
echo "Built Android binary at $OUTPUT_BIN"
