#!/usr/bin/env python3
"""Update app/src/main/assets/zh/ru_ai.tsv, the machine-translated Russian meanings.

Applied in order:
  1. verdicts of tools/verify_ru_wiktionary.py --source ru_ai.tsv: a meaning judged wrong is
     replaced by the corrected one;
  2. answers of tools/translate_zh_ru.py: new words, names, and every idiom/proverb translated
     again in the «смысл; букв.: …» form (they replace older meanings).
Senses are joined by "; ". Words the Russian Wiktionary (ru.tsv) covers are left out, except set
expressions (idioms, proverbs, xiehouyu), whose machine translation the app prefers.

Usage: python3 tools/update_ru_ai.py <verdicts.jsonl> <translations.jsonl>
"""
import gzip
import json
import os
import sys

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "zh")


def norm(ru):
    return "; ".join(s.strip() for s in ru.replace("\t", " ").split(";") if s.strip())


def main(verdicts, translations):
    path = os.path.join(ASSETS, "ru_ai.tsv")
    rows = {}
    for line in open(path, encoding="utf-8"):
        w, m = line.rstrip("\n").split("\t", 1)
        rows[w] = m
    wikt = {l.split("\t")[0] for l in open(os.path.join(ASSETS, "ru.tsv"), encoding="utf-8")}
    with gzip.open(os.path.join(ASSETS, "..", "..", "..", "..", "..", "data", "zh", "lexicon.dat"), "rt", encoding="utf-8") as f:
        lexicon = {}
        for l in f:
            parts = l.split("\t")
            lexicon[parts[0]] = parts[2]
    fixed = added = replaced = 0
    for line in open(verdicts, encoding="utf-8"):
        v = json.loads(line)
        if not v["ok"] and v.get("ru") and v["w"] in rows and norm(v["ru"]) != rows[v["w"]]:
            rows[v["w"]] = norm(v["ru"])
            fixed += 1
    for line in open(translations, encoding="utf-8"):
        t = json.loads(line)
        w, m = t["w"], norm(t["ru"])
        # Set expressions are kept even where the Russian Wiktionary has them: the app shows the
        # «смысл; букв.: …» form first for them (ZhEngine.russianSenses).
        is_saying = any(k in lexicon.get(w, "") for k in ("(idiom)", "(proverb)", "(xiehouyu)"))
        if (w in wikt and not is_saying) or not m or w not in lexicon:
            continue
        if w in rows:
            replaced += rows[w] != m
        else:
            added += 1
        rows[w] = m
    with open(path, "w", encoding="utf-8") as f:
        for w in sorted(rows):
            f.write(f"{w}\t{rows[w]}\n")
    print(f"{len(rows)} entries: {fixed} corrected, {added} added, {replaced} translated again")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
