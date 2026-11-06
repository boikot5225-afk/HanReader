#!/usr/bin/env python3
"""Build app/src/main/assets/zh/ru.tsv: Russian meanings of Chinese words from the Russian Wiktionary.

Inputs: the kaikki.org wiktextract extracts of ru.wiktionary.org (https://kaikki.org/ruwiktionary/):
  "Китайский (Гуаньхуа)" words and "Китайский иероглиф" characters, JSONL.
Wiktionary text is CC BY-SA 4.0 (and GFDL); the derived file keeps that licence, see
assets/licenses/RU_WIKTIONARY_NOTICE.txt.

Output, sorted by word:  word \\t meanings   (senses joined by " ; ", at most MAX_SENSES, each trimmed)

Usage: python3 tools/build_ru_wiktionary.py <words.jsonl> <chars.jsonl> <out.tsv>
"""
import json
import re
import sys

MAX_SENSES = 4
MAX_SENSE_LEN = 90


def clean(text: str) -> str:
    text = re.sub(r"\s+", " ", text.replace("\t", " ")).strip(" ;,.")
    if len(text) > MAX_SENSE_LEN:
        text = text[:MAX_SENSE_LEN].rsplit(" ", 1)[0] + "…"
    return text


def main(words_path: str, chars_path: str, out: str) -> None:
    meanings = {}
    for path in (words_path, chars_path):
        for line in open(path, encoding="utf-8"):
            e = json.loads(line)
            word = e.get("word", "").strip()
            if not word:
                continue
            for s in e.get("senses", []):
                for g in s.get("glosses", []):
                    g = clean(g)
                    if g and g not in meanings.setdefault(word, []):
                        meanings[word].append(g)
    with open(out, "w", encoding="utf-8") as f:
        for word in sorted(meanings):
            senses = meanings[word][:MAX_SENSES]
            if senses:
                f.write("%s\t%s\n" % (word, " ; ".join(senses)))
    print(f"{len(meanings)} words -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
