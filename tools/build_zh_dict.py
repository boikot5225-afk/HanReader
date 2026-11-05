#!/usr/bin/env python3
"""Build app/src/main/assets/zh/lexicon.dat, the offline Chinese lexicon.

Inputs:
  zh_dict_core.json          CC-CEDICT compact map from Reader AI (boikot5225-afk/Test, data/):
                             word -> [pinyin, english, traditional?]          (CC BY-SA 4.0)
  zh_vocab_frequency.txt.gz  Reader AI, one word per line, line = HSK frequency rank (MIT)
  dict.txt                   Jieba 0.42.1 lexicon "word freq tag" (pip download jieba)  (MIT)

Output: gzip TSV sorted by word (named .dat: the Android build would unpack a .gz asset):  word \t pinyin \t english \t hskRank \t jiebaFreq
Jieba words missing from CC-CEDICT are added (Han characters only, freq >= MIN_JIEBA_FREQ)
with empty pinyin/english, so the segmenter knows them. hskRank / jiebaFreq are 0 when absent.

Usage: python3 tools/build_zh_dict.py <dict.json> <freq.txt.gz> <jieba dict.txt> <out.tsv.gz>
"""
import gzip
import json
import sys


MIN_JIEBA_FREQ = 5


def is_han(word: str) -> bool:
    return all("\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf" for ch in word)


def main(dict_path: str, freq_path: str, jieba_path: str, out_path: str) -> None:
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
        pinyin = str(value[0] or "").replace("\t", " ").strip()
        gloss = str(value[1] or "").replace("\t", " ").replace("\n", " ").strip()
        rows[word] = (word, pinyin, gloss, ranks.get(word, 0), jieba.get(word, 0))
    added = 0
    for word, freq in jieba.items():
        if word not in rows and freq >= MIN_JIEBA_FREQ and is_han(word):
            rows[word] = (word, "", "", ranks.get(word, 0), freq)
            added += 1
    with gzip.open(out_path, "wt", encoding="utf-8", compresslevel=9) as out:
        for key in sorted(rows):
            out.write("%s\t%s\t%s\t%d\t%d\n" % rows[key])
    print(f"{len(rows)} entries ({added} from Jieba only) -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
