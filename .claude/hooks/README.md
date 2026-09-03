# Session setup for Claude Code on the web

`session-start.sh` runs at the start of every remote session. Containers are
rebuilt each time, so it provisions the Android toolchain from scratch:

1. Checks that the hosts the build needs are actually reachable.
2. Installs the Android command line tools into `$HOME/android-sdk`.
3. Accepts the SDK licenses and installs `platform-tools` plus the
   `compileSdk` level read from `gradle/mihon.versions.toml`.
4. Writes `sdk.dir` into `local.properties` and exports `ANDROID_HOME`
   through `$CLAUDE_ENV_FILE`.
5. Warms the Gradle cache with `:data:generateDebugDatabaseInterface`.

It does nothing on local checkouts (`CLAUDE_CODE_REMOTE` is not `true`).

## Network access is required

The build cannot be provisioned unless the environment's egress policy allows:

| Host | Serves |
| --- | --- |
| `dl.google.com` | Android SDK packages and the Android Gradle Plugin |
| `www.jitpack.io` | `sqldelight-androidx-driver`, `injekt`, `quickjs-android`, `PhotoView` and other required dependencies |
| `services.gradle.org` | the Gradle distribution named by the wrapper |
| `repo1.maven.org` | Maven Central dependencies |

If any of these is denied, the hook prints which one, sets
`MIHON_ANDROID_TOOLCHAIN=unavailable` and exits without failing the session.

The first two are the ones a policy usually denies, and neither has an
alternative: `google()` resolves to `dl.google.com/dl/android/maven2`,
`maven.google.com` only redirects there, and jitpack builds are served nowhere
else. **Trusted** network access does not cover either — its list has
`developer.android.com`, which is the documentation, not the repository — so an
environment that has to build this project needs **Custom**, with *Also include
default list of common package managers* checked and:

```text
dl.google.com
www.jitpack.io
jitpack.io
*.frame.claudeusercontent.com
```

The last line is not for the build: the plan this fork follows lives in an
artifact, and that is the host artifact content is fetched from. Leave it out
and a session can no longer read the plan.

The checkbox appears only once **Custom** is selected. If it is missing
entirely, this list stands on its own without the defaults:

```text
dl.google.com
maven.google.com
www.jitpack.io
jitpack.io
repo.maven.apache.org
repo1.maven.org
plugins.gradle.org
services.gradle.org
release-assets.githubusercontent.com
claude.ai
code.claude.com
*.frame.claudeusercontent.com
```

Two of those are not obvious. `mavenCentral()` resolves to
`repo.maven.apache.org`, not to the `repo1.maven.org` alias, and the plugin
portal redirects there too. And `services.gradle.org` only issues the redirect
for the wrapper's distribution: the zip itself comes from
`release-assets.githubusercontent.com`, so an allowlist without it downloads
nothing and `./gradlew` never starts.

This is an environment setting, changed at
<https://code.claude.com/docs/en/cloud-environments#allow-specific-domains> and
picked up by the *next* session, not the running one. It is not something a
session can change from the inside, and the egress proxy correctly refuses
attempts to route around it.

## Working without the toolchain

The two hosts the toolchain needs are the only ones usually blocked;
`repo1.maven.org` and `services.gradle.org` normally answer. That is enough to
run SQLDelight itself, because the compiler, its dialect and the Kotlin plugin
all live on Maven Central — only the Android Gradle Plugin and the jitpack
dependencies do not. So schema and migration changes under
`data/src/main/sqldelight` can be verified in full:

```sh
.claude/tools/verify_sqldelight_gradle.sh         # the SQLDelight compiler and migration verification
python3 .claude/tools/verify_sqldelight.py        # backfill, triggers, recalculation, merge, divergence
python3 .claude/tools/verify_migration_schema.py  # fresh install vs. post-migration schema
```

The shell script builds a throwaway JVM-only Gradle project that points the
real compiler at the real source directory, reading the SQLDelight version,
dialect and package name out of the build files rather than repeating them. It
runs the parser and type checker over every `.sq` — the same work as
`:data:generateDebugDatabaseInterface` — and then verifies the migrations: it
generates the schema of the revision before the newest migration was added,
applies what came after it and diffs the result against the schema the `.sq`
files declare. This project checks no `.db` schema files in, which is why the
baseline is generated from git instead of read from the tree.

The Python scripts exercise the SQLite *engine* instead, so they cover what the
compiler cannot: that the triggers fire, that the backfill produces the numbers
it should, that recalculation is idempotent, that a merged `started_at` survives
it, and that the divergence check sees a write the triggers did not. They are
also much faster, so they are the ones to iterate with; the compiler is stricter
about syntax and has the final word. `WHERE true`, for instance, is valid SQLite and valid to
those scripts, but SQLDelight's grammar has no boolean literal and reads it as
a column name.

None of this compiles Kotlin: every module depends on the Android Gradle
Plugin, so generated interfaces, mappers and interactors can be written here
but not built. Say so when reporting work that has not been compiled.
