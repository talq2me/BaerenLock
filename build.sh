#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./build.sh            -> build debug APK
#   ./build.sh debug      -> build debug APK
#   ./build.sh release    -> build release APK
#   ./build.sh all        -> build debug + release APKs

MODE="${1:-debug}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

build_debug() {
  echo "Building debug APK..."
  ./gradlew assembleDebug
  echo "Debug APK:"
  echo "  $ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
}

build_release() {
  echo "Building release APK..."
  ./gradlew assembleRelease
  echo "Release APK:"
  echo "  $ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
}

case "$MODE" in
  debug)
    build_debug
    ;;
  release)
    build_release
    ;;
  all)
    build_debug
    build_release
    ;;
  *)
    echo "Unknown mode: $MODE"
    echo "Usage: ./build.sh [debug|release|all]"
    exit 1
    ;;
esac

