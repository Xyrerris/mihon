"""Exercise the manga_progress schema, the migration's backfill, staleness triggers,
the merge facts and the divergence check against a real SQLite engine, using the
project's own DDL as the starting point."""
import os
import re
import subprocess
import sqlite3
import sys
import time

ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()
SQ = f"{ROOT}/data/src/main/sqldelight/tachiyomi/data"
MIG = f"{ROOT}/data/src/main/sqldelight/tachiyomi/migrations"

TYPE_ANNOTATIONS = [
    " AS Boolean", " AS Date", " AS List<String>", " AS JsonObject",
    " AS UpdateStrategy",
]
LABEL = re.compile(r"^([a-zA-Z][a-zA-Z0-9_]*):$", re.M)


def clean(sql):
    sql = re.sub(r"^import .*;$", "", sql, flags=re.M)
    for ann in TYPE_ANNOTATIONS:
        sql = sql.replace(ann, "")
    return sql


def ddl_only(path):
    """Everything before the first labelled query."""
    sql = clean(open(path).read())
    m = LABEL.search(sql)
    return sql[: m.start()] if m else sql


def named_query(path, label):
    sql = clean(open(path).read())
    m = re.search(rf"^{label}:$", sql, re.M)
    assert m, f"{label} not found in {path}"
    rest = sql[m.end():]
    nxt = LABEL.search(rest)
    return (rest[: nxt.start()] if nxt else rest).strip()


db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")

# --- pre-migration schema, straight from the repo -------------------------
for f in ("mangas.sq", "chapters.sq", "history.sq", "excluded_scanlators.sq"):
    db.executescript(ddl_only(f"{SQ}/{f}"))

NOW_MS = int(time.time() * 1000)
DAY = 86_400_000


def add_manga(mid, title):
    db.execute(
        "INSERT INTO mangas(_id, source, url, title, status, favorite_at, initialized,"
        " viewer, chapter_flags, cover_last_modified)"
        " VALUES (?, 1, ?, ?, 0, ?, 1, 0, 0, 0)",
        (mid, f"/m/{mid}", title, NOW_MS),
    )


def add_chapter(cid, mid, num, read, page=0, scanlator=None, bookmark=0, upload=0, fetch=0):
    db.execute(
        "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark,"
        " last_page_read, chapter_number, source_order, date_fetch, date_upload)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
        (cid, mid, f"/c/{cid}", f"Ch {num}", scanlator, read, bookmark, page, num, fetch, upload),
    )


def add_history(cid, last_read, time_read=1000):
    db.execute(
        "INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, ?)",
        (cid, last_read, time_read),
    )


# 1: excluded-scanlator chapter is read but must not count. Its bookmark and its
# dates are the newest of the four, so they also prove that the columns
# libraryView reads for the shelf honour the same exclusion.
add_manga(1, "Excluded scanlator")
add_chapter(101, 1, 1, 1, bookmark=1, upload=NOW_MS - 40 * DAY, fetch=NOW_MS - 39 * DAY)
add_chapter(102, 1, 2, 1, upload=NOW_MS - 30 * DAY, fetch=NOW_MS - 29 * DAY)
add_chapter(103, 1, 3, 0, bookmark=1, upload=NOW_MS - 20 * DAY, fetch=NOW_MS - 19 * DAY)
add_chapter(
    104, 1, 4, 1, scanlator="BadGroup",
    bookmark=1, upload=NOW_MS - 10 * DAY, fetch=NOW_MS - 9 * DAY,
)
db.execute("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'BadGroup')")
add_history(101, NOW_MS - 5 * DAY, 500)
add_history(102, NOW_MS - 2 * DAY, 700)   # most recent -> resume point
add_history(104, NOW_MS - 1 * DAY, 900)   # excluded, must be ignored entirely

# 2: fully read -> completed_at
add_manga(2, "Completed")
for i, cid in enumerate((201, 202, 203), start=1):
    add_chapter(cid, 2, i, 1)
    add_history(cid, NOW_MS - (4 - i) * DAY)

# 3: no chapters at all
add_manga(3, "Empty")

# 4: chapters, nothing read
add_manga(4, "Untouched")
add_chapter(401, 4, 1, 0)
add_chapter(402, 4, 2, 0)

# 5: marked read without ever opening the reader (no history rows)
add_manga(5, "Marked read only")
add_chapter(501, 5, 1, 1)
add_chapter(502, 5, 2, 0)

