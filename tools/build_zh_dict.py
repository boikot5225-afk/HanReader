#!/usr/bin/env python3
"""Build data/zh/lexicon.dat, the source of the offline Chinese lexicon (then tools/build_lexicon_bin.py).

Inputs:
  zh_dict_core.json          CC-CEDICT compact map from Reader AI (boikot5225-afk/Test, data/):
                             word -> [pinyin, english, traditional?]          (CC BY-SA 4.0)
  zh_vocab_frequency.txt.gz  Reader AI, one word per line, line = HSK frequency rank (MIT)
  dict.txt                   Jieba 0.42.1 lexicon "word freq tag" (pip download jieba)  (MIT)
  pypinyin dir               pypinyin 0.53.0 package dir with pinyin_dict.json, phrases_dict.json (MIT)

Readings: the compact CC-CEDICT map keeps only a word's first CEDICT reading, often a rare
one or a surname (还 Huán, 都 Dū, 离 chī). pypinyin lists a character's most common reading
first and has readings for 47k phrases, so its reading replaces CEDICT's where they differ
(ignoring case, so CEDICT's capitalised proper nouns stay). Jieba-only words get pypinyin
phrase readings too.

Output: gzip TSV sorted by word (named .dat: the Android build would unpack a .gz asset):  word \t pinyin \t english \t hskRank \t jiebaFreq
Jieba words missing from CC-CEDICT are added (Han characters only, freq >= MIN_JIEBA_FREQ)
with empty pinyin/english, so the segmenter knows them. hskRank / jiebaFreq are 0 when absent.

Usage: python3 tools/build_zh_dict.py <dict.json> <freq.txt.gz> <jieba dict.txt> <pypinyin dir> <out.dat>
"""
import gzip
import json
import sys


MIN_JIEBA_FREQ = 5


def is_han(word: str) -> bool:
    return all("\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf" for ch in word)


def load_pypinyin(path: str):
    chars = {}
    for code, readings in json.load(open(path + "/pinyin_dict.json", encoding="utf-8")).items():
        chars[chr(int(code))] = readings.split(",")[0]
    phrases = {}
    for word, syllables in json.load(open(path + "/phrases_dict.json", encoding="utf-8")).items():
        phrases[word] = " ".join(s[0] for s in syllables)
    return chars, phrases


def better_reading(word: str, cedict: str, chars: dict, phrases: dict) -> str:
    if len(word) == 1:
        candidate = chars.get(word)
    else:
        candidate = phrases.get(word)
    if not candidate:
        return cedict
    # A single character keeps its ordinary lower-case reading: CEDICT's capitalised one is a surname
    # (章 Zhāng, 德 Dé), wrong when the character is part of a word or a transliterated name.
    if len(word) > 1 and cedict and cedict.replace(" ", "").lower() == candidate.replace(" ", "").lower():
        return cedict
    return candidate


def main(dict_path: str, freq_path: str, jieba_path: str, pypinyin_dir: str, out_path: str) -> None:
    chars, phrases = load_pypinyin(pypinyin_dir)
    changed = 0
    with open(dict_path, encoding="utf-8") as f:
        data = json.load(f)
    entries = data["map"]
    with gzip.open(freq_path, "rt", encoding="utf-8") as f:
        ranks = {}
        for i, line in enumerate(f, 1):
            w = line.strip()
            if w and w not in ranks:
                ranks[w] = i
    jieba = {}
    with open(jieba_path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                jieba.setdefault(parts[0], int(parts[1]))
    rows = {}
    for word, value in entries.items():
        word = word.strip()
        if not word or "\t" in word or "\n" in word:
            continue
        cedict_pinyin = str(value[0] or "").replace("\t", " ").strip()
        pinyin = better_reading(word, cedict_pinyin, chars, phrases)
        if pinyin != cedict_pinyin:
            changed += 1
        gloss = str(value[1] or "").replace("\t", " ").replace("\n", " ").strip()
        rows[word] = (word, pinyin, gloss, ranks.get(word, 0), jieba.get(word, 0))
    added = 0
    for word, freq in jieba.items():
        if word not in rows and freq >= MIN_JIEBA_FREQ and is_han(word):
            rows[word] = (word, phrases.get(word, ""), "", ranks.get(word, 0), freq)
            added += 1
    with gzip.open(out_path, "wt", encoding="utf-8", compresslevel=9) as out:
        for key in sorted(rows):
            out.write("%s\t%s\t%s\t%d\t%d\n" % rows[key])
    print(f"{len(rows)} entries ({added} from Jieba only, {changed} readings taken from pypinyin) -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
