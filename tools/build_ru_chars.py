#!/usr/bin/env python3
"""app/src/main/assets/zh/ru_chars.tsv from the JSONL of tools/translate_chars_ru.py.

Output lines: character \\t reading \\t senses (joined by "; "), the readings of a character in the
order given (most frequent first). Readings are kept only if they look like pinyin; senses with
Latin letters are dropped. A derivative of CC-CEDICT, CC BY-SA 4.0 (assets/licenses/RU_AI_NOTICE.txt).

Usage: python3 tools/build_ru_chars.py .work/ru_chars.jsonl app/src/main/assets/zh/ru_chars.tsv
"""
import json
import re
import sys

PINYIN = re.compile(r"^[a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ]+$")


def main(src, out):
    rows = {}
    for line in open(src, encoding="utf-8"):
        d = json.loads(line)
        w = d["w"]
        seen = set()
        for r in d["r"]:
            p = r["p"].strip().lower().replace(" ", "")
            ru = re.sub(r"\s+", " ", r["ru"].replace("\t", " ")).strip(" ;")
            if not PINYIN.match(p) or not ru or p in seen or re.search(r"[A-Za-z]", ru):
                continue
            seen.add(p)
            rows.setdefault(w, []).append((p, ru))
    with open(out, "w", encoding="utf-8") as f:
        for w in sorted(rows):
            for p, ru in rows[w]:
                f.write(f"{w}\t{p}\t{ru}\n")
    print(len(rows), "characters,", sum(len(v) for v in rows.values()), "readings")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
