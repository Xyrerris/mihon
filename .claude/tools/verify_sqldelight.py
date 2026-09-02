"""Exercise the manga_progress schema, migration 15 backfill and staleness triggers
against a real SQLite engine, using the project's own DDL as the starting point."""
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
        "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized,"
        " viewer, chapter_flags, cover_last_modified, date_added)"
        " VALUES (?, 1, ?, ?, 0, 1, 1, 0, 0, 0, 0)",
        (mid, f"/m/{mid}", title),
    )


def add_chapter(cid, mid, num, read, page=0, scanlator=None):
    db.execute(
        "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark,"
        " last_page_read, chapter_number, source_order, date_fetch, date_upload)"
        " VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 0, 0, 0)",
        (cid, mid, f"/c/{cid}", f"Ch {num}", scanlator, read, page, num),
    )


def add_history(cid, last_read, time_read=1000):
    db.execute(
        "INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, ?)",
        (cid, last_read, time_read),
    )


# 1: excluded-scanlator chapter is read but must not count
add_manga(1, "Excluded scanlator")
add_chapter(101, 1, 1, 1)
add_chapter(102, 1, 2, 1)
add_chapter(103, 1, 3, 0)
add_chapter(104, 1, 4, 1, scanlator="BadGroup")
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

# --- apply migration 15 ---------------------------------------------------
db.executescript(clean(open(f"{MIG}/15.sqm").read()))
db.commit()
print("migration 15 applied\n")

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

r = row(2)
check("m2 percent", r["progress_percent"], 1.0)
check("m2 completed_at", r["completed_at"], NOW_MS - DAY)

r = row(3)
check("m3 total", r["total_chapter_count"], 0)
check("m3 percent", r["progress_percent"], 0.0)
check("m3 started_at", r["started_at"], None)
check("m3 completed_at", r["completed_at"], None)

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

# is_syncing guard: bulk restore must not thrash the table
db.execute("UPDATE manga_progress SET is_stale = 0")
db.execute("UPDATE mangas SET is_syncing = 1 WHERE _id = 2")
db.execute("UPDATE chapters SET read = 0 WHERE _id = 201")
check("is_syncing suppresses the trigger", stale(2), 0)
db.execute("UPDATE mangas SET is_syncing = 0 WHERE _id = 2")

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

db.commit()
print()
if failures:
    print(f"{len(failures)} FAILURE(S)")
    sys.exit(1)
print("all checks passed")
