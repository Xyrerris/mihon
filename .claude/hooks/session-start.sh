#!/bin/bash
# Provision the Android SDK and warm the Gradle cache so that a Claude Code on
# the web session can build, lint and test this project.
#
# The container is rebuilt for every session, so this runs from scratch each
# time; everything below is idempotent and skips work that is already done.
set -euo pipefail

# Local checkouts already have a working toolchain.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"

# Bootstrap build of the command line tools. sdkmanager updates itself
# afterwards, so this only has to be new enough to run; bump it if Google
# retires the download.
CMDLINE_TOOLS_BUILD="13114758"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"

# Read the levels the build actually asks for instead of hardcoding them here.
catalog() {
    sed -n "s/^$1 = \"\(.*\)\"/\1/p" "$PROJECT_DIR/gradle/mihon.versions.toml" | head -1
}
COMPILE_SDK="$(catalog android-sdk-compile)"
COMPILE_SDK="${COMPILE_SDK:-37}"

note() { printf '[session-start] %s\n' "$1"; }

persist() {
    if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
        echo "$1" >>"$CLAUDE_ENV_FILE"
    fi
}

# --- preflight -----------------------------------------------------------
# Both hosts below are mandatory: dl.google.com serves the SDK and the Android
# Gradle Plugin, www.jitpack.io serves sqldelight-androidx-driver, injekt and
# several other required dependencies. When an egress policy blocks either one
# there is nothing this script can do, so say which host failed and stop rather
# than burning the session's startup budget on downloads that cannot succeed.
# Any HTTP status means the host answered; curl reports 000 only when no
# response was received at all, which is what a refused CONNECT looks like.
# Checking the status rather than curl's exit code keeps a plain 404 on the
# root path from being misread as a blocked host.
reachable() {
    local code
    code="$(curl -sS -o /dev/null -m 15 --retry 0 -w '%{http_code}' "https://$1/" 2>/dev/null || true)"
    [ -n "$code" ] && [ "$code" != "000" ]
}

blocked=""
for host in dl.google.com www.jitpack.io services.gradle.org repo1.maven.org; do
    reachable "$host" || blocked="$blocked $host"
done

if [ -n "$blocked" ]; then
    note "cannot provision the Android toolchain; blocked host(s):$blocked"
    note "these are denied by this environment's network policy, not by the project."
    note "allow them on the environment (see the network access section of"
    note "https://code.claude.com/docs/en/claude-code-on-the-web) and start a new session."
    note "schema work still works, SQLDelight included: see .claude/hooks/README.md"
    persist "export MIHON_ANDROID_TOOLCHAIN=unavailable"
    persist "export MIHON_TOOLCHAIN_BLOCKED_HOSTS=\"${blocked# }\""
    # Exit successfully: the session is still usable for everything that does
    # not need Gradle, and a failing hook would only obscure that.
    exit 0
fi

# --- Android SDK ---------------------------------------------------------
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    note "installing Android command line tools into $SDK_ROOT"
    mkdir -p "$SDK_ROOT/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/tools.zip" "$CMDLINE_TOOLS_URL"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    rm -rf "$SDK_ROOT/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rm -rf "$tmp"
else
    note "Android command line tools already present"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

note "accepting SDK licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# Google publishes minor-versioned platforms for recent levels: API 37 ships as
# platforms;android-37.0 and up, and asking for the historical major-only name
# fails outright rather than resolving to one of them. Read the names the
# repository actually offers, prefer an exact match, and otherwise take the
# lowest minor of the level the catalog asks for. That is the one the build
# wants: compileSdk = 37 with no compileSdkMinor resolves to android-37.0, and
# a later minor is opted into in the build files, not here.
resolve_platform() {
    local available
    available="$(sdkmanager --list 2>/dev/null |
        sed -n 's/^ *\(platforms;android-[0-9][0-9.]*\) .*/\1/p' | sort -u)"
    if printf '%s\n' "$available" | grep -qx "platforms;android-$1"; then
        printf 'platforms;android-%s\n' "$1"
        return
    fi
    printf '%s\n' "$available" | grep "^platforms;android-$1\." | sort -V | head -1
}

PLATFORM_PACKAGE="$(resolve_platform "$COMPILE_SDK")"
if [ -z "$PLATFORM_PACKAGE" ]; then
    note "the SDK repository publishes no platform for android-$COMPILE_SDK"
    note "check gradle/mihon.versions.toml against sdkmanager --list"
    persist "export MIHON_ANDROID_TOOLCHAIN=unavailable"
    exit 0
fi

note "installing platform-tools and $PLATFORM_PACKAGE"
if ! sdkmanager --install "platform-tools" "$PLATFORM_PACKAGE" >/dev/null; then
    note "sdkmanager could not install $PLATFORM_PACKAGE; the session cannot build"
    persist "export MIHON_ANDROID_TOOLCHAIN=unavailable"
    exit 0
fi

# AGP picks its own build-tools, so let it resolve them rather than pinning a
# version here that would drift out of sync with the plugin.

# local.properties is gitignored and is what Gradle reads when ANDROID_HOME is
# not inherited by the daemon.
if ! grep -qs '^sdk.dir=' "$PROJECT_DIR/local.properties" 2>/dev/null; then
    echo "sdk.dir=$SDK_ROOT" >>"$PROJECT_DIR/local.properties"
fi

persist "export ANDROID_HOME=\"$SDK_ROOT\""
persist "export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
persist "export PATH=\"$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:\$PATH\""
persist "export MIHON_ANDROID_TOOLCHAIN=ready"

# --- Gradle cache --------------------------------------------------------
# Resolving dependencies once here means the first real build in the session is
# fast. --no-daemon keeps the container image smaller when it is snapshotted.
note "warming the Gradle cache (first run downloads a few hundred MB)"
cd "$PROJECT_DIR"
./gradlew --no-daemon --quiet :data:generateDebugDatabaseInterface || {
    note "gradle warm-up did not finish cleanly; the session can still build manually"
    exit 0
}

note "toolchain ready: ./gradlew :data:generateDebugDatabaseInterface works"
