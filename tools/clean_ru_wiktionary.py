#!/usr/bin/env python3
"""Clean app/src/main/assets/zh/ru.tsv, the Russian Wiktionary meanings, in place.

1. Headwords that are not Chinese words (Latin letters, spaces) are dropped ("jí", "Z 字形", an Armenian word, "十 三").
2. Corrections from data/zh/ru_fixes.tsv replace the meaning of their word (made once with
   tools/verify_ru_wiktionary.py and checked by hand on samples).
3. Senses that are a character page header ("両 (ключ 1 一+5 черт, всего 6 черт, метод ввода…")
   or a bare "?" are removed; a word left without senses is dropped.

Run after tools/build_ru_wiktionary.py. Idempotent.
Usage: python3 tools/clean_ru_wiktionary.py
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
RU = os.path.join(HERE, "..", "app", "src", "main", "assets", "zh", "ru.tsv")
FIXES = os.path.join(HERE, "..", "data", "zh", "ru_fixes.tsv")

_CJK = "\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u2e80-\u2fdf\u3005\u3007\U00020000-\U0003ffff"
HAN = re.compile("^[" + _CJK + "][-，·" + _CJK + "]*$")
HEADER = re.compile(r"ключ \d+.*черт|метод ввода")


def load_fixes():
    fixes = {}
    for line in open(FIXES, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        w, m = line.rstrip("\n").split("\t", 1)
        fixes[w] = m
    return fixes


def clean_senses(meaning: str):
    out = []
    for s in meaning.split(" ; "):
        s = s.strip()
        if not s or s == "?" or HEADER.search(s):
            continue
        out.append(s)
    return out


def main():
    fixes = load_fixes()
    rows, dropped, fixed = [], 0, 0
    for line in open(RU, encoding="utf-8"):
        w, m = line.rstrip("\n").split("\t", 1)
        if not HAN.match(w):
            dropped += 1
            continue
        if w in fixes and fixes[w] != m:
            m = fixes[w]
            fixed += 1
        senses = clean_senses(m)
        if not senses:
            dropped += 1
            continue
        rows.append((w, " ; ".join(senses)))
    with open(RU, "w", encoding="utf-8") as f:
        for w, m in rows:
            f.write(f"{w}\t{m}\n")
    print(f"{len(rows)} words, {fixed} corrected, {dropped} dropped -> {RU}")


if __name__ == "__main__":
    main()
