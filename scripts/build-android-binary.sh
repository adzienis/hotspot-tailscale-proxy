#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

(
  cd "$ROOT_DIR"
  ./gradlew :app:buildGoAndroidBinaries
)

echo "Built Android binaries under $ROOT_DIR/app/build/generated/jniLibs/proxyt"
