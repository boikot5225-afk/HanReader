#!/usr/bin/env python3
"""Build app/src/main/assets/zh/ru_ai.tsv from the JSONL written by tools/translate_cedict_ru.py.

Output, sorted by word:  word \\t meanings ("; "-separated). A derivative of CC-CEDICT (CC BY-SA 4.0),
distributed under the same licence; see assets/licenses/RU_AI_NOTICE.txt.

Usage: python3 tools/build_ru_ai.py <ru_ai.jsonl> <out.tsv>
"""
import json
import sys


def main(src: str, out: str) -> None:
    rows = {}
    for line in open(src, encoding="utf-8"):
        if not line.strip():
            continue
        e = json.loads(line)
        w = e["w"].strip()
        ru = " ".join(e["ru"].replace("\t", " ").split()).strip(" ;")
        if w and ru and w not in rows:
            rows[w] = ru
    with open(out, "w", encoding="utf-8") as f:
        for w in sorted(rows):
            f.write(f"{w}\t{rows[w]}\n")
    print(f"{len(rows)} words -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
