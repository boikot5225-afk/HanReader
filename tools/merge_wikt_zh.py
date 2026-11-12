#!/usr/bin/env python3
"""Merge English Wiktionary entries (tools/extract_wikt_zh.py) into data/zh/lexicon.dat.

1. A lexicon word without a meaning (a Jieba-only word: 一举成名, 一方水土养一方人) takes the
   Wiktionary reading and English senses.
2. Chengyu, proverbs, xiehouyu and idioms the lexicon lacks are added (Han characters only:
   a saying with a comma cannot be one token), with frequency 0 like other dictionary-only words.
3. "Erhua form of X" senses take X's senses.
The English gloss is marked like CC-CEDICT: "(idiom) …" for chengyu and idioms, "(proverb) …",
"(xiehouyu) …", so the app can label them. Existing CC-CEDICT meanings are never replaced.

Output: lexicon.dat in place, and data/zh/wikt_added.tsv (word \\t pinyin \\t english \\t kind \\t how)
listing every change, for review and for translating the new meanings into Russian.

Usage: python3 tools/merge_wikt_zh.py <wikt_zh.tsv.gz> data/zh/lexicon.dat data/zh/wikt_added.tsv
"""
import gzip
import re
import sys

HAN = re.compile(r"^[㐀-䶿一-鿿\U00020000-\U0003ffff]+$")
ERHUA = re.compile(r"^erhua form of [^／(（]*／([^ (（]+)")
TONELESS = str.maketrans("āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ", "aaaaeeeeiiiioooouuuuvvvvvnnnm")


def syllabify(py, word, inventory, char_reading):
    """Wiktionary pinyin ("yījǔchéngmíng", "dǎ tuìtánggǔ") as one syllable per character, spaced.
    Splits by the syllable inventory; if the count does not match the word, uses the readings
    of the single characters."""
    raw = re.sub(r"[\s'’\-·,，]", "", py.lower())
    plain = raw.translate(TONELESS)
    n = len(plain)
    best = [None] * (n + 1)
    best[0] = []
    for i in range(n):
        if best[i] is None:
            continue
        for L in range(6, 0, -1):
            if i + L <= n and plain[i:i + L] in inventory:
                cand = best[i] + [raw[i:i + L]]
                if best[i + L] is None or len(cand) < len(best[i + L]):
                    best[i + L] = cand
    parts = best[n]
    if parts and len(parts) == len(word):
        return " ".join(parts)
    fallback = [char_reading.get(c) for c in word]
    return " ".join(fallback) if all(fallback) else py


MARK = {"chengyu": "(idiom) ", "idiom": "(idiom) ", "proverb": "(proverb) ", "xiehouyu": "(xiehouyu) "}


SCRIPT = __import__("os").path.join(__import__("os").path.dirname(__file__), "..", "app", "src", "main", "assets", "zh", "script.tsv")


def traditional_chars():
    """Characters that OpenCC turns into a different simplified character (還, 閣): a headword
    holding one is a traditional spelling Wiktionary did not mark, not a simplified word."""
    out = set()
    for line in open(SCRIPT, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 3 and p[0] == "t" and p[1] != p[2]:
            out.add(p[1])
    return out


def main(wikt_path, lex_path, out_path):
    trad = traditional_chars()
    wikt = {}
    for line in gzip.open(wikt_path, "rt", encoding="utf-8"):
        w, py, en, kind = line.rstrip("\n").split("\t")
        if any(c in trad for c in w):
            continue
        wikt[w] = [py, en, kind]
    # Erhua forms: the base word's senses.
    for w, v in wikt.items():
        senses = []
        for s in v[1].split(" / "):
            m = ERHUA.match(s)
            if m and m.group(1) in wikt:
                senses += [x for x in wikt[m.group(1)][1].split(" / ") if not x.startswith("erhua form of")]
            elif not s.startswith("erhua form of"):
                senses.append(s)
        v[1] = " / ".join(dict.fromkeys(senses))

    rows = []
    with gzip.open(lex_path, "rt", encoding="utf-8") as f:
        for line in f:
            rows.append(line.rstrip("\n").split("\t"))
    have = {r[0] for r in rows}
    char_reading = {r[0]: r[1].split(" ")[0].lower() for r in rows if len(r[0]) == 1 and r[1]}
    inventory = {v.translate(TONELESS) for v in char_reading.values()} | {"r", "er"}
    added = []
    for r in rows:
        w = r[0]
        if r[2] or w not in wikt or not wikt[w][1]:
            continue
        py, en, kind = wikt[w]
        r[1] = r[1] or syllabify(py, w, inventory, char_reading)
        r[2] = MARK.get(kind, "") + en
        added.append((w, r[1], r[2], kind, "meaning"))
    for w, (py, en, kind) in wikt.items():
        if w in have or kind == "word" or not en or not HAN.match(w) or len(w) < 3:
            continue
        spaced = syllabify(py, w, inventory, char_reading)
        rows.append([w, spaced, MARK.get(kind, "") + en, "0", "0"])
        added.append((w, spaced, MARK.get(kind, "") + en, kind, "new"))
    rows.sort(key=lambda r: r[0])
    with gzip.open(lex_path, "wt", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(r) + "\n")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# English Wiktionary (kaikki.org, CC BY-SA 4.0) entries merged into lexicon.dat by tools/merge_wikt_zh.py\n")
        for a in sorted(added):
            f.write("\t".join(a) + "\n")
    print(f"{sum(1 for a in added if a[4] == 'meaning')} meanings filled, {sum(1 for a in added if a[4] == 'new')} sayings added")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
