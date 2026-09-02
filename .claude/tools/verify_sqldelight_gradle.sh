#!/bin/bash
# Run SQLDelight's own compiler and migration verification over
# data/src/main/sqldelight, without the Android toolchain.
#
# The Python scripts next to this one exercise the SQLite engine; this one
# exercises the SQLDelight compiler, which is stricter and is what the real
# build runs. It exists because the Android toolchain is frequently
# unavailable: dl.google.com and www.jitpack.io are blocked by many egress
# policies, so the Android Gradle Plugin cannot be resolved and ./gradlew is
# out of reach. Maven Central and services.gradle.org usually are reachable,
# and the SQLDelight plugin, its dialect and the Kotlin plugin all live on
# Maven Central — so a throwaway JVM-only project can point the same compiler
# at the same .sq and .sqm files.
#
# It runs two checks:
#
#   1. generateMainDatabaseInterface — the stand-in for the build's
#      :data:generateDebugDatabaseInterface. Parses and type-checks every .sq.
#   2. verifyMainDatabaseMigration — applies the migrations that came after the
#      previous revision to that revision's schema and compares the result,
#      through SchemaCrawler, with the schema the .sq files declare. The
#      baseline .db is generated from git rather than read from the tree,
#      because this project checks none in.
#
# Usage: .claude/tools/verify_sqldelight_gradle.sh [--baseline <rev>] [--work-dir <dir>]
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
WORK_DIR="${TMPDIR:-/tmp}/mihon-sqldelight-check"
BASELINE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --baseline) BASELINE="$2"; shift 2 ;;
        --work-dir) WORK_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

SQ_DIR="$PROJECT_DIR/data/src/main/sqldelight"
note() { printf '[sqldelight] %s\n' "$1"; }

# --- configuration, read from the build rather than duplicated here ------
catalog() {
    sed -n "s/^$1 = \"\(.*\)\"/\1/p" "$PROJECT_DIR/gradle/libs.versions.toml" | head -1
}
module_of() {
    sed -n "s/^$1 = { module = \"\([^\"]*\)\".*/\1/p" "$PROJECT_DIR/gradle/libs.versions.toml" | head -1
}
SQLDELIGHT_VERSION="$(catalog sqldelight)"
KOTLIN_VERSION="$(catalog kotlin-gradle)"
DIALECT_MODULE="$(module_of sqldelight-sqliteDialect338)"
PACKAGE_NAME="$(sed -n 's/.*packageName.set("\([^"]*\)").*/\1/p' "$PROJECT_DIR/data/build.gradle.kts" | head -1)"

for pair in "SQLDELIGHT_VERSION:$SQLDELIGHT_VERSION" "KOTLIN_VERSION:$KOTLIN_VERSION" \
            "DIALECT_MODULE:$DIALECT_MODULE" "PACKAGE_NAME:$PACKAGE_NAME"; do
    if [ -z "${pair#*:}" ]; then
        note "could not read ${pair%%:*} from the build files; has the layout changed?"
        exit 2
    fi
done

# --- preflight -----------------------------------------------------------
# Any HTTP status means the host answered; curl reports 000 when the CONNECT
# was refused, which is what a blocked host looks like from in here.
reachable() {
    local code
    code="$(curl -sS -o /dev/null -m 15 --retry 0 -w '%{http_code}' "https://$1/" 2>/dev/null || true)"
    [ -n "$code" ] && [ "$code" != "000" ]
}
if ! reachable repo1.maven.org; then
    note "repo1.maven.org is unreachable, so the SQLDelight compiler cannot be fetched."
    note "the Python checks in this directory still work; see .claude/hooks/README.md"
    exit 2
fi

if command -v gradle >/dev/null 2>&1; then
    GRADLE=(gradle)
else
    # The wrapper needs services.gradle.org for the distribution itself.
    GRADLE=("$PROJECT_DIR/gradlew")
fi

# --- the throwaway project ------------------------------------------------
mkdir -p "$WORK_DIR"
cat >"$WORK_DIR/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "mihon-sqldelight-check"
EOF

# verifyMigrations stays off, as it is in data/build.gradle.kts: migrations 1
# to 14 target the legacy Tachiyomi schema, so type-checking them against the
# current one reports well over a hundred failures at any revision, upstream
# included. The .sq files are type-checked either way.
cat >"$WORK_DIR/build.gradle.kts" <<EOF
plugins {
    kotlin("jvm") version "$KOTLIN_VERSION"
    id("app.cash.sqldelight") version "$SQLDELIGHT_VERSION"
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("$PACKAGE_NAME")
            dialect("$DIALECT_MODULE:$SQLDELIGHT_VERSION")
            srcDirs.setFrom(file(System.getProperty("check.src")))
            schemaOutputDirectory.set(layout.buildDirectory.dir("schemas"))
            generateAsync.set(true)
            verifyMigrations.set(false)
        }
    }
}
EOF

