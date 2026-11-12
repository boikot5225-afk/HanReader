#!/usr/bin/env python3
"""Worklist for tools/translate_zh_ru.py: lexicon entries that need a Russian meaning.

  idiom  every chengyu, proverb and xiehouyu ("(idiom)", "(proverb)", "(xiehouyu)" in the English)
         not covered by the Russian Wiktionary: translated again in the «смысл; букв.: …» form;
  name   proper names and terms (capitalised CC-CEDICT reading) without a Russian meaning;
  word   every other entry with an English meaning and no Russian one (Wiktionary-filled words,
         rare characters).

Output: TSV  word \\t pinyin \\t english \\t kind
Usage: python3 tools/dict_worklist.py <out.tsv>
"""
import gzip
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "zh")


def keys(name):
    return {l.split("\t")[0] for l in open(os.path.join(ASSETS, name), encoding="utf-8") if not l.startswith("#")}


def main(out):
    ru, ai, chars = keys("ru.tsv"), keys("ru_ai.tsv"), keys("ru_chars.tsv")
    rows = []
    with gzip.open(os.path.join(HERE, "..", "data", "zh", "lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, en, _rank, _freq = line.rstrip("\n").split("\t")
            if not en:
                continue
            if any(m in en for m in ("(idiom)", "(proverb)", "(xiehouyu)")):
                if w not in ru:
                    rows.append((w, p, en, "idiom"))
            elif w in ru or w in ai or (len(w) == 1 and w in chars):
                continue
            elif p[:1].isupper() and len(w) > 1:
                rows.append((w, p, en, "name"))
            else:
                rows.append((w, p, en, "word"))
    with open(out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(x.replace("\t", " ") for x in r) + "\n")
    from collections import Counter
    print(len(rows), dict(Counter(r[3] for r in rows)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
