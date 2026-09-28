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

Track A is done. `manga_progress`, its queries and migration `17.sqm` are in
the tree, and so are the `MangaProgress` model and the `MangaProgressRepository`
interface in `domain`, the implementation and mapper in `data`, and the
`GetMangaProgress` and `RecalculateMangaProgress` interactors. `libraryView`
reads its counts off the table rather than aggregating `chapters`,
`MangaProgressMaintainer` rebuilds the rows the triggers flag, and the
measurement A3 asked for is `.claude/tools/benchmark_library_view.py`: 22x on a
synthetic library of 1200 manga.

A4 closed the loop between devices. `SyncMerger` holds the merge rules for the
sync to reuse; the backup carries `started_at` and `completed_at` as
`BackupManga.progress`; and `LibraryUpdateJob` reconciles rows no trigger
flagged.

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

The list entry has an icon of its own: `cloud_sync.svg` under
`icons/material-symbols/src/main/valkyrieResources/rounded`, taken from Google's
Material Symbols like every other icon in the pack, which Valkyrie turns into
`MaterialSymbols.Rounded.CloudSync` on sync. Its header comment names no version,
unlike its neighbours: `fonts.google.com`'s metadata endpoint is blocked here,
and inventing a number is worse than omitting one.

Upstream was merged in on 2026-09-28, and two of its changes reshaped track A:

- *Remove unused sync scaffold* dropped `version`, `is_syncing` and
  `last_modified_at` from `mangas` and `chapters`, their triggers, and the
  matching backup fields, which upstream now calls "artifacts of the abandoned
  sync attempt" and keeps reserved (`BackupChapter` 11 and 12, `BackupManga`
  106 and 109 — never reuse them). The progress triggers were guarded on
  `mangas.is_syncing` and the bookmark rule compared `chapters.version`; see the
  invariants below for what replaced each.
- Upstream took migrations `15.sqm` and `16.sqm` — the drop above, and
  `favorite`, `date_added` and `favorite_modified_at` folded into
  `favorite_at`. The fork's migration became `17.sqm`, written against the
  schema those two leave. The old fork `15.sqm` never reached an installation,
  so no install is left on a schema this numbering skips; a development build
  that did carry it has to be reinstalled or have its data cleared.
- The restore moved into `RestoreRepositoryImpl`, in `data`, all of it in one
  transaction per batch. The fork leaves that file exactly as upstream ships it
  and hooks in from `MangaRestorer`, in `app`, through the per-entry callback
  `restoreManga` already takes.

B2 and the plan's track C are both written and neither is merged:
`claude/procedi-con-b2-4mkmjg` and `claude/sql-server-coolify-setup-m22x35`,
branched before the upstream merge. B2 needs adapting on the way in, not only
merging: its bookmark rule is last-write-wins on a per-chapter `version` that no
longer exists anywhere, and its `SyncManga.lastModifiedAt` is documented in
seconds. The wire format can keep `SyncChapter.version`, but the number has to
come from a counter the fork owns — a trigger-maintained column on a fork table,
never one re-added to `chapters` — and that counter is B3's to build, with the
push that reads it.

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
  already at, so sixteen migrations produce `17.db`.
- The baseline is the revision the newest migration upgrades, and
  `.claude/tools/migration_baseline.py` is what finds it for all three tools:
  the parent of the commit that added migration N which already had N-1. For a
  migration added by a merge that is not the first parent — `17.sqm` arrived in
  the merge that brought upstream's 15 and 16, and it upgrades upstream's side.
- `verifyMigrations` stays off in `data/build.gradle.kts`. Migrations 1 to 14
  target the legacy Tachiyomi schema, so type-checking them against the current
  one reports well over a hundred failures at any revision, upstream included.
  The `.sq` files are type-checked either way.
- `17.sqm` has not reached any installation yet, so a correction to the table
  it creates still belongs in that file, edited together with the `.sq`. Once a
  build carries it, the same correction costs an `18.sqm` doing `DROP` and
  `CREATE`.
- Upstream adds migrations too. When a merge brings one numbered like an
  unreleased fork migration, the fork's moves past it, as `15.sqm` became
  `17.sqm`; upstream's numbering is the one real installations already have.
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
  place. Each trigger's `WHEN` guard checks only that the parent manga still
  exists, which makes cascading deletes a no-op instead of a foreign-key
  failure. The guards used to read `mangas.is_syncing` so that a restore would
  not flag what it wrote; with that column gone the restore flags like any other
  write, which costs one primary-key upsert per write and nothing more.
- `started_at` and `completed_at` are the only columns not derivable from
  `chapters` and `history`. Both are monotonic: recalculation only moves them
  earlier, and `completed_at` is cleared when a new chapter drops the manga
  back below 100%.
