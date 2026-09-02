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

Schema and migration changes under `data/src/main/sqldelight` can be verified
without Gradle, using the SQLite build that ships with Python:

```sh
python3 .claude/tools/verify_sqldelight.py        # fixtures, backfill, triggers, recalculation
python3 .claude/tools/verify_migration_schema.py  # fresh install vs. post-migration schema
```

The second one is the cheap stand-in for SQLDelight's own migration
verification: it builds the schema both ways and compares `sqlite_master`.
Note that these exercise the SQLite *engine*, not SQLDelight's parser, which is
stricter — a `.sq` file that passes here can still be rejected by
`./gradlew :data:generateDebugDatabaseInterface`. Run that before considering
a schema change finished.
