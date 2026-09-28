"""Measure what reading libraryView off manga_progress buys.

The library flow re-runs libraryView on every emission, so its cost is paid on
every write that touches a manga, a chapter or the history -- page reads
included. This builds a synthetic library of the size a heavy user has, then
times the view the baseline revision declared against the one in the tree, on
the same data and the same engine, after checking that they return the same
rows.

It is a query-shape benchmark, not a device benchmark: Android ships the same
engine on a slower CPU and slower storage, so the ratio carries over and the
absolute numbers do not.

Usage: python3 .claude/tools/benchmark_library_view.py [--baseline <rev>]
"""
import os
import random
import re
import statistics
import subprocess
import sqlite3
import sys
import time

ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()
SQ = f"{ROOT}/data/src/main/sqldelight/tachiyomi/data"
VIEW = "data/src/main/sqldelight/tachiyomi/view/libraryView.sq"
MIGRATIONS = f"{ROOT}/data/src/main/sqldelight/tachiyomi/migrations"

TYPE_ANNOTATIONS = [
    " AS Boolean", " AS Date", " AS List<String>", " AS JsonObject",
    " AS UpdateStrategy",
]
LABEL = re.compile(r"^([a-zA-Z][a-zA-Z0-9_]*):$", re.M)

MANGA_COUNT = int(os.environ.get("BENCH_MANGA", 1200))
CHAPTERS_PER_MANGA = int(os.environ.get("BENCH_CHAPTERS", 140))
RUNS = int(os.environ.get("BENCH_RUNS", 15))

BASELINE = None
args = sys.argv[1:]
if args[:1] == ["--baseline"]:
    BASELINE = args[1]


def clean(sql):
    sql = re.sub(r"^import .*;$", "", sql, flags=re.M)
    for ann in TYPE_ANNOTATIONS:
        sql = sql.replace(ann, "")
    return sql


def ddl_only(sql):
    """Everything before the first labelled query."""
    sql = clean(sql)
    m = LABEL.search(sql)
    return sql[: m.start()] if m else sql


def git(*argv):
    return subprocess.run(
        ["git", "-C", ROOT, *argv], capture_output=True, text=True, check=True
    ).stdout


if BASELINE is None:
    # The revision the newest migration upgrades, the same baseline the two
    # verification scripts use, which is where the previous view is declared.
    sys.dont_write_bytecode = True
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from migration_baseline import baseline

    BASELINE = baseline()

def view_body(sql):
    """The SELECT the view wraps, with the leading comments and CREATE dropped."""
    body = ddl_only(sql).strip().rstrip(";")
    m = re.search(r"CREATE VIEW libraryView AS", body)
    return body[m.end():].strip()


before = view_body(git("show", f"{BASELINE}:{VIEW}"))
after = view_body(open(f"{ROOT}/{VIEW}").read())
if before == after:
    print(f"libraryView is unchanged since {BASELINE}; nothing to compare")
    sys.exit(0)

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
for f in ("mangas.sq", "chapters.sq", "history.sq", "excluded_scanlators.sq",
          "categories.sq", "mangas_categories.sq"):
    db.executescript(ddl_only(open(f"{SQ}/{f}").read()))

# --- data ----------------------------------------------------------------
rnd = random.Random(20260903)
now_ms = int(time.time() * 1000)
day = 86_400_000

print(f"populating {MANGA_COUNT} manga x {CHAPTERS_PER_MANGA} chapters", flush=True)
db.execute("INSERT INTO categories(_id, name, sort, flags) VALUES (1, 'Reading', 0, 0)")
db.execute("INSERT INTO categories(_id, name, sort, flags) VALUES (2, 'Done', 1, 0)")

