#!/usr/bin/env python3
"""Apply hand-checked Chinese meanings (data/zh/manual.tsv) to the app's dictionaries.

The reader sends words whose translation was wrong or missing in a real book (the list
«Переводы под вопросом»); each fix goes into data/zh/manual.tsv, one line per word:

  word \t reading \t Russian senses ("; " between them) \t English gloss \t note

- A single character with a reading (冲 \t chòng \t …): replaces the senses of that reading in
  assets/zh/ru_chars.tsv (the meaning shown for the character on the page and in word breakdowns);
  a new reading is added after the known ones.
- A word: replaces its senses in assets/zh/ru_ai.tsv (shown before the Russian Wiktionary).
  A word the dictionary lacks, or knows only as a Jieba combination without a meaning, is added to
  data/zh/lexicon.dat with the reading and the English gloss (both needed then), so the segmenter
  keeps it whole and the card has a reading; lexicon.bin is rebuilt.
- "=reading" for a word: the senses of the word in that reading (重重 \t =zhòng zhòng: «тяжело»,
  =chóng chóng: «слой за слоем»), kept with the characters' readings; the reading on the page
  (set by the dictionary or by zh/Polyphones.kt) picks them, the first is the default.
- "-" as the reading: not a word, a Jieba combination without a meaning that the segmenter
  should not keep whole (文传 in 人以文传, 文以人传); it is removed from the dictionary.
- "!reading" for a defined word replaces its reading in the dictionary (地方 \t !dì fang).
  A defined word the segmenter never keeps whole (Jieba frequency 0, 起作用) gets a frequency.
- The note column is for people (where the word was met); "#" starts a comment line.

Run after editing the file; it is idempotent:
  python3 tools/apply_zh_manual.py
"""
import gzip
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANUAL = os.path.join(ROOT, "data/zh/manual.tsv")
RU_AI = os.path.join(ROOT, "app/src/main/assets/zh/ru_ai.tsv")
RU_CHARS = os.path.join(ROOT, "app/src/main/assets/zh/ru_chars.tsv")
LEXICON = os.path.join(ROOT, "data/zh/lexicon.dat")
LEXICON_BIN = os.path.join(ROOT, "app/src/main/assets/zh/lexicon.bin")
# Frequency given to a word new to the dictionary: enough for the segmenter to keep it whole.
NEW_FREQ = 60


def read_manual():
    rows = []
    for n, line in enumerate(open(MANUAL, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        p = line.split("\t") + [""] * 5
        word, reading, ru, en = (x.strip() for x in p[:4])
        if not word or not ru:
            sys.exit(f"manual.tsv:{n}: a word and its Russian senses are required")
        if "\t" in ru or "\n" in ru:
            sys.exit(f"manual.tsv:{n}: bad senses")
        rows.append((word, reading, ru, en, n))
    return rows


def main():
    rows = read_manual()
    # By reading: characters, and words whose meaning depends on the reading ("=zhòng zhòng").
    chars = [(w, r.lstrip("=").replace(" ", ""), ru, en, n) for w, r, ru, en, n in rows if len(w) == 1 or r.startswith("=")]
    # "-": not a word here, a Jieba combination the segmenter should cut (文传 in 人以文传).
    cuts = [r for r in rows if r[1] == "-"]
    words = [r for r in rows if len(r[0]) > 1 and not r[1].startswith("=") and r[1] != "-"]

    # Characters by reading.
    lines = [l.rstrip("\n").split("\t") for l in open(RU_CHARS, encoding="utf-8")]
    for ch, reading, ru, _, n in chars:
        if not reading:
            sys.exit(f"manual.tsv:{n}: a character needs its reading")
        at = [i for i, l in enumerate(lines) if l[0] == ch]
        same = [i for i in at if lines[i][1] == reading]
        if same:
            lines[same[0]][2] = ru
        elif at:
            lines.insert(at[-1] + 1, [ch, reading, ru])
        else:
            lines.append([ch, reading, ru])
    lines.sort(key=lambda l: l[0])  # stable: readings keep their order
    with open(RU_CHARS, "w", encoding="utf-8") as f:
        f.writelines("\t".join(l) + "\n" for l in lines)

    # Words: Russian senses.
    ai = {}
    for l in open(RU_AI, encoding="utf-8"):
        w, _, v = l.rstrip("\n").partition("\t")
        ai[w] = v
    for w, _, ru, _, _ in words:
        ai[w] = ru
    with open(RU_AI, "w", encoding="utf-8") as f:
        f.writelines(f"{w}\t{ai[w]}\n" for w in sorted(ai))

    # Words: the dictionary itself, for words it lacks or does not define.
    lex = {}
    with gzip.open(LEXICON, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            lex[p[0]] = p
    changed = 0
    for w, _, _, _, n in cuts:
        e = lex.get(w)
        if e is not None and len(e) > 2 and e[2]:
            sys.exit(f"manual.tsv:{n}: {w} has a meaning in the dictionary: it is a word, not cut")
        if e is not None:
            del lex[w]
            changed += 1
    for w, reading, _, en, n in words:
        e = lex.get(w)
        if e is not None and len(e) > 2 and e[2]:
            # Defined already: the Russian senses are enough. "!reading" replaces a wrong reading
            # (地方 dìfang «место», not dìfāng); a word the segmenter never keeps whole (freq 0) gets one.
            if reading.startswith("!") and e[1] != reading[1:]:
                e[1] = reading[1:]
                changed += 1
            if int(e[4] or 0) < NEW_FREQ:
                e[4] = str(NEW_FREQ)
                changed += 1
            continue
        reading = reading.lstrip("!")
        if not reading or not en:
            sys.exit(f"manual.tsv:{n}: {w} is not defined in the dictionary: give its reading and an English gloss")
        rank = e[3] if e else "0"
        freq = e[4] if e and len(e) > 4 and int(e[4] or 0) >= NEW_FREQ else str(NEW_FREQ)
        lex[w] = [w, reading, en, rank, freq]
        changed += 1
    if changed:
        with open(LEXICON, "wb") as raw, gzip.GzipFile(filename="lexicon.dat", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write("".join("\t".join(lex[w]) + "\n" for w in sorted(lex)).encode("utf-8"))
        subprocess.run([sys.executable, os.path.join(ROOT, "tools/build_lexicon_bin.py"), LEXICON, LEXICON_BIN], check=True)
    print(f"{len(chars)} characters, {len(words)} words, {changed} dictionary entries added or defined")


if __name__ == "__main__":
    main()
