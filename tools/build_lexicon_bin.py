#!/usr/bin/env python3
"""Build app/src/main/assets/zh/lexicon.bin from data/zh/lexicon.dat (gzip TSV made by build_zh_dict.py).

A binary lexicon the app maps into memory instead of parsing 190k lines at every start.
All numbers little-endian int32; sections in this order, each starting on a 4-byte boundary:

  header   "HRLX", version, count N, tableSize T (power of two), maxWordLength, totalFreq (int64 as 2 ints)
  wordOff  int32[N+1]   offsets into wordChars, in UTF-16 code units
  words    uint16[...]  every word, UTF-16, sorted by word (padded to 4 bytes)
  table    int32[T]     open addressing, linear probing: entry index + 1, 0 = empty;
                        slot = Java String.hashCode(word) & (T-1)
  rank     int32[N]     HSK frequency rank (0 = unranked); bit 30 set if the entry has a meaning;
                        bit 29 set if the entry is "loose": a Jieba combination without a meaning,
                        not a proper name, that starts or ends with a function word (一个, 放在,
                        是因为) or is two dictionary words together (解决问题) — the segmenter does not keep it whole, its parts are the words
  freq     int32[N]     Jieba count
  pyOff    int32[N+1]   offsets into pinyin bytes;  pinyin  UTF-8 bytes (padded)
  glOff    int32[N+1]   offsets into gloss bytes;   gloss   UTF-8 bytes (padded)

Proper names come from app/src/main/assets/zh/names.tsv (next to the output file).

Usage: python3 tools/build_lexicon_bin.py data/zh/lexicon.dat app/src/main/assets/zh/lexicon.bin
"""
import gzip
import os
import struct
import sys

VERSION = 1
HAS_DEF = 1 << 30
LOOSE = 1 << 29
# A Jieba combination is loose when a function word starts or ends it: numerals and demonstratives,
# adverbs, coverbs, pronouns in front (一个, 很大, 是因为, 我会); particles and complements behind (放在, 美的).
LOOSE_FIRST = set("零一二三四五六七八九十百千万两几每这那哪某各不没很最更也都就还又才再只已曾将正是有在把被给让叫对向往从和与跟同比为以用我你他她它谁")
LOOSE_LAST = set("的了着过得地在到给是有不们吗呢吧啊")


def java_hash(s: str) -> int:
    h = 0
    units = struct.unpack("<%dH" % (len(s.encode("utf-16-le")) // 2), s.encode("utf-16-le"))
    for u in units:
        h = (31 * h + u) & 0xFFFFFFFF
    return h


def pad4(b: bytes) -> bytes:
    return b + b"\0" * (-len(b) % 4)


def main(src: str, out: str) -> None:
    rows = []
    with gzip.open(src, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 5 or not p[0]:
                continue
            rows.append((p[0], p[1], p[2], int(p[3] or 0), int(p[4] or 0)))
    rows.sort(key=lambda r: r[0])
    n = len(rows)
    t = 1
    while t < n * 2.5:
        t <<= 1
    word_off, words = [0], []
    for r in rows:
        u = r[0].encode("utf-16-le")
        words.append(u)
        word_off.append(word_off[-1] + len(u) // 2)
    table = [0] * t
    for i, r in enumerate(rows):
        slot = java_hash(r[0]) & (t - 1)
        while table[slot]:
            slot = (slot + 1) & (t - 1)
        table[slot] = i + 1
    def block(strings):
        off, data = [0], bytearray()
        for s in strings:
            b = s.encode("utf-8")
            data += b
            off.append(len(data))
        return off, bytes(data)
    py_off, py = block(r[1] for r in rows)
    gl_off, gl = block(r[2] for r in rows)
    names_path = os.path.join(os.path.dirname(os.path.abspath(out)), "names.tsv")
    names = {l.split("\t")[0] for l in open(names_path, encoding="utf-8")} if os.path.exists(names_path) else set()
    defined = {r[0] for r in rows if r[2]}
    def loose(r):
        w = r[0]
        if r[2] or len(w) < 2 or w in names:
            return False
        if w[0] in LOOSE_FIRST or w[-1] in LOOSE_LAST:
            return True
        # Two dictionary words written together: 解决问题, 产品质量.
        return len(w) in (4, 5) and any(w[:k] in defined and w[k:] in defined for k in (2, 3) if len(w) - k >= 2)
    rank = [r[3] | (HAS_DEF if r[2] else 0) | (LOOSE if loose(r) else 0) for r in rows]
    print(f"{sum(1 for r in rows if loose(r))} loose combinations")
    freq = [r[4] for r in rows]
    total = sum(freq)
    max_len = max(len(r[0]) for r in rows)
    ints = lambda xs: struct.pack("<%di" % len(xs), *xs)
    with open(out, "wb") as f:
        f.write(b"HRLX" + ints([VERSION, n, t, max_len, total & 0xFFFFFFFF, total >> 32]))
        f.write(ints(word_off)); f.write(pad4(b"".join(words)))
        f.write(ints(table))
        f.write(ints(rank)); f.write(ints(freq))
        f.write(ints(py_off)); f.write(pad4(py))
        f.write(ints(gl_off)); f.write(pad4(gl))
    print(f"{n} words, table {t} -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
