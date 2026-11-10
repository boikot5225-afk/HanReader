#!/usr/bin/env python3
"""Build app/src/main/assets/zh/measure.tsv: the measure words (classifiers) of Chinese nouns.

Input: the English Wiktionary Chinese dump of kaikki.org (CC BY-SA 4.0); a noun's measure words are
its categories "Chinese nouns classified by 張/张" (traditional/simplified, or one form) and the
"(Classifier: 張／张; 套)" note of its senses.
Output, sorted by word:  noun (simplified) \\t measure words, most common first, comma-separated
(the order of the Wiktionary entry). Only simplified Han headwords; at most 4 measure words.

Usage: python3 tools/extract_measure_words.py <kaikki Chinese .jsonl.gz> <out.tsv>
"""
import gzip
import json
import re
import sys

CAT = re.compile(r"^Chinese nouns classified by (.+)$")
GLOSS = re.compile(r"\(Classifier: ([^)]*)\)")
HAN = re.compile(r"^[㐀-䶿一-鿿]+$")


def main(src, dst):
    out = {}
    for line in gzip.open(src, "rt", encoding="utf-8"):
        if "classified by" not in line:
            continue
        e = json.loads(line)
        cls = []
        for c in e.get("categories", []) + [c for s in e.get("senses", []) for c in s.get("categories", [])]:
            name = c.get("name") if isinstance(c, dict) else str(c)
            m = CAT.match(name or "")
            if m:
                simp = m.group(1).split("/")[-1].strip()
                if HAN.match(simp) and simp not in cls:
                    cls.append(simp)
        for s in e.get("senses", []):
            for g in s.get("glosses", []):
                for m in GLOSS.finditer(g):
                    for item in m.group(1).split(";"):
                        simp = item.strip().split("／")[-1].strip()
                        if HAN.match(simp) and simp not in cls:
                            cls.append(simp)
        if not cls:
            continue
        heads = [f["form"] for f in e.get("forms", []) if "Simplified-Chinese" in f.get("tags", []) and "alternative" not in f.get("tags", [])] or [e.get("word", "")]
        for h in heads:
            if HAN.match(h):
                cur = out.setdefault(h, [])
                cur += [c for c in cls if c not in cur]
    with open(dst, "w", encoding="utf-8") as f:
        f.write("# Measure words of nouns: English Wiktionary via kaikki.org (CC BY-SA 4.0); tools/extract_measure_words.py\n")
        for w in sorted(out):
            f.write(f"{w}\t{','.join(out[w][:4])}\n")
    print(f"{len(out)} nouns -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