# 6: history reset to 0 must not act as a resume point
add_manga(6, "Reset history")
add_chapter(601, 6, 1, 1, page=7)
add_history(601, 0, 250)

db.commit()

# --- apply the migration that creates manga_progress ---------------------
# The newest one, which is what an existing install runs last. The .sq files
# above are the schema it upgrades: upstream's migrations before it have
# already been folded into them.
NEWEST = max(int(f[:-4]) for f in os.listdir(MIG) if f.endswith(".sqm"))
db.executescript(clean(open(f"{MIG}/{NEWEST}.sqm").read()))
db.commit()
print(f"migration {NEWEST} applied\n")

failures = []


def check(name, got, want):
    if got != want:
        failures.append(f"{name}: got {got!r}, want {want!r}")
        print(f"  FAIL {name}: got {got!r}, want {want!r}")
    else:
        print(f"  ok   {name} = {got!r}")


def row(mid):
    db.row_factory = sqlite3.Row
    r = db.execute("SELECT * FROM manga_progress WHERE manga_id = ?", (mid,)).fetchone()
    db.row_factory = None
    return r


print("backfill:")
check("count of rows", db.execute("SELECT count(*) FROM manga_progress").fetchone()[0], 6)

r = row(1)
check("m1 total (excludes BadGroup)", r["total_chapter_count"], 3)
check("m1 read (excludes BadGroup)", r["read_chapter_count"], 2)
check("m1 percent", round(r["progress_percent"], 4), round(2 / 3, 4))
check("m1 resume chapter", r["last_read_chapter_id"], 102)
check("m1 last_read_at", r["last_read_at"], NOW_MS - 2 * DAY)
check("m1 started_at", r["started_at"], NOW_MS - 5 * DAY)
check("m1 duration (excludes BadGroup)", r["total_read_duration"], 1200)
check("m1 completed_at", r["completed_at"], None)
check("m1 is_stale", r["is_stale"], 0)
check("m1 bookmarks (excludes BadGroup)", r["bookmarked_chapter_count"], 2)
check("m1 latest_upload_at (excludes BadGroup)", r["latest_upload_at"], NOW_MS - 20 * DAY)
check("m1 latest_fetch_at (excludes BadGroup)", r["latest_fetch_at"], NOW_MS - 19 * DAY)

r = row(2)
check("m2 percent", r["progress_percent"], 1.0)
check("m2 completed_at", r["completed_at"], NOW_MS - DAY)

r = row(3)
check("m3 total", r["total_chapter_count"], 0)
check("m3 percent", r["progress_percent"], 0.0)
check("m3 started_at", r["started_at"], None)
check("m3 completed_at", r["completed_at"], None)
check("m3 bookmarks", r["bookmarked_chapter_count"], 0)
check("m3 latest_upload_at", r["latest_upload_at"], 0)
check("m3 latest_fetch_at", r["latest_fetch_at"], 0)

r = row(4)
check("m4 started_at", r["started_at"], None)
check("m4 percent", r["progress_percent"], 0.0)

r = row(5)
check("m5 read", r["read_chapter_count"], 1)
check("m5 started_at falls back to now", abs(r["started_at"] - NOW_MS) < 60_000, True)

r = row(6)
check("m6 resume chapter (reset history ignored)", r["last_read_chapter_id"], None)
check("m6 last_read_at (reset history ignored)", r["last_read_at"], None)
check("m6 completed_at falls back to now", abs(r["completed_at"] - NOW_MS) < 60_000, True)

# --- triggers -------------------------------------------------------------
print("\nstaleness triggers:")


def stale(mid):
    return db.execute("SELECT is_stale FROM manga_progress WHERE manga_id = ?", (mid,)).fetchone()[0]


