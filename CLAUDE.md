# CLAUDE.md

Notes for Claude Code sessions in this fork. Everything here is specific to it;
for anything not covered, follow the surrounding code and `CONTRIBUTING.md`.

## The work in flight

This fork tracks upstream mihon closely and carries one feature: persisted
per-manga reading progress, and later the online sync of it. The plan lives
outside the repository, as an artifact:

<https://claude.ai/code/artifact/ac4f6067-42dc-49a7-a38c-8ca6f5e1e587>

It is the source of truth for what to build and in which order — read it before
starting a phase. It is written in Italian and splits the work into track A
(local persistence, phases A1 to A4) and track B (online sync, B1 to B4), with
the progress UI left out of both.

Track A is done. `manga_progress`, its queries and migration `15.sqm` are in
the tree, and so are the `MangaProgress` model and the `MangaProgressRepository`
interface in `domain`, the implementation and mapper in `data`, and the
`GetMangaProgress` and `RecalculateMangaProgress` interactors. `libraryView`
reads its counts off the table rather than aggregating `chapters`,
`MangaProgressMaintainer` rebuilds the rows the triggers flag, and the
measurement A3 asked for is `.claude/tools/benchmark_library_view.py`: 22x on a
synthetic library of 1200 manga.

A4 closed the loop between devices. `SyncMerger` is where the merge rules live
now, for the backup restore and for the sync that will reuse it; the backup
carries `started_at` and `completed_at` as `BackupManga.progress`;
`LibraryUpdateJob` reconciles rows no trigger flagged; and `BackupRestorer`
clears `mangas.is_syncing` on purpose rather than by side effect.

B1 is in: `SyncPreferences` under `domain/…/sync/service`, `SettingsSyncScreen`
listed as *Device sync* between *Data and storage* and *Security and privacy*,
and the strings for both. Nothing reads the preferences yet — that is the phase,
not an omission. Four choices worth knowing:

- The screen is *Device sync*, not *Sync*: *Tracking* already advertises
  "one-way progress sync", and `syncing_library` is upstream's string for a
  restore run as a sync. The class keeps the plan's name.
- The API key sits behind `Preference.privateKey`, so a shared `.tachibk` does
  not carry it, and the screen shows *Set* rather than the key itself.
- `isValidServerUrl` refuses anything but `https` with a host, and refuses an
  empty address too: it answers "can a sync run against this", which is the
  question B3's job asks, not "has the user finished typing".
- The plan's *Sync now* row and the last-sync timestamp behind it are not there.
  A button with no job behind it is worse than no button, so B3 adds both with
  the thing they trigger. `SyncState` is missing for the same reason.

The list entry borrows the `Public` icon: the pack has no cloud-sync glyph, and
adding one means adding an SVG under `icons/material-symbols` for Valkyrie to
generate from. Next is B2 — `SyncMerger` extended to the full rules, the
`SyncRequest`/`SyncResponse` models and the watermark, all pure and with no
network in it.

A3 needed three columns A1 had not planned for — `bookmarked_chapter_count`,
`latest_upload_at`, `latest_fetch_at`. Without them the view still had to group
the whole `chapters` table to find them, which bought 1.7x instead of 22x. They
are in the table for the library's sake, not because they are progress.

The user writes in Italian, so reply in Italian. Code, comments, commit
messages and documentation stay in English, like the rest of the repository.

## What runs in this environment, and what does not

Sessions on Claude Code on the web get a fresh container, and
`.claude/hooks/session-start.sh` provisions the Android SDK. It needs
`dl.google.com` and `www.jitpack.io`. On an environment whose allowlist carries
them the whole build works — `./gradlew :app:compileDebugKotlin`, spotless and
the unit tests included — and the hook exports
`MIHON_ANDROID_TOOLCHAIN=ready`. Where an egress policy blocks either host the
hook says which one and exports `MIHON_ANDROID_TOOLCHAIN=unavailable`. Check
that variable before promising anything that needs Gradle: without those hosts
there is no Android Gradle Plugin and no `sqldelight-androidx-driver`, so
**no Kotlin can be compiled** — it can be written, but say plainly that it was
not built.

The provisioning is not free: the first build downloads a few hundred MB and
takes minutes, so start it in the background and do something else meanwhile.
If the hook did not run in the shell, `local.properties` with `sdk.dir` is all
Gradle needs to find the SDK.

