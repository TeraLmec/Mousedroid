#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_CLIENT=1
BUILD_SERVER=1
BUILD_LINUX_SERVER=1
BUILD_WINDOWS_SERVER=1
BUILD_TYPE="Debug"
CLEAN=0
RELEASE_DIR="$ROOT_DIR/release"
PACKAGE_DIR="$RELEASE_DIR/packages"
WINDOWS_TRIPLET="${WINDOWS_TRIPLET:-x64-mingw-dynamic}"
PUBLISH_GITHUB=0
RELEASE_TAG=""
RELEASE_TITLE=""
RELEASE_DRAFT=0
RELEASE_PRERELEASE=0

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
  --linux         Build only the Linux desktop server
  --windows       Build only the Windows desktop server
  --debug         Build debug artifacts (default)
  --release       Build release artifacts
  --clean         Remove the selected build outputs before building
  --publish-github
                  Create/update a GitHub Release from local package artifacts
  --tag TAG       GitHub release tag (default: next v<Android versionName> tag)
  --title TITLE   GitHub release title (default: Mousedroid TAG)
  --draft         Create a draft GitHub Release when the release does not exist
  --prerelease    Mark a new GitHub Release as a prerelease
  -h, --help      Show this help

Environment:
  ANDROID_HOME or ANDROID_SDK_ROOT must point to an Android SDK when building
  the client if client/local.properties does not point to a valid SDK.
  A local .env file at the repo root is loaded automatically.
  VCPKG_ROOT must point to a vcpkg checkout when building the Windows server
  on Linux. WINDOWS_TRIPLET defaults to x64-mingw-dynamic.
  WINDOWS_SERVER_BIN can point to a directory containing an existing
  Mousedroid.exe and runtime DLLs to package instead of cross-building.
  GitHub publishing requires the GitHub CLI: gh auth login
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --client)
            BUILD_CLIENT=1
            BUILD_SERVER=0
            BUILD_LINUX_SERVER=0
            BUILD_WINDOWS_SERVER=0
            ;;
        --server)
            BUILD_CLIENT=0
            BUILD_SERVER=1
            BUILD_LINUX_SERVER=1
            BUILD_WINDOWS_SERVER=1
            ;;
        --linux)
            BUILD_CLIENT=0
            BUILD_SERVER=1
            BUILD_LINUX_SERVER=1
            BUILD_WINDOWS_SERVER=0
            ;;
        --windows)
            BUILD_CLIENT=0
            BUILD_SERVER=1
            BUILD_LINUX_SERVER=0
            BUILD_WINDOWS_SERVER=1
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
        --publish-github)
            PUBLISH_GITHUB=1
            ;;
        --tag)
            shift
            if [[ $# -eq 0 ]]; then
                echo "--tag requires a value." >&2
                exit 1
            fi
            RELEASE_TAG="$1"
            ;;
        --title)
            shift
            if [[ $# -eq 0 ]]; then
                echo "--title requires a value." >&2
                exit 1
            fi
            RELEASE_TITLE="$1"
            ;;
        --draft)
            RELEASE_DRAFT=1
            ;;
        --prerelease)
            RELEASE_PRERELEASE=1
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

write_default_config() {
    local config_path="$1"

    cat > "$config_path" <<CONFIG
MINIMIZE_TASKBAR=0
MOVE_SENSITIVITY=10
RUN_STARTUP=0
SCROLL_SENSITIVITY=3
CONFIG
}

build_linux_server() {
    local build_dir="$ROOT_DIR/build/server-linux-${BUILD_TYPE,,}"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$build_dir"
    fi

    echo "Configuring Linux server: $BUILD_TYPE"
    cmake -S "$ROOT_DIR/server" -B "$build_dir" -DCMAKE_BUILD_TYPE="$BUILD_TYPE"

    echo "Building Linux server"
    cmake --build "$build_dir"

    local server_binary="$build_dir/bin/Mousedroid"

    if [[ ! -f "$server_binary" ]]; then
        echo "Server binary not found at $server_binary" >&2
        exit 1
    fi

    local destination_dir="$RELEASE_DIR/server-linux"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$destination_dir"
    fi

    mkdir -p "$destination_dir"
    cp "$server_binary" "$destination_dir/Mousedroid"
    chmod +x "$destination_dir/Mousedroid"
    copy_if_exists "$ROOT_DIR/server/icon.png" "$destination_dir/"
    copy_if_exists "$ROOT_DIR/server/adb" "$destination_dir/"
    write_default_config "$destination_dir/config.ini"
}

vcpkg_toolchain_file() {
    if [[ -z "${VCPKG_ROOT:-}" ]]; then
        return 1
    fi

    local toolchain_file="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake"
    [[ -f "$toolchain_file" ]] || return 1
    printf '%s\n' "$toolchain_file"
}

ensure_windows_cross_build_prereqs() {
    if [[ -n "${WINDOWS_SERVER_BIN:-}" ]]; then
        return 0
    fi

    local missing=()
    local command_name

    for command_name in cmake x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++ x86_64-w64-mingw32-windres; do
        if ! command -v "$command_name" >/dev/null 2>&1; then
            missing+=("$command_name")
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "Missing Windows cross-build tools: ${missing[*]}" >&2
        echo "Install them on Ubuntu with: sudo apt install mingw-w64 cmake ninja-build" >&2
        exit 1
    fi

    if ! vcpkg_toolchain_file >/dev/null; then
        echo "VCPKG_ROOT must point to a vcpkg checkout containing scripts/buildsystems/vcpkg.cmake." >&2
        echo "Example: export VCPKG_ROOT=\$HOME/src/vcpkg" >&2
        exit 1
    fi
}

build_windows_server() {
    local build_dir="$ROOT_DIR/build/server-windows-${BUILD_TYPE,,}"
    local toolchain_file

    ensure_windows_cross_build_prereqs

    if [[ -n "${WINDOWS_SERVER_BIN:-}" ]]; then
        package_existing_windows_server "$build_dir"
        return 0
    fi

    toolchain_file="$(vcpkg_toolchain_file)"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$build_dir"
    fi

    echo "Configuring Windows server: $BUILD_TYPE ($WINDOWS_TRIPLET)"
    cmake -S "$ROOT_DIR/server" -B "$build_dir" \
        -DCMAKE_SYSTEM_NAME=Windows \
        -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
        -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
        -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
        -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
        -DVCPKG_APPLOCAL_DEPS=OFF \
        -DVCPKG_TARGET_TRIPLET="$WINDOWS_TRIPLET"

    echo "Building Windows server"
    cmake --build "$build_dir"

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

copy_vcpkg_windows_runtime_files() {
    local destination_dir="$1"
    local build_dir="${2:-}"
    local runtime_dirs=("${VCPKG_ROOT:-}/installed/$WINDOWS_TRIPLET/bin")
    local runtime_dir

    if [[ -n "$build_dir" ]]; then
        runtime_dirs=("$build_dir/vcpkg_installed/$WINDOWS_TRIPLET/bin" "${runtime_dirs[@]}")
    fi

    for runtime_dir in "${runtime_dirs[@]}"; do
        if [[ ! -d "$runtime_dir" ]]; then
            continue
        fi

        while IFS= read -r file; do
            cp -a "$file" "$destination_dir/"
        done < <(find "$runtime_dir" -maxdepth 1 -type f -name "*.dll" 2>/dev/null)
    done
}

copy_mingw_runtime_files() {
    local destination_dir="$1"
    local runtime_name
    local runtime_path

    if ! command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
        return 0
    fi

    for runtime_name in libgcc_s_seh-1.dll libstdc++-6.dll libwinpthread-1.dll; do
        runtime_path="$(x86_64-w64-mingw32-g++ -print-file-name="$runtime_name")"
        if [[ -f "$runtime_path" ]]; then
            cp -a "$runtime_path" "$destination_dir/"
        fi
    done
}

package_existing_windows_server() {
    local build_dir="$1"
    local source_dir

    if ! source_dir="$(find_windows_server_bin "$build_dir")"; then
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
    copy_vcpkg_windows_runtime_files "$destination_dir"
    copy_mingw_runtime_files "$destination_dir"
    copy_if_exists "$ROOT_DIR/server/app.ico" "$destination_dir/"
    copy_if_exists "$ROOT_DIR/server/adb" "$destination_dir/"
    write_default_config "$destination_dir/config.ini"

    echo "Windows server package written to $destination_dir"
}

package_windows_server() {
    local build_dir="$1"
    local source_dir="$build_dir/bin"

    if [[ ! -f "$source_dir/Mousedroid.exe" ]]; then
        echo "Windows server binary not found at $source_dir/Mousedroid.exe" >&2
        exit 1
    fi

    local destination_dir="$RELEASE_DIR/server-windows"

    if [[ "$CLEAN" -eq 1 ]]; then
        rm -rf "$destination_dir"
    fi

    mkdir -p "$destination_dir"
    cp -a "$source_dir/Mousedroid.exe" "$destination_dir/"
    copy_windows_runtime_files "$source_dir" "$destination_dir"
    copy_vcpkg_windows_runtime_files "$destination_dir" "$build_dir"
    copy_mingw_runtime_files "$destination_dir"
    copy_if_exists "$ROOT_DIR/server/app.ico" "$destination_dir/"
    copy_if_exists "$ROOT_DIR/server/adb" "$destination_dir/"
    write_default_config "$destination_dir/config.ini"

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

    if [[ "$BUILD_SERVER" -eq 1 && "$BUILD_LINUX_SERVER" -eq 1 ]]; then
        zip_directory "$RELEASE_DIR/server-linux" "$PACKAGE_DIR/Mousedroid-server-linux-${variant_dir}.zip"
    fi

    if [[ "$BUILD_SERVER" -eq 1 && "$BUILD_WINDOWS_SERVER" -eq 1 ]]; then
        zip_directory "$RELEASE_DIR/server-windows" "$PACKAGE_DIR/Mousedroid-server-windows-${variant_dir}.zip"
    fi
}

android_version_name() {
    sed -n 's/.*versionName[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT_DIR/client/app/build.gradle" | head -n 1
}

default_release_tag() {
    local version_name
    local base_tag
    local max_suffix=-1
    local tag
    local suffix
    version_name="$(android_version_name)"

    if [[ -z "$version_name" ]]; then
        echo "Could not infer Android versionName from client/app/build.gradle. Pass --tag explicitly." >&2
        exit 1
    fi

    base_tag="v$version_name"

    if ! command -v gh >/dev/null 2>&1; then
        printf '%s\n' "$base_tag"
        return 0
    fi

    while IFS= read -r tag; do
        if [[ "$tag" == "$base_tag" ]]; then
            if [[ "$max_suffix" -lt 0 ]]; then
                max_suffix=0
            fi
        elif [[ "$tag" =~ ^${base_tag//./\\.}\.([0-9]+)$ ]]; then
            suffix="${BASH_REMATCH[1]}"
            if [[ "$suffix" -gt "$max_suffix" ]]; then
                max_suffix="$suffix"
            fi
        fi
    done < <(gh release list --limit 1000 --json tagName --jq '.[].tagName' 2>/dev/null || true)

    if [[ "$max_suffix" -lt 0 ]]; then
        printf '%s\n' "$base_tag"
    else
        printf '%s.%d\n' "$base_tag" "$((max_suffix + 1))"
    fi
}

preflight_github_publish() {
    if [[ "$BUILD_TYPE" != "Release" ]]; then
        echo "GitHub publishing requires --release so debug artifacts are not published by accident." >&2
        exit 1
    fi

    if ! command -v gh >/dev/null 2>&1; then
        echo "GitHub CLI not found. Install it, then run: gh auth login" >&2
        exit 1
    fi

    if ! gh auth status >/dev/null 2>&1; then
        echo "GitHub CLI is not authenticated. Run: gh auth login" >&2
        exit 1
    fi
}

publish_github_release() {
    local variant_dir="${BUILD_TYPE,,}"
    local tag="${RELEASE_TAG:-}"
    local title="${RELEASE_TITLE:-}"
    local artifacts=()
    local release_flags=()

    if [[ -z "$tag" ]]; then
        tag="$(default_release_tag)"
    fi

    if [[ -z "$title" ]]; then
        title="Mousedroid $tag"
    fi

    if [[ "$RELEASE_DRAFT" -eq 1 ]]; then
        release_flags+=(--draft)
    fi

    if [[ "$RELEASE_PRERELEASE" -eq 1 ]]; then
        release_flags+=(--prerelease)
    fi

    shopt -s nullglob
    artifacts=("$PACKAGE_DIR"/*-"$variant_dir".zip)
    shopt -u nullglob

    if [[ ${#artifacts[@]} -eq 0 ]]; then
        echo "No $variant_dir package artifacts found in $PACKAGE_DIR." >&2
        exit 1
    fi

    if gh release view "$tag" >/dev/null 2>&1; then
        echo "Updating GitHub Release $tag"
        gh release upload "$tag" "${artifacts[@]}" --clobber
    else
        echo "Creating GitHub Release $tag"
        gh release create "$tag" "${artifacts[@]}" \
            --title "$title" \
            --generate-notes \
            "${release_flags[@]}"
    fi
}

if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
    preflight_github_publish
fi

if [[ "$BUILD_SERVER" -eq 1 && "$BUILD_LINUX_SERVER" -eq 1 ]]; then
    build_linux_server
fi

if [[ "$BUILD_SERVER" -eq 1 && "$BUILD_WINDOWS_SERVER" -eq 1 ]]; then
    build_windows_server
fi

if [[ "$BUILD_CLIENT" -eq 1 ]]; then
    build_client
fi

package_release_zips

if [[ "$PUBLISH_GITHUB" -eq 1 ]]; then
    publish_github_release
fi

echo "Build complete."
