#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_CLIENT=1
BUILD_SERVER=1
BUILD_TYPE="Debug"
CLEAN=0
RELEASE_DIR="$ROOT_DIR/release"
PACKAGE_DIR="$RELEASE_DIR/packages"

if [[ -f "$ROOT_DIR/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$ROOT_DIR/.env"
    set +a
fi

usage() {
    cat <<USAGE
Usage: ./build.sh [options]

Options:
  --client        Build only the Android client
  --server        Build only the native desktop server
  --debug         Build debug artifacts (default)
  --release       Build release artifacts
  --clean         Remove the selected build outputs before building
  -h, --help      Show this help

Environment:
  ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK when building
  the client if client/local.properties does not point to a valid SDK.
  A local .env file at the repo root is loaded automatically.
  WINDOWS_SERVER_BIN can point to a directory containing Mousedroid.exe and
  runtime DLLs to package into release/server-windows.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --client)
            BUILD_CLIENT=1
            BUILD_SERVER=0
            ;;
        --server)
            BUILD_CLIENT=0
            BUILD_SERVER=1
            ;;
        --debug)
            BUILD_TYPE="Debug"
            ;;
        --release)
            BUILD_TYPE="Release"
            ;;
        --clean)
            CLEAN=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            exit 1
            ;;
    esac
    shift
done

android_sdk_from_local_properties() {
    local local_properties="$ROOT_DIR/client/local.properties"
    [[ -f "$local_properties" ]] || return 1

    local sdk_dir
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$local_properties" | tail -n 1)"
    [[ -n "$sdk_dir" ]] || return 1

    sdk_dir="${sdk_dir//\\\\/\\}"
    [[ -d "$sdk_dir" ]] || return 1
    printf '%s\n' "$sdk_dir"
}

ensure_android_sdk() {
    local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

    if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
        sdk_dir="$(android_sdk_from_local_properties || true)"
    fi

    if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
        echo "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT to build the client." >&2
        exit 1
    fi

    export ANDROID_HOME="$sdk_dir"
    export ANDROID_SDK_ROOT="$sdk_dir"
}

build_client() {
    ensure_android_sdk

    local task="assembleDebug"
    if [[ "$BUILD_TYPE" == "Release" ]]; then
        task="assembleRelease"
    fi

    if [[ "$CLEAN" -eq 1 ]]; then
        (cd "$ROOT_DIR/client" && sh gradlew clean)
    fi

    echo "Building Android client: $task"
    (cd "$ROOT_DIR/client" && sh gradlew "$task")

    mkdir -p "$RELEASE_DIR/android"
    local variant_dir="${BUILD_TYPE,,}"
    local apk_path="$ROOT_DIR/client/app/build/outputs/apk/$variant_dir/app-$variant_dir.apk"

    if [[ ! -f "$apk_path" && "$variant_dir" == "release" ]]; then
        apk_path="$ROOT_DIR/client/app/build/outputs/apk/$variant_dir/app-release-unsigned.apk"
    fi

    if [[ -f "$apk_path" ]]; then
        cp "$apk_path" "$RELEASE_DIR/android/Mousedroid-${variant_dir}.apk"
    else
        echo "Android APK not found at $apk_path" >&2
        exit 1
    fi
}

build_server() {
    local build_dir="$ROOT_DIR/build/server-${BUILD_TYPE,,}"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$build_dir"
    fi

    echo "Configuring native server: $BUILD_TYPE"
    cmake -S "$ROOT_DIR/server" -B "$build_dir" -DCMAKE_BUILD_TYPE="$BUILD_TYPE"

    echo "Building native server"
    cmake --build "$build_dir"

    local server_binary="$build_dir/bin/Mousedroid"

    if [[ -f "$server_binary" ]]; then
        mkdir -p "$RELEASE_DIR/server-linux"
        cp "$server_binary" "$RELEASE_DIR/server-linux/Mousedroid"
        chmod +x "$RELEASE_DIR/server-linux/Mousedroid"
    elif [[ "$OSTYPE" != msys* && "$OSTYPE" != cygwin* && "$OSTYPE" != win32* ]]; then
        echo "Server binary not found at $server_binary" >&2
        exit 1
    fi

    package_windows_server "$build_dir"
}