Maven Central and `services.gradle.org` answer even where the two hosts above
are blocked, which is enough to run SQLDelight itself. Schema work is therefore
verifiable in either environment:

```sh
.claude/tools/verify_sqldelight_gradle.sh         # SQLDelight compiler + migration verification
python3 .claude/tools/verify_sqldelight.py        # triggers, backfill, recalculation, merge, divergence
python3 .claude/tools/verify_migration_schema.py  # fresh install vs. post-migration schema
python3 .claude/tools/benchmark_library_view.py   # libraryView, before and after the change
```

Run the first three before committing a change under
`data/src/main/sqldelight`. The fourth is not a gate: it times the tree's
`libraryView` against the baseline revision's on a synthetic library, and checks
that the two return the same rows, so it is what to run when a change is
supposed to make the library cheaper.
`.claude/hooks/README.md` explains what each one covers and why the Python
scripts are not a substitute for the compiler.

## Schema changes

- Every DDL change is written twice: in the `.sq`, which is what a fresh
  install creates, and in a new numbered `.sqm`, which is what an existing
  install runs. The two must produce identical schemas; the verification script
  applies the migration to the previous revision's schema and diffs the result.
- No `.db` schema files are checked in, so the baseline for that diff is
  generated from git. SQLDelight names a schema file after the version it is
  already at, so fourteen migrations produce `15.db`.
- `verifyMigrations` stays off in `data/build.gradle.kts`. Migrations 1 to 14
  target the legacy Tachiyomi schema, so type-checking them against the current
  one reports well over a hundred failures at any revision, upstream included.
  The `.sq` files are type-checked either way.
- `15.sqm` has not reached any installation yet, so a correction to the table
  it creates still belongs in that file, edited together with the `.sq`. Once a
  build carries it, the same correction costs a `16.sqm` doing `DROP` and
  `CREATE`.
- `verify_sqldelight.py` builds its database from the migration, not from
  `manga_progress.sq`: it is the `.sqm` triggers that its checks exercise. The
  two files are held identical by `verify_migration_schema.py` and by the
  compiler, so a change to one has to be made in both to be tested at all.
- The compiler is stricter than SQLite. `WHERE true` is valid SQLite but has no
  boolean literal in SQLDelight's grammar, which reads it as a column name;
  write `WHERE 1 = 1`. That clause is not decoration — an `INSERT ... SELECT`
  needs one so the parser does not read a following `ON CONFLICT` as part of
  the join.

## `manga_progress` invariants

Keep these when extending the table, and read `manga_progress.sq` for the rest:

- The triggers only flag a row `is_stale`; they never recompute. Recomputation
  lives in `recalculateForManga`, so "progress" is defined in exactly one
  place. Each trigger's `WHEN` guard reads `is_syncing` through a subquery that
  yields `NULL` once the parent row is gone, which also makes cascading deletes
  a no-op.
- `started_at` and `completed_at` are the only columns not derivable from
  `chapters` and `history`. Both are monotonic: recalculation only moves them
  earlier, and `completed_at` is cleared when a new chapter drops the manga
  back below 100%.
- Timestamps are epoch milliseconds, matching `history.last_read`, except
  `last_modified_at`, which is in seconds to match the identically named
  columns on `mangas` and `chapters`.
- The chapter trigger lists every column a stored value depends on: `read`,
  `bookmark`, `last_page_read`, `manga_id`, `scanlator`, `date_upload` and
  `date_fetch`. SQLite fires an `UPDATE OF` trigger on the columns a statement
  names in its `SET` clause, not on the ones whose value changes, so today's
  single `UPDATE` in `chapters.sq` fires it whatever it writes; the list is what
  keeps a narrower statement from slipping past. Adding a column the table
  stores means adding it here too.
- `bookmarked_chapter_count`, `latest_upload_at` and `latest_fetch_at` are the
  three columns that are not progress. They are what `libraryView` needs beyond
  the reading columns, and leaving them out leaves the view grouping `chapters`
  anyway, which is the whole cost. Track B has no reason to sync them: they are
  derived from the chapter list, not from the reader.
