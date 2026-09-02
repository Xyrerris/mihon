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
Allow the missing hosts on the environment — see the network access section of
<https://code.claude.com/docs/en/claude-code-on-the-web> — then start a new
session. This is an environment setting, not something a session can change
from the inside, and the egress proxy correctly refuses attempts to route
around it.

## Working without the toolchain

The two hosts the toolchain needs are the only ones usually blocked;
`repo1.maven.org` and `services.gradle.org` normally answer. That is enough to
run SQLDelight itself, because the compiler, its dialect and the Kotlin plugin
all live on Maven Central — only the Android Gradle Plugin and the jitpack
dependencies do not. So schema and migration changes under
`data/src/main/sqldelight` can be verified in full:

```sh
.claude/tools/verify_sqldelight_gradle.sh         # the SQLDelight compiler and migration verification
python3 .claude/tools/verify_sqldelight.py        # fixtures, backfill, triggers, recalculation
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
it should, and that recalculation is idempotent. They are also much faster, so
they are the ones to iterate with; the compiler is stricter about syntax and
has the final word. `WHERE true`, for instance, is valid SQLite and valid to
those scripts, but SQLDelight's grammar has no boolean literal and reads it as
a column name.

None of this compiles Kotlin: every module depends on the Android Gradle
Plugin, so generated interfaces, mappers and interactors can be written here
but not built. Say so when reporting work that has not been compiled.
