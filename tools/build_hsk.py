#!/usr/bin/env python3
"""Build app/src/main/assets/zh/hsk.tsv: HSK levels per word.

Input: complete.min.json from https://github.com/drkameleon/complete-hsk-vocabulary (MIT),
pinned commit 7ac65bf1a6387d35f1ade478906172a19311c7f9 (the same source as Reader AI's frequency list).

Output, sorted by word:  word \\t hsk3Level \\t hsk2Level
hsk3Level: new HSK 3.0, 1..6, 7 = levels 7-9; hsk2Level: old HSK 2.0, 1..6; 0 = not in that list.

Usage: python3 tools/build_hsk.py <complete.min.json> <out.tsv>
"""
import json
import sys


def main(src: str, out: str) -> None:
    rows = {}
    for e in json.load(open(src, encoding="utf-8")):
        word = e["s"].strip()
        new = [int(l[1:]) for l in e.get("l", []) if l.startswith("n")]
        old = [int(l[1:]) for l in e.get("l", []) if l.startswith("o")]
        if not word or not (new or old):
            continue
        n0, o0 = rows.get(word, (0, 0))
        n = min([x for x in new + ([n0] if n0 else [])], default=0)
        o = min([x for x in old + ([o0] if o0 else [])], default=0)
        rows[word] = (n, o)
    with open(out, "w", encoding="utf-8") as f:
        for word in sorted(rows):
            f.write("%s\t%d\t%d\n" % (word, *rows[word]))
    print(f"{len(rows)} words -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
