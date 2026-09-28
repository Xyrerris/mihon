"""Print the revision the newest migration upgrades: the schema it was written
against, which the verification scripts rebuild from git and migrate forward.

That is a revision that has migration N-1, exactly as the tree has it, and not
yet N. For a migration added by an ordinary commit it is that commit's parent.
For one added by a merge it is whichever parent carries N-1: the fork's 17.sqm
arrived in the merge that brought upstream's 15 and 16 in, and it upgrades
upstream's side, not the fork's previous tip. "Exactly as the tree has it" is
what keeps a same-numbered file from matching: that same merge's first parent
has a 15.sqm too, the fork's old one, with nothing in common with upstream's.
While N is still uncommitted the candidates are HEAD and, in the middle of a
merge, MERGE_HEAD.

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


def blob(rev, path):
    """The object id of path at rev, or None where it does not exist."""
    out = git("rev-parse", "-q", "--verify", f"{rev}:{path}", check=False)
    return out.stdout.strip() if out.returncode == 0 else None


def newest_migration():
    return max(
        int(f[:-4]) for f in os.listdir(f"{ROOT}/{MIGRATIONS}") if f.endswith(".sqm")
    )


def baseline():
    newest = newest_migration()
    path = f"{MIGRATIONS}/{newest}.sqm"
    previous = f"{MIGRATIONS}/{newest - 1}.sqm"
    wanted = git("hash-object", f"{ROOT}/{previous}").stdout.strip()

    tracked = git("ls-files", "--error-unmatch", path, check=False).returncode == 0
    if tracked:
        # -m lists a merge that added the file; without it git log diffs no
        # merge at all and never reports one.
        commits = git("log", "-m", "--diff-filter=A", "--format=%H", "--", path).stdout.split()
        candidates = []
        for commit in dict.fromkeys(commits):
            candidates += git("rev-list", "--parents", "-n", "1", commit).stdout.split()[1:]
    else:
        candidates = ["HEAD"]
        if git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode == 0:
            candidates.append("MERGE_HEAD")

    for rev in candidates:
        if blob(rev, path) is None and blob(rev, previous) == wanted:
            return git("rev-parse", rev).stdout.strip()

    sys.exit(f"no candidate among {candidates} has migration {newest - 1} as the tree does")


if __name__ == "__main__":
    print(baseline())