- Timestamps are epoch milliseconds, `last_modified_at` included, matching
  `history.last_read`. It used to be in seconds to match the identically named
  columns on `mangas` and `chapters`, which no longer exist.
- The chapter trigger lists every column a stored value depends on: `read`,
  `bookmark`, `last_page_read`, `manga_id`, `scanlator`, `date_upload` and
  `date_fetch`. SQLite fires an `UPDATE OF` trigger on the columns a statement
  names in its `SET` clause, not on the ones whose value changes, so today's
  general `UPDATE` in `chapters.sq` fires it whatever it writes; the list is
  what keeps a narrower statement, such as the restore's `updateFromBackup`,
  from slipping past. Adding a column the table
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
- A backup restore goes through the triggers like any other write, so what it
  touched is flagged, and `BackupRestorer` calls
  `RecalculateMangaProgress.awaitStale` when it finishes so the library shows
  the restored counts by the time the restore reports itself done. It used to
  need `awaitAll` and an explicit `is_syncing` reset, because the restore
  suppressed the triggers; neither is left to do.
- `recursive_triggers` stays at SQLite's default, off: `DatabaseBindings.kt`
  configures only `isForeignKeyConstraintsEnabled`. Nothing here depends on it
  either way — the staleness triggers write only to `manga_progress`, which has
  no triggers — but it used to matter: upstream's
  `update_last_modified_at_chapters` wrote to `chapters` from a trigger on
  `chapters`, and turning it on made a page read fail. That trigger went with
  the sync scaffold.
- The merge rules are written twice now, and that is a choice. `SyncMerger`
  has them for the sync: `read` and `bookmark` are ORs, `last_page_read` and
  both history columns are maxima, and `started_at`/`completed_at` take the
  earlier of the two, with null meaning "this device does not know" rather than
  "it did not happen". Upstream's `RestoreRepositoryImpl` has the same rules
  for chapters and history, inline — it adopted the maximum on
  `last_page_read` the fork had argued for. Routing the restore through
  `SyncMerger` again would mean editing a file upstream rewrites often, from a
  module (`data`) that cannot see `app`; so the restore calls `SyncMerger` only
  for the two progress dates, and an upstream merge that touches
  `restoreChapters` or `restoreHistory` is the moment to compare the two again.
  `bookmark` being an OR is a regression the fork accepts for now: a bookmark
  removed on one device comes back at the next merge, because the per-chapter
  counter that ordered them was upstream's and is gone. The sync gets it back
  with a counter of its own, in B3.
- A backup carries `started_at` and `completed_at` and nothing else of the
  table: `BackupMangaProgress` at `@ProtoNumber(113)`, written under the history
  option because that is what those two dates are. The rest of the row is
  derived, so the `awaitStale` at the end of the restore rebuilds it. The two
  dates are merged inside the restore's own transaction, from the callback
  `restoreManga` runs once an entry's chapters and history are in place. A backup
  written before the field existed decodes with `progress = null` and merges as
  a no-op, which `BackupMangaProgressTest` pins down against a message that
  genuinely lacks the field.
- `getDivergentMangaIds` is the consistency net phase A4 owed the plan: it
  recomputes the six columns `libraryView` reads and returns the manga whose
  stored row disagrees, including one with chapters and no row at all.
  `LibraryUpdateJob` rebuilds whatever it names. It is the one query that still
  pays the aggregate the table exists to avoid, which is why it runs once per
  library update and not on the library flow.
- `last_modified_at` and `version` exist but nothing writes them yet; track B
  is their first client. They are the only sync bookkeeping left in the
  schema, since upstream dropped the same pair from `mangas` and `chapters`, so
  what they have to mean changes: with no `chapters.last_modified_at` to select
  a delta on, this is the column that says which manga a push must carry, and
  it has to move when a chapter's `read`, `bookmark` or `last_page_read` or a
  history row changes, not only when `started_at` or `completed_at` do. It must
  still not move on a recalculation that changes nothing a sync carries, or
  every device marks every row modified on every pass. A push per manga, not
  per chapter, is also what the server's one row per `(account, device, source,
  url)` needs: a row overwritten with a partial chapter list would lose the
  rest.
- Sync bookkeeping lives on tables the fork owns, never on upstream's. Progress
  was kept off `mangas` for a reason that no longer applies — its `AFTER
  UPDATE` trigger would have bumped `last_modified_at` on every page read — and
  a better one replaced it: upstream has just shown it will remove columns it
  does not use, and a fork column on an upstream table is a conflict at every
  merge that touches it.

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
