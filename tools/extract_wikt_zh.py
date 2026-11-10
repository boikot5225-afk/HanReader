#!/usr/bin/env python3
"""Extract Mandarin words, idioms and sayings from the English Wiktionary (kaikki.org wiktextract dump).

Input:  kaikki.org-dictionary-Chinese.jsonl.gz (https://kaikki.org/dictionary/Chinese/), CC BY-SA 4.0.
Output: gzip TSV, one line per simplified headword, sorted:
        word \\t pinyin \\t english \\t kind
  word     simplified form (Wiktionary keeps the content under the traditional page; its
           "Simplified-Chinese" form is the key here)
  pinyin   the Mandarin (Standard Chinese) Hanyu Pinyin of the entry
  english  senses joined by " / ", Mandarin senses only (dialect-only senses dropped),
           without "form of" redirects; at most MAX_SENSES
  kind     chengyu | proverb | xiehouyu | idiom | word  (from the entry's categories)
Entries without a Mandarin reading (dialect words) are skipped.

Usage: python3 tools/extract_wikt_zh.py <kaikki Chinese .jsonl.gz> <out.tsv.gz>
"""
import gzip
import json
import re
import sys

MAX_SENSES = 6
DIALECTS = {"Cantonese", "Hokkien", "Hakka", "Teochew", "Wu", "Min-Nan", "Min-Bei", "Min-Dong", "Eastern-Min",
            "Xiang", "Gan", "Jin", "Puxian-Min", "Taishanese", "Shanghainese", "Sichuanese", "Dungan",
            "Northern-Min", "Southern-Min", "Leizhou-Min", "Hainanese"}
HAN = re.compile(r"^[㐀-䶿一-鿿\U00020000-\U0003ffff，、]+$")


def mandarin_pinyin(e):
    for s in e.get("sounds", []):
        tags = set(s.get("tags", []))
        if "Mandarin" in tags and "Pinyin" in tags and s.get("zh_pron") and not tags & {"Tongyong-Pinyin"}:
            return s["zh_pron"].strip()
    return None


def kind_of(e):
    names = []
    for s in e.get("senses", []):
        for c in s.get("categories", []):
            names.append((c.get("name") if isinstance(c, dict) else str(c)).lower())
    for c in e.get("categories", []):
        names.append((c.get("name") if isinstance(c, dict) else str(c)).lower())
    joined = " | ".join(names)
    if "xiehouyu" in joined:
        return "xiehouyu"
    if "chengyu" in joined:
        return "chengyu"
    if "proverb" in joined:
        return "proverb"
    if "idiom" in joined:
        return "idiom"
    return "word"


def mandarin_senses(e):
    out = []
    for s in e.get("senses", []):
        tags = set(s.get("tags", [])) | set(s.get("raw_tags", []))
        if "form-of" in tags or "alt-of" in tags or s.get("form_of") or s.get("alt_of"):
            continue
        if tags & DIALECTS and "Mandarin" not in tags:
            continue
        for g in s.get("glosses", [])[-1:]:
            g = " ".join(g.split()).strip(" ;")
            if g and not g.lower().startswith(("alternative form of", "simplified form of", "traditional form of")):
                out.append(g)
    return out


def main(src, dst):
    entries = {}
    for line in gzip.open(src, "rt", encoding="utf-8"):
        e = json.loads(line)
        if e.get("pos") in ("soft-redirect", "character", "romanization", "symbol"):
            continue
        word = e.get("word", "")
        py = mandarin_pinyin(e)
        senses = mandarin_senses(e)
        if not py or not senses:
            continue
        simp = [f["form"] for f in e.get("forms", []) if "Simplified-Chinese" in f.get("tags", []) and "alternative" not in f.get("tags", [])]
        heads = simp or [word]
        kind = kind_of(e)
        for h in heads:
            h = h.replace("⫽", "")
            if not HAN.match(h):
                continue
            cur = entries.setdefault(h, [py, [], "word"])
            for g in senses:
                if g not in cur[1]:
                    cur[1].append(g)
            if kind != "word":
                cur[2] = kind
    with gzip.open(dst, "wt", encoding="utf-8") as f:
        for w in sorted(entries):
            py, senses, kind = entries[w]
            f.write("%s\t%s\t%s\t%s\n" % (w, py, " / ".join(senses[:MAX_SENSES]).replace("\t", " "), kind))
    print(f"{len(entries)} entries -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