db.execute("UPDATE chapters SET read = 1 WHERE _id = 103")
check("chapter read update marks stale", stale(1), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("INSERT INTO history(chapter_id, last_read, time_read) VALUES (401, ?, 100)", (NOW_MS,))
check("history insert marks stale", stale(4), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("UPDATE history SET last_read = ? WHERE chapter_id = 401", (NOW_MS + 10,))
check("history update marks stale", stale(4), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("DELETE FROM excluded_scanlators WHERE manga_id = 1")
check("excluded scanlator delete marks stale", stale(1), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
add_chapter(403, 4, 3, 0)
check("chapter insert marks stale", stale(4), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("DELETE FROM chapters WHERE _id = 403")
check("chapter delete marks stale", stale(4), 1)

# scanlator decides whether excluded_scanlators hides a chapter from the counts,
# and an UPDATE OF trigger keys off the columns a SET clause names rather than
# the ones that change value, so a statement writing scanlator alone must still
# flag the row
db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("UPDATE chapters SET scanlator = 'Solo' WHERE _id = 401")
check("scanlator-only update marks stale", stale(4), 1)

# same for the columns the shelf reads: bookmark, date_upload and date_fetch are
# stored on the row now, so a statement writing one of them alone has to flag it
db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("UPDATE chapters SET bookmark = 1 WHERE _id = 402")
check("bookmark-only update marks stale", stale(4), 1)

db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("UPDATE chapters SET date_upload = ? WHERE _id = 402", (NOW_MS,))
check("date_upload-only update marks stale", stale(4), 1)

# the restore's own statements: it has no way to suppress the triggers any
# more, and relies on them to flag what it wrote
db.execute("UPDATE manga_progress SET is_stale = 0")
RESTORE_CHAPTER = (
    named_query(f"{SQ}/chapters.sq", "updateFromBackup")
    .replace(":read", "?").replace(":bookmark", "?").replace(":lastPageRead", "?")
    .replace(":memo", "?").replace(":chapterId", "?")
)
db.execute(RESTORE_CHAPTER, (0, 0, 3, "{}", 201))
check("the restore's chapter update marks stale", stale(2), 1)

# cascading manga delete must not trip the chapters FK
db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("DELETE FROM mangas WHERE _id = 6")
check("manga delete cascades cleanly", db.execute("SELECT count(*) FROM manga_progress").fetchone()[0], 5)

# --- recalculateForManga --------------------------------------------------
print("\nrecalculateForManga:")
RECALC = named_query(f"{SQ}/manga_progress.sq", "recalculateForManga").replace(":mangaId", "?")
NPARAMS = RECALC.count("?")


def recalc(mid):
    db.execute(RECALC, (mid,) * NPARAMS)


recalc(1)
r = row(1)
check("m1 recalc total (scanlator filter removed)", r["total_chapter_count"], 4)
check("m1 recalc read", r["read_chapter_count"], 4)
check("m1 recalc percent", r["progress_percent"], 1.0)
check("m1 recalc resume chapter", r["last_read_chapter_id"], 104)
check("m1 recalc clears stale", r["is_stale"], 0)
check("m1 recalc keeps original started_at", r["started_at"], NOW_MS - 5 * DAY)
# the exclusion was lifted above, so BadGroup's bookmark and dates now count
check("m1 recalc bookmarks (filter removed)", r["bookmarked_chapter_count"], 3)
check("m1 recalc latest_upload_at (filter removed)", r["latest_upload_at"], NOW_MS - 10 * DAY)
check("m1 recalc latest_fetch_at (filter removed)", r["latest_fetch_at"], NOW_MS - 9 * DAY)

first_completed = r["completed_at"]
recalc(1)
check("recalc is idempotent (completed_at)", row(1)["completed_at"], first_completed)
check("recalc is idempotent (started_at)", row(1)["started_at"], NOW_MS - 5 * DAY)

# started_at must never move forward
db.execute("UPDATE manga_progress SET started_at = ? WHERE manga_id = 1", (NOW_MS - 99 * DAY,))
recalc(1)
check("started_at only moves earlier", row(1)["started_at"], NOW_MS - 99 * DAY)

# a new chapter drops it below 100% and clears completed_at
add_chapter(105, 1, 5, 0)
recalc(1)
check("new chapter clears completed_at", row(1)["completed_at"], None)
check("new chapter lowers percent", round(row(1)["progress_percent"], 4), 0.8)

# reading it again re-completes
db.execute("UPDATE chapters SET read = 1 WHERE _id = 105")
recalc(1)
check("re-reading restores completed_at", row(1)["completed_at"] is not None, True)

# recalc on a manga with no row yet (insert path)
add_manga(7, "Fresh")
add_chapter(701, 7, 1, 1)
db.execute("DELETE FROM manga_progress WHERE manga_id = 7")
recalc(7)
check("recalc inserts a missing row", row(7)["read_chapter_count"], 1)

# deleting the resume chapter must null the reference, not raise a FK error
resume = row(1)["last_read_chapter_id"]
check("m1 has a resume chapter to delete", resume is not None, True)
db.execute("DELETE FROM chapters WHERE _id = ?", (resume,))
check("resume chapter FK is set to NULL", row(1)["last_read_chapter_id"], None)
check("deleting the resume chapter marks stale", row(1)["is_stale"], 1)
recalc(1)
check("recalc picks a new resume chapter", row(1)["last_read_chapter_id"] != resume, True)

# --- progress facts, the merge write --------------------------------------
print("\nprogress facts:")
FACTS = "SELECT started_at, completed_at FROM manga_progress WHERE manga_id = ?"
UPSERT_FACTS = (
    named_query(f"{SQ}/manga_progress.sq", "upsertProgressFacts")
    .replace(":mangaId", "?")
    .replace(":startedAt", "?")
    .replace(":completedAt", "?")
)


def facts(mid):
    return db.execute(FACTS, (mid,)).fetchone()


def set_facts(mid, started, completed):
    db.execute(UPSERT_FACTS, (mid, started, completed))


started, completed = facts(7)
check("m7 has both facts to merge against", (started is not None, completed is not None), (True, True))

# a backup from a device that started the manga a month earlier
earlier_start = started - 30 * DAY
set_facts(7, earlier_start, completed)
check("merged started_at is stored", facts(7)[0], earlier_start)
check("writing facts flags the row", stale(7), 1)
recalc(7)
check("recalculation keeps the merged started_at", facts(7)[0], earlier_start)
check("recalculation clears the flag", stale(7), 0)

# and one that finished it earlier
earlier_completed = completed - 10 * DAY
set_facts(7, earlier_start, earlier_completed)
recalc(7)
check("recalculation keeps the merged completed_at", facts(7)[1], earlier_completed)

# the insert path: a manga whose row does not exist yet
db.execute("DELETE FROM manga_progress WHERE manga_id = 7")
set_facts(7, earlier_start, None)
check("facts on a manga with no row insert one", facts(7), (earlier_start, None))
check("the inserted row is flagged", stale(7), 1)
recalc(7)

# a backup claiming a manga this device has not finished: the recalculation
# clears completed_at, the same way a new chapter does
set_facts(4, None, NOW_MS - 50 * DAY)
recalc(4)
check("completed_at the chapter list does not back is cleared", facts(4)[1], None)

# --- getDivergentMangaIds -------------------------------------------------
print("\ngetDivergentMangaIds:")
DIVERGENT = named_query(f"{SQ}/manga_progress.sq", "getDivergentMangaIds")


def divergent():
    return sorted(r[0] for r in db.execute(DIVERGENT).fetchall())


for mid in [r[0] for r in db.execute("SELECT _id FROM mangas").fetchall()]:
    recalc(mid)
check("nothing diverges once every row is recalculated", divergent(), [])


def unseen_write(mid, statement, params=()):
    """A write the triggers do not see, which is what the check exists to catch.

    Nothing in the schema lets a statement skip the triggers any more, so the
    write happens and the flag it raised is cleared behind it, which leaves the
    row in the state such a write would."""
    db.execute(statement, params)
    db.execute("UPDATE manga_progress SET is_stale = 0 WHERE manga_id = ?", (mid,))


unseen_write(4, "UPDATE chapters SET read = 1 WHERE _id = 401")
check("a read no trigger saw diverges", divergent(), [4])
check("and nothing flagged the row", stale(4), 0)
recalc(4)
check("recalculating it settles the divergence", divergent(), [])

unseen_write(5, "UPDATE chapters SET bookmark = 1 WHERE _id = 502")
check("a bookmark no trigger saw diverges", divergent(), [5])
recalc(5)

unseen_write(5, "UPDATE chapters SET date_upload = ? WHERE _id = 502", (NOW_MS,))
check("an upload date no trigger saw diverges", divergent(), [5])
recalc(5)

unseen_write(5, "INSERT INTO history(chapter_id, last_read, time_read) VALUES (502, ?, 10)", (NOW_MS,))
check("a read date no trigger saw diverges", divergent(), [5])
recalc(5)

db.execute("DELETE FROM manga_progress WHERE manga_id = 5")
check("a manga with chapters and no row diverges", divergent(), [5])
recalc(5)
check("recalculation creates the missing row", divergent(), [])

db.execute("DELETE FROM manga_progress WHERE manga_id = 3")
check("a manga with no chapters and no row does not", divergent(), [])

db.commit()
print()
if failures:
    print(f"{len(failures)} FAILURE(S)")
    sys.exit(1)
print("all checks passed")
