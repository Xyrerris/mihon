"""Compare the DDL as declared in the .sq files against the .sqm, the way
SQLDelight's migration verification does.

A fresh install runs the .sq files; an existing one runs the migration on top of
the schema the baseline revision declared. Both sides are built here and diffed,
which covers libraryView as well as manga_progress: the migration has to drop
and recreate the view, because SQLite stores a view's text verbatim and never
revisits it."""
import os
import re
import subprocess, sqlite3, sys

def clean(s):
    s = re.sub(r"^import .*;$", "", s, flags=re.M)
    return s.replace(" AS Boolean", "")

def schema_of(sql_text, base):
    db = sqlite3.connect(":memory:")
    db.executescript(base)
    db.executescript(sql_text)
    rows = db.execute(
        "SELECT type, name, sql FROM sqlite_master"
        " WHERE sql IS NOT NULL AND (name LIKE '%progress%' OR sql LIKE '%manga_progress%')"
        " ORDER BY type, name"
    ).fetchall()
    return {(t, n): re.sub(r"\s+", " ", s).strip() for t, n, s in rows}

ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()
SQ = f"{ROOT}/data/src/main/sqldelight/tachiyomi/data"
VIEW = f"{ROOT}/data/src/main/sqldelight/tachiyomi/view"
MIGRATIONS = f"{ROOT}/data/src/main/sqldelight/tachiyomi/migrations"


def ddl(text):
    """Everything before the first labelled query."""
    text = clean(text)
    for ann in (" AS Date", " AS List<String>", " AS JsonObject", " AS UpdateStrategy"):
        text = text.replace(ann, "")
    m = re.search(r"^[a-zA-Z][a-zA-Z0-9_]*:$", text, re.M)
    return text[: m.start()] if m else text


def at_baseline(path):
    """The file as the revision the newest migration upgrades declared it."""
    return subprocess.run(
        ["git", "-C", ROOT, "show", f"{BASELINE}:{path}"],
        capture_output=True, text=True, check=True,
    ).stdout


# The baseline is the revision before the newest migration was added, the same
# one verify_sqldelight_gradle.sh uses, and HEAD while that migration is still
# uncommitted.
NEWEST = max(
    int(f[:-4]) for f in os.listdir(MIGRATIONS) if f.endswith(".sqm")
)
NEWEST_PATH = f"data/src/main/sqldelight/tachiyomi/migrations/{NEWEST}.sqm"
tracked = subprocess.run(
    ["git", "-C", ROOT, "ls-files", "--error-unmatch", NEWEST_PATH],
    capture_output=True, text=True,
).returncode == 0
if tracked:
    added = subprocess.run(
        ["git", "-C", ROOT, "log", "--diff-filter=A", "--format=%H", "-1", "--", NEWEST_PATH],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    BASELINE = f"{added}^"
else:
    BASELINE = "HEAD"

tables = ""
for f in ("mangas.sq", "chapters.sq", "history.sq", "excluded_scanlators.sq",
          "categories.sq", "mangas_categories.sq"):
    tables += ddl(open(f"{SQ}/{f}").read())

# A fresh install creates the current view; an existing one starts from the
# baseline's and has to be brought to the same place by the migration.
fresh = schema_of(
    ddl(open(f"{SQ}/manga_progress.sq").read()) + ddl(open(f"{VIEW}/libraryView.sq").read()),
    tables,
)

mig = clean(open(f"{MIGRATIONS}/{NEWEST}.sqm").read())
upgraded = schema_of(
    mig,
    tables + ddl(at_baseline("data/src/main/sqldelight/tachiyomi/view/libraryView.sq")),
)

ok = True
for key in sorted(set(fresh) | set(upgraded)):
    a, b = fresh.get(key), upgraded.get(key)
    if a != b:
        ok = False
        print(f"MISMATCH {key[0]} {key[1]}\n  fresh install: {a}\n  after upgrade: {b}\n")
    else:
        print(f"ok  {key[0]:7} {key[1]}")

print()
print("fresh-install schema and post-migration schema are identical" if ok else "SCHEMAS DIVERGE")
sys.exit(0 if ok else 1)
