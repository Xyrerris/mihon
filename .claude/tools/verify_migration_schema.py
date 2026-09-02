"""Compare the manga_progress DDL as declared in the .sq against the .sqm,
the way SQLDelight's migration verification does."""
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
base = ""
for f in ("mangas.sq", "chapters.sq", "history.sq", "excluded_scanlators.sq"):
    txt = clean(open(f"{SQ}/{f}").read())
    for ann in (" AS Date", " AS List<String>", " AS JsonObject", " AS UpdateStrategy"):
        txt = txt.replace(ann, "")
    m = re.search(r"^[a-zA-Z][a-zA-Z0-9_]*:$", txt, re.M)
    base += txt[: m.start()] if m else txt

sq = clean(open(f"{SQ}/manga_progress.sq").read())
m = re.search(r"^[a-zA-Z][a-zA-Z0-9_]*:$", sq, re.M)
fresh = schema_of(sq[: m.start()], base)

mig = clean(open(f"{ROOT}/data/src/main/sqldelight/tachiyomi/migrations/15.sqm").read())
upgraded = schema_of(mig, base)

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