- The library reads the stored values directly, so a row that stays flagged
  shows stale counts. `MangaProgressMaintainer` is what closes that window: it
  collects the flagged ids for the life of the process and rebuilds them,
  conflated so a burst costs one pass. It is started from `App.onCreate`, next
  to `widgetManager.init(scope)`.
- A backup restore writes chapters with `mangas.is_syncing` set, which
  suppresses the triggers, so `BackupRestorer` calls
  `RecalculateMangaProgress.awaitAll` when it finishes, and calls
  `mangasQueries.resetIsSyncing` just before it. The flag was already being
  cleared per manga, but only by accident: the fetch-interval update that ends
  each entry's restore happens to write `is_syncing = 0`. Every progress trigger
  is guarded on that column, so a manga left flagged is a manga whose progress
  silently stops being maintained — too much to hang on a side effect. The
  explicit reset matches only rows still flagged, and the one trigger it fires
  on those, `update_last_modified_at_mangas`, moves a timestamp the restore has
  already moved.
- `recursive_triggers` stays at SQLite's default, off: `AppBindings.kt`
  configures only `isForeignKeyConstraintsEnabled`. It has to stay off, and not
  because of this table — upstream's `update_last_modified_at_chapters` writes
  to `chapters` from an `AFTER UPDATE ON chapters` trigger, so turning it on
  makes an ordinary page read fail with "too many levels of trigger recursion",
  with `manga_progress` present or absent. The staleness triggers add no
  recursion of their own: they write only to `manga_progress`, which has no
  triggers.
- The merge rules are `SyncMerger`'s and nowhere else's: `read` is an OR,
  `last_page_read` and both history columns are maxima, `bookmark` is
  last-write-wins on `chapters.version`, and `started_at`/`completed_at` take
  the earlier of the two, with null meaning "this device does not know" rather
  than "it did not happen". The restore used to carry its own copy of these
  inline, and two of them were wrong for a merge: `bookmark` was an OR, so a
  bookmark removed on one device came back at the next restore, and
  `last_page_read` took the backup's value rather than the further of the two.
  Track B's sync is the second client, which is the reason they are a pure
  function with no database in sight.
- A backup carries `started_at` and `completed_at` and nothing else of the
  table: `BackupMangaProgress` at `@ProtoNumber(113)`, written under the history
  option because that is what those two dates are. The rest of the row is
  derived, so the `awaitAll` at the end of the restore rebuilds it. A backup
  written before the field existed decodes with `progress = null` and merges as
  a no-op, which `BackupMangaProgressTest` pins down against a message that
  genuinely lacks the field.
- `getDivergentMangaIds` is the consistency net phase A4 owed the plan: it
  recomputes the six columns `libraryView` reads and returns the manga whose
  stored row disagrees, including one with chapters and no row at all.
  `LibraryUpdateJob` rebuilds whatever it names. It is the one query that still
  pays the aggregate the table exists to avoid, which is why it runs once per
  library update and not on the library flow.
- `last_modified_at` and `version` exist but nothing writes them yet; they are
  the sync convention, and track B is their first client. When something does
  maintain them, bump them only when `started_at` or `completed_at` change —
  the facts a sync actually carries. Bumping them on every recalculation would
  mark every row modified on every device and defeat the `last_modified_at >
  since` delta the push is built on.
- Progress deliberately does not live on `mangas`: that table's `AFTER UPDATE`
  trigger would bump `last_modified_at` on every page read and disturb the
  version counter backup restore uses to resolve conflicts.

## Branches

- `main` mirrors upstream mihon and is never written to from here: no commit,
  no merge, no rebase, no pull request targeting it. Its worth is precisely
  that it stays untouched, so it can be trusted as the record of what upstream
  ships.
- `main-personal` is this fork's trunk and the repository's default branch.
  Everything the fork adds lives there, work branches start from it and go back
  into it, and a pull request targets it.
- Upstream arrives by bringing `main` up to date from upstream and merging
  `main` into `main-personal` — in that direction only.

## Conventions

- Develop on the `claude/<slug>` branch the session is given, commit there and
  push there. Open a pull request only when asked for one, and open it against
  `main-personal`.
- Commit messages: a short imperative subject, then prose explaining why the
  change looks the way it does and what was considered. `git log` has the
  register.
- Spotless only covers `*.kt` and `*.kts`; `.sq` and `.sqm` files are not
  formatted by it, so match the file you are editing.
