#!/usr/bin/env python3
"""How much of a Claude Code chat was read from the prompt cache (the discounted part), from the chat's transcript file.

  python3 scripts/cache_report.py                 the newest transcript of this project
  python3 scripts/cache_report.py FILE.jsonl      a given transcript
  python3 scripts/cache_report.py --last 20       only the last 20 model calls (is the chat still hitting the cache now?)

Input side only. A token read from the cache costs 0.1 of a normal input token (a 90% discount); a token written to the cache costs 1.25
(5-minute cache) or 2.0 (1-hour cache) of one. The report prints the cache's hit rate and the saving against sending everything uncached.
The transcripts are in ~/.claude/projects/<project>/. Constitution rule 3 asks for this check after the second turn of a chat."""
import glob
import json
import os
import sys

READ, WRITE_5M, WRITE_1H = 0.1, 1.25, 2.0


def newest_transcript():
    files = glob.glob(os.path.expanduser("~/.claude/projects/*/*.jsonl"))
    return max(files, key=os.path.getmtime) if files else None


def calls(path):
    """One entry per model call (a call is written to the transcript more than once), in order."""
    seen, out = set(), []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                d = json.loads(line)
            except ValueError:
                continue
            m = d.get("message") if isinstance(d, dict) else None
            u = m.get("usage") if isinstance(m, dict) else None
            if not u:
                continue
            key = m.get("id") or d.get("uuid")
            if key in seen:
                continue
            seen.add(key)
            out.append((u.get("input_tokens", 0) or 0, u.get("cache_creation_input_tokens", 0) or 0, u.get("cache_read_input_tokens", 0) or 0))
    return out


def report(rows, write_cost=WRITE_5M):
    fresh = sum(r[0] for r in rows)
    wrote = sum(r[1] for r in rows)
    read = sum(r[2] for r in rows)
    total = fresh + wrote + read
    if not total:
        return "no model calls with token counts found."
    cost = fresh + wrote * write_cost + read * READ
    saving = 1 - cost / total
    return (f"{len(rows)} model calls, {total:,} input tokens: {read:,} read from the cache ({read / total:.0%}), {wrote:,} written to it, "
            f"{fresh:,} sent fresh.\n"
            f"Hit rate {read / total:.1%}. Cost against sending it all uncached: {cost / total:.0%} (saving {saving:.0%}; "
            f"a cache write is priced at {write_cost}x here, a read at {READ}x).")


def main(argv):
    last = None
    if "--last" in argv:
        i = argv.index("--last")
        last = int(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    path = argv[0] if argv else newest_transcript()
    if not path or not os.path.isfile(path):
        print("no transcript found: give the path of a .jsonl file from ~/.claude/projects/")
        return 1
    rows = calls(path)
    print(os.path.basename(path))
    print("whole chat:\n  " + report(rows).replace("\n", "\n  "))
    if last:
        print(f"last {last} calls:\n  " + report(rows[-last:]).replace("\n", "\n  "))
    # the telemetry gate: after the second call the cache must be read more than it is written
    if len(rows) > 2 and sum(r[2] for r in rows[2:]) <= sum(r[1] for r in rows[2:]):
        print("WARNING: from the third call on, more was written to the cache than read from it. Look for: a changed prefix "
              "(CLAUDE.md, tools, model), a pause longer than the cache's time to live, or a model switch.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