find_windows_server_bin() {
    local build_dir="$1"
    local candidates=(
        "${WINDOWS_SERVER_BIN:-}"
        "$build_dir/bin"
        "$ROOT_DIR/build/server-${BUILD_TYPE,,}/bin"
        "$ROOT_DIR/server/out/build/x64-${BUILD_TYPE}/bin"
        "$ROOT_DIR/server/out/build/x64-${BUILD_TYPE}/"
        "$ROOT_DIR/server/out/build/x64-Release/bin"
        "$ROOT_DIR/server/out/build/x64-Debug/bin"
        "$ROOT_DIR/server/cmake/bin"
    )

    for candidate in "${candidates[@]}"; do
        if [[ -n "$candidate" && -f "$candidate/Mousedroid.exe" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

copy_if_exists() {
    local source="$1"
    local destination="$2"

    if [[ -e "$source" ]]; then
        cp -a "$source" "$destination"
    fi
}

copy_windows_runtime_files() {
    local source_dir="$1"
    local destination_dir="$2"
    local patterns=("*.dll" "*.pdb" "*.ilk" "*.log")

    for pattern in "${patterns[@]}"; do
        while IFS= read -r file; do
            cp -a "$file" "$destination_dir/"
        done < <(find "$source_dir" -maxdepth 1 -type f -name "$pattern" 2>/dev/null)
    done
}

package_windows_server() {
    local build_dir="$1"
    local source_dir

    if ! source_dir="$(find_windows_server_bin "$build_dir")"; then
        if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* || "$OSTYPE" == win32* ]]; then
            echo "Windows server binary not found. Set WINDOWS_SERVER_BIN to the directory containing Mousedroid.exe." >&2
            exit 1
        fi

        echo "Windows server package skipped: Mousedroid.exe not found."
        return 0
    fi

    local destination_dir="$RELEASE_DIR/server-windows"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$destination_dir"
    fi

    mkdir -p "$destination_dir"
    cp -a "$source_dir/Mousedroid.exe" "$destination_dir/"
    copy_windows_runtime_files "$source_dir" "$destination_dir"
    copy_if_exists "$ROOT_DIR/server/app.ico" "$destination_dir/"
    copy_if_exists "$ROOT_DIR/server/adb" "$destination_dir/"

    cat > "$destination_dir/config.ini" <<CONFIG
MINIMIZE_TASKBAR=0
MOVE_SENSITIVITY=10
RUN_STARTUP=0
SCROLL_SENSITIVITY=3
CONFIG

    echo "Windows server package written to $destination_dir"
}

zip_directory() {
    local source_dir="$1"
    local zip_path="$2"
    local base_name

    if [[ ! -d "$source_dir" ]]; then
        return 0
    fi

    mkdir -p "$(dirname "$zip_path")"
    rm -f "$zip_path"
    base_name="$(basename "$source_dir")"

    if command -v zip >/dev/null 2>&1; then
        (
            cd "$(dirname "$source_dir")"
            zip -qr "$zip_path" "$base_name"
        )
    else
        (
            cd "$(dirname "$source_dir")"
            python3 -m zipfile -c "$zip_path" "$base_name"
        )
    fi
}

package_release_zips() {
    local variant_dir="${BUILD_TYPE,,}"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$PACKAGE_DIR"
    fi

    mkdir -p "$PACKAGE_DIR"

    if [[ "$BUILD_CLIENT" -eq 1 && -f "$RELEASE_DIR/android/Mousedroid-${variant_dir}.apk" ]]; then
        rm -f "$PACKAGE_DIR/Mousedroid-android-${variant_dir}.zip"
        if command -v zip >/dev/null 2>&1; then
            (
                cd "$RELEASE_DIR/android"
                zip -q "$PACKAGE_DIR/Mousedroid-android-${variant_dir}.zip" "Mousedroid-${variant_dir}.apk"
            )
        else
            (
                cd "$RELEASE_DIR/android"
                python3 -m zipfile -c "$PACKAGE_DIR/Mousedroid-android-${variant_dir}.zip" "Mousedroid-${variant_dir}.apk"
            )
        fi
    fi

    if [[ "$BUILD_SERVER" -eq 1 ]]; then
        zip_directory "$RELEASE_DIR/server-linux" "$PACKAGE_DIR/Mousedroid-server-linux-${variant_dir}.zip"
        zip_directory "$RELEASE_DIR/server-windows" "$PACKAGE_DIR/Mousedroid-server-windows-${variant_dir}.zip"
    fi
}

if [[ "$BUILD_SERVER" -eq 1 ]]; then
    build_server
fi

if [[ "$BUILD_CLIENT" -eq 1 ]]; then
    build_client
fi

package_release_zips

echo "Build complete."
