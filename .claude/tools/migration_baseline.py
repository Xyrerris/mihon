"""Print the revision the newest migration upgrades: the schema it was written
against, which the verification scripts rebuild from git and migrate forward.

That is the revision that already has migration N-1 and not yet N. For a
migration added by an ordinary commit it is that commit's parent. For one added
by a merge it is whichever parent carries N-1: the fork's 17.sqm arrived in the
merge that brought upstream's 15 and 16 in, and it upgrades upstream's side,
not the fork's previous tip. While N is still uncommitted the candidates are
HEAD and, in the middle of a merge, MERGE_HEAD.

Usage: python3 .claude/tools/migration_baseline.py
Importable as well: baseline() returns the same revision."""
import os
import subprocess
import sys

ROOT = os.environ.get("CLAUDE_PROJECT_DIR") or subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()
MIGRATIONS = "data/src/main/sqldelight/tachiyomi/migrations"


def git(*argv, check=True):
    return subprocess.run(
        ["git", "-C", ROOT, *argv], capture_output=True, text=True, check=check
    )


def has(rev, path):
    return git("cat-file", "-e", f"{rev}:{path}", check=False).returncode == 0


def newest_migration():
    return max(
        int(f[:-4]) for f in os.listdir(f"{ROOT}/{MIGRATIONS}") if f.endswith(".sqm")
    )


def baseline():
    newest = newest_migration()
    path = f"{MIGRATIONS}/{newest}.sqm"
    previous = f"{MIGRATIONS}/{newest - 1}.sqm"

    tracked = git("ls-files", "--error-unmatch", path, check=False).returncode == 0
    if tracked:
        added = git("log", "--diff-filter=A", "--format=%H", "-1", "--", path).stdout.strip()
        candidates = git("rev-list", "--parents", "-n", "1", added).stdout.split()[1:]
    else:
        candidates = ["HEAD"]
        if git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode == 0:
            candidates.append("MERGE_HEAD")

    for rev in candidates:
        if not has(rev, path) and has(rev, previous):
            return git("rev-parse", rev).stdout.strip()

    sys.exit(f"no parent of {candidates} has migration {newest - 1} without {newest}")


if __name__ == "__main__":
    print(baseline())
