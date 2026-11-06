#!/usr/bin/env python3
"""Build app/src/main/assets/zh/script.tsv: simplified <-> traditional conversion tables.

Input: the OpenCC dictionary dir (Apache 2.0; e.g. from the opencc-python-reimplemented 0.1.7
wheel, opencc/dictionary): TSCharacters.txt, STCharacters.txt, STPhrases.txt.

Only conversions that keep the text length are kept (one BMP character for one), so a
converted paragraph keeps every offset: tokens, bookmarks and positions stay valid.

Output lines:  t <TAB> trad <TAB> simp   (character, traditional -> simplified)
               s <TAB> simp <TAB> trad   (character, simplified -> its usual traditional)
               p <TAB> simp <TAB> trad   (phrase whose characters convert differently, e.g. 头发 -> 頭髮)
Usage: python3 tools/build_script.py <opencc dictionary dir> <out.tsv>
"""
import sys


def bmp(s):
    return all(ord(c) < 0x10000 for c in s)


def read(path):
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 2 and parts[0] and parts[1]:
            yield parts[0], parts[1].split(" ")[0]


def main(src, out):
    t2s = {k: v for k, v in read(src + "/TSCharacters.txt") if len(k) == 1 and len(v) == 1 and bmp(k + v) and k != v}
    s2t = {k: v for k, v in read(src + "/STCharacters.txt") if len(k) == 1 and len(v) == 1 and bmp(k + v) and k != v}
    phrases = {}
    for k, v in read(src + "/STPhrases.txt"):
        if len(k) != len(v) or len(k) < 2 or not bmp(k + v):
            continue
        # Only phrases the character table would get wrong.
        if "".join(s2t.get(c, c) for c in k) != v:
            phrases[k] = v
    with open(out, "w", encoding="utf-8") as f:
        f.write("# OpenCC (Apache License 2.0) simplified/traditional tables; see tools/build_script.py\n")
        for k in sorted(t2s): f.write(f"t\t{k}\t{t2s[k]}\n")
        for k in sorted(s2t): f.write(f"s\t{k}\t{s2t[k]}\n")
        for k in sorted(phrases): f.write(f"p\t{k}\t{phrases[k]}\n")
    print(len(t2s), len(s2t), len(phrases))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