gradle_run() {
    local log="$1" src="$2"; shift 2
    if (cd "$WORK_DIR" && "${GRADLE[@]}" --no-daemon --console=plain -Dcheck.src="$src" "$@" >"$log" 2>&1); then
        return 0
    fi
    # SQLDelight reports "<file>:<line>:<column> <problem>"; a schema mismatch
    # is reported as a diff instead, so fall back to the tail of the log.
    grep -E '\.(sq|sqm):[0-9]+:[0-9]+ ' "$log" || tail -40 "$log"
    note "full log: $log"
    return 1
}

# --- 1. the compiler ------------------------------------------------------
note "compiling $SQ_DIR with SQLDelight $SQLDELIGHT_VERSION"
gradle_run "$WORK_DIR/generate.log" "$SQ_DIR" generateMainDatabaseInterface --rerun-tasks
note "ok: every .sq parses and type-checks"

# --- 2. the migrations ----------------------------------------------------
# The baseline is the revision before the newest migration was added, which is
# the schema that migration claims to upgrade.
newest="$(ls "$SQ_DIR"/tachiyomi/migrations/*.sqm | sed 's#.*/##; s#\.sqm$##' | sort -n | tail -1)"
newest_path="data/src/main/sqldelight/tachiyomi/migrations/$newest.sqm"

if [ -z "$BASELINE" ]; then
    if git -C "$PROJECT_DIR" ls-files --error-unmatch "$newest_path" >/dev/null 2>&1; then
        added="$(git -C "$PROJECT_DIR" log --diff-filter=A --format=%H -1 -- "$newest_path")"
        BASELINE="$added^"
    else
        # Still uncommitted, so HEAD is already the schema it upgrades.
        BASELINE="HEAD"
    fi
fi

if ! git -C "$PROJECT_DIR" rev-parse --verify -q "$BASELINE^{commit}" >/dev/null; then
    note "baseline revision '$BASELINE' does not exist; skipping migration verification"
    exit 1
fi

note "generating the baseline schema from $(git -C "$PROJECT_DIR" rev-parse --short "$BASELINE^{commit}")"
rm -rf "$WORK_DIR/baseline" "$WORK_DIR/current"
mkdir -p "$WORK_DIR/baseline"
git -C "$PROJECT_DIR" archive "$BASELINE" data/src/main/sqldelight | tar -x -C "$WORK_DIR/baseline"
gradle_run "$WORK_DIR/schema.log" "$WORK_DIR/baseline/data/src/main/sqldelight" \
    generateMainDatabaseSchema --rerun-tasks

# SQLDelight names the file after the version the schema is already at, so
# 14 migrations produce 15.db, and 15.sqm is what takes it from there.
baseline_db="$(ls "$WORK_DIR/build/schemas"/*.db | head -1)"
if [ -z "$baseline_db" ]; then
    note "the baseline revision produced no schema file; skipping migration verification"
    exit 1
fi

# Verification reads .db files from the source folders, so the current tree is
# copied out to keep the generated baseline from landing in the repository.
mkdir -p "$WORK_DIR/current"
cp -r "$SQ_DIR" "$WORK_DIR/current/sqldelight"
cp "$baseline_db" "$WORK_DIR/current/sqldelight/"
note "applying the migrations after $(basename "$baseline_db" .db) and diffing against the .sq schema"
gradle_run "$WORK_DIR/verify.log" "$WORK_DIR/current/sqldelight" \
    verifyMainDatabaseMigration --rerun-tasks
note "ok: migrations reproduce the declared schema"
