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

Phase A1 is done: `manga_progress`, its queries and migration `15.sqm` are in
the tree and verified with the tools below. Nothing reads the table yet, by
design. Next is A2 — the `MangaProgress` model, the repository interface in
`domain`, its implementation, mapper and interactors in `data` — which ends
with the project compiling and no consumers.

The user writes in Italian, so reply in Italian. Code, comments, commit
messages and documentation stay in English, like the rest of the repository.

## What runs in this environment, and what does not

Sessions on Claude Code on the web get a fresh container, and
`.claude/hooks/session-start.sh` provisions the Android SDK. It needs
`dl.google.com` and `www.jitpack.io`, which many egress policies block; when
they are blocked the hook says so and exports
`MIHON_ANDROID_TOOLCHAIN=unavailable`. Check that variable before promising
anything that needs Gradle: without those hosts there is no Android Gradle
Plugin and no `sqldelight-androidx-driver`, so `./gradlew`, spotless and the
unit tests are all out of reach, and **no Kotlin can be compiled** — it can be
written, but say plainly that it was not built.

Maven Central and `services.gradle.org` normally do answer, which is enough to
run SQLDelight itself. Schema work is therefore fully verifiable:

```sh
.claude/tools/verify_sqldelight_gradle.sh         # SQLDelight compiler + migration verification
python3 .claude/tools/verify_sqldelight.py        # triggers, backfill, recalculation
python3 .claude/tools/verify_migration_schema.py  # fresh install vs. post-migration schema
```

Run all three before committing a change under `data/src/main/sqldelight`.
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
- The chapter trigger lists every column that can move progress: `read`,
  `last_page_read`, `manga_id` and `scanlator`. SQLite fires an `UPDATE OF`
  trigger on the columns a statement names in its `SET` clause, not on the ones
  whose value changes, so today's single `UPDATE` in `chapters.sq` fires it
  whatever it writes; the list is what keeps a narrower statement from slipping
  past.
- `recursive_triggers` stays at SQLite's default, off: `AppBindings.kt`
  configures only `isForeignKeyConstraintsEnabled`. It has to stay off, and not
  because of this table — upstream's `update_last_modified_at_chapters` writes
  to `chapters` from an `AFTER UPDATE ON chapters` trigger, so turning it on
  makes an ordinary page read fail with "too many levels of trigger recursion",
  with `manga_progress` present or absent. The staleness triggers add no
  recursion of their own: they write only to `manga_progress`, which has no
  triggers.
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
