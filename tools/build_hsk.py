#!/usr/bin/env python3
"""Build app/src/main/assets/zh/hsk.tsv: HSK levels per word.

Inputs:
  HSK 3.0: complete.min.json from https://github.com/drkameleon/complete-hsk-vocabulary (MIT),
           pinned commit 7ac65bf1a6387d35f1ade478906172a19311c7f9. Checked against
           krmanik/HSK-3.0-words-list: identical levels for all shared words.
  HSK 2.0: the official 2012 lists "HSK Official With Definitions 2012 L1..L6.txt" from
           https://github.com/glxxyz/hskhsk.com (cidian/data, MIT, Alan Davies). The HSK 2.0 levels
           in complete-hsk-vocabulary were not used: against the official lists they miss 199 words
           (说, 没有, 哪儿, 男, 女 ...), add 195 and differ in level for 316.

Erhua: for a word ending in 儿 (哪儿, 一点儿), the form without 儿 gets the same level, since books
often write it that way. Entries with "…" placeholders are skipped.

Output, sorted by word:  word \\t hsk3Level \\t hsk2Level
hsk3Level: new HSK 3.0, 1..6, 7 = levels 7-9; hsk2Level: old HSK 2.0, 1..6; 0 = not in that list.

Usage: python3 tools/build_hsk.py <complete.min.json> <hskhsk cidian/data dir> <out.tsv>
"""
import json
import sys


def put(levels: dict, word: str, level: int) -> None:
    word = word.strip()
    if not word or "…" in word or "..." in word:
        return
    forms = [word]
    if len(word) > 1 and word.endswith("儿"):
        forms.append(word[:-1])
    for w in forms:
        if w not in levels or level < levels[w]:
            levels[w] = level


def main(src: str, official_dir: str, out: str) -> None:
    new_levels = {}
    for e in json.load(open(src, encoding="utf-8")):
        for l in e.get("l", []):
            if l.startswith("n"):
                put(new_levels, e["s"], int(l[1:]))
    old_levels = {}
    for level in range(1, 7):
        path = f"{official_dir}/HSK Official With Definitions 2012 L{level}.txt"
        for line in open(path, encoding="utf-8-sig"):
            put(old_levels, line.split("\t")[0], level)
    rows = {w: (new_levels.get(w, 0), old_levels.get(w, 0)) for w in set(new_levels) | set(old_levels)}
    with open(out, "w", encoding="utf-8") as f:
        for word in sorted(rows):
            f.write("%s\t%d\t%d\n" % (word, *rows[word]))
    print(f"{len(rows)} words -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