chapter_id = 0
for mid in range(1, MANGA_COUNT + 1):
    # A tenth are not in the library. The view filters them out and the aggregate
    # it used to join did not, which is part of what is being measured.
    favorite_at = None if mid % 10 == 0 else now_ms
    db.execute(
        "INSERT INTO mangas(_id, source, url, title, status, favorite_at, initialized,"
        " viewer, chapter_flags, cover_last_modified)"
        " VALUES (?, 1, ?, ?, 0, ?, 1, 0, 0, 0)",
        (mid, f"/manga/{mid}", f"Manga {mid}", favorite_at),
    )
    db.execute(
        "INSERT INTO mangas_categories(manga_id, category_id) VALUES (?, ?)",
        (mid, 1 if mid % 3 else 2),
    )
    if mid % 25 == 0:
        db.execute(
            "INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (?, 'Group B')",
            (mid,),
        )
    read_upto = int(CHAPTERS_PER_MANGA * rnd.uniform(0, 1))
    for n in range(1, CHAPTERS_PER_MANGA + 1):
        chapter_id += 1
        read = 1 if n <= read_upto else 0
        db.execute(
            "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark,"
            " last_page_read, chapter_number, source_order, date_fetch, date_upload)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (chapter_id, mid, f"/c/{chapter_id}", f"Ch. {n}",
             "Group A" if n % 4 else "Group B", read, 1 if n % 40 == 0 else 0,
             rnd.randint(0, 20) if read else 0, float(n), n,
             now_ms - (CHAPTERS_PER_MANGA - n) * day,
             now_ms - (CHAPTERS_PER_MANGA - n) * day),
        )
        if read:
            db.execute(
                "INSERT INTO history(chapter_id, last_read, time_read) VALUES (?, ?, ?)",
                (chapter_id, now_ms - rnd.randint(0, 400) * day, rnd.randint(30, 3000) * 1000),
            )
db.commit()

# manga_progress, created and filled the way the migration does it
db.executescript(ddl_only(open(f"{SQ}/manga_progress.sq").read()))
sql = clean(open(f"{SQ}/manga_progress.sq").read())
m = re.search(r"^recalculateForManga:$", sql, re.M)
rest = sql[m.end():]
nxt = LABEL.search(rest)
recalc = (rest[: nxt.start()] if nxt else rest).strip().rstrip(";")
for (mid,) in db.execute("SELECT _id FROM mangas").fetchall():
    db.execute(recalc.replace(":mangaId", "?"), (mid,) * recalc.count(":mangaId"))
db.commit()
db.execute("ANALYZE")

# --- agreement ------------------------------------------------------------
COLUMNS = "_id, totalCount, readCount, latestUpload, chapterFetchedAt, lastRead, bookmarkCount"


def rows(sql):
    return {r[0]: tuple(float(v) for v in r[1:]) for r in db.execute(f"SELECT {COLUMNS} FROM ({sql})")}


old_rows, new_rows = rows(before), rows(after)
assert old_rows.keys() == new_rows.keys(), "the two views return different manga"
disagreeing = [k for k in old_rows if old_rows[k] != new_rows[k]]
assert not disagreeing, (
    f"{len(disagreeing)} rows disagree, e.g. manga {disagreeing[0]}: "
    f"{old_rows[disagreeing[0]]} vs {new_rows[disagreeing[0]]}"
)
print(f"both views return the same {len(old_rows)} rows and the same numbers\n")


# --- timing ---------------------------------------------------------------
def timed(sql):
    samples = []
    for _ in range(RUNS):
        start = time.perf_counter()
        db.execute(sql).fetchall()
        samples.append((time.perf_counter() - start) * 1000)
    return statistics.median(samples), min(samples)


print(f"{'libraryView':<44} {'median':>10} {'best':>10}")
old_median, old_best = timed(before)
new_median, new_best = timed(after)
print(f"{'at ' + BASELINE:<44} {old_median:>9.1f}ms {old_best:>9.1f}ms")
print(f"{'in the tree':<44} {new_median:>9.1f}ms {new_best:>9.1f}ms")
print(f"\n{old_median / new_median:.1f}x faster")
