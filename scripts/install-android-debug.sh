#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

resolve_sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return 0
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return 0
  fi

  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    awk -F= '/^sdk\.dir=/{print substr($0, index($0,$2))}' "$ROOT_DIR/local.properties" | tail -n 1
    return 0
  fi

  return 1
}

ensure_local_properties() {
  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    return 0
  fi

  local sdk_dir
  sdk_dir="$(resolve_sdk_dir || true)"
  if [[ -z "$sdk_dir" ]]; then
    cat <<'EOF'
Android SDK not found.

Set one of these first:
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"

Or create local.properties with:
  sdk.dir=/absolute/path/to/Android/sdk
EOF
    exit 1
  fi

  printf 'sdk.dir=%s\n' "${sdk_dir//\\/\\\\}" > "$ROOT_DIR/local.properties"
  echo "Wrote $ROOT_DIR/local.properties"
}

resolve_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  local sdk_dir
  sdk_dir="$(resolve_sdk_dir || true)"
  if [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
    printf '%s\n' "$sdk_dir/platform-tools/adb"
    return 0
  fi

  return 1
}

main() {
  ensure_local_properties

  local adb_bin
  adb_bin="$(resolve_adb || true)"
  if [[ -z "$adb_bin" ]]; then
    cat <<'EOF'
adb not found.

Install Android platform-tools, then either:
  1. put adb on PATH, or
  2. set ANDROID_HOME / ANDROID_SDK_ROOT so the script can use <sdk>/platform-tools/adb
EOF
    exit 1
  fi

  if ! "$adb_bin" get-state >/dev/null 2>&1; then
    cat <<'EOF'
No Android device detected.

On the phone:
  1. Enable Developer options
  2. Enable USB debugging
  3. Connect with USB
  4. Accept the RSA prompt

Then rerun this script.
EOF
    exit 1
  fi

  "$ROOT_DIR/scripts/build-android-binary.sh"
  (
    cd "$ROOT_DIR"
    ./gradlew :app:installDebug
  )

  echo
  echo "Installed debug build on the connected Android device."
  echo "Launch app: Hotspot ProxyT"
}

main "$@"
