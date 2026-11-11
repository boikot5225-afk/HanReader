#!/usr/bin/env python3
"""Final Russian entries for frequent words, the commonest sense first (DeepSeek, one-time).

CC-CEDICT often lists a rare sense first (东西 «восток и запад» before «вещь», 本事 «исходный
материал» before «умение»), and the machine translation kept that order; the Russian Wiktionary
is sometimes too short (精神 «духовный»). For every word with a meaning, not a name or an idiom,
that is in the HSK lists or has a Jieba count >= MIN_FREQ, the model gets the English senses and
both Russian versions and writes the entry: 1–5 senses, commonest in modern Chinese first, no
senses the sources lack.

Output: JSONL {"w": word, "ru": "значение; значение"}; progress is kept. Then
tools/update_ru_ai.py <empty> <out.jsonl> writes them into ru_ai.tsv, which the app shows before
the Russian Wiktionary.
Usage: python3 tools/order_senses_ru.py <out.jsonl> [--limit N] [--threads 8]
"""
import argparse
import concurrent.futures
import gzip
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "zh")
API = "https://api.deepseek.com/chat/completions"
MIN_FREQ = 100
BATCH = 30
BAD = re.compile(r"[а-яё][a-z]|[a-z][а-яё]|[㐀-鿿]", re.IGNORECASE)

SYSTEM = (
    "Ты редактор китайско-русского учебного словаря. Для каждого слова даны пиньинь, английские значения "
    "(CC-CEDICT) и русские значения из Викисловаря (W) и машинного перевода (A). Составь итоговую статью: "
    "1–5 значений через «; » по убыванию употребительности в современном китайском — самое частое первым. "
    "Значения бери только из источников, не выдумывай новых; можешь переформулировать точнее и короче "
    "(каждое 1–5 слов). Редкие, книжные, устаревшие и узкоспециальные значения — в конце или опусти; "
    "имена собственные и топонимы, совпадающие со словом (уезд, фамилия), опусти. Пометки «разг.», «книжн.» "
    "ставь перед значением, если нужно. Для служебных слов — кратко назначение. Только по-русски, без "
    "иероглифов и пиньиня. Ответ — только JSON {\"items\":[{\"w\":слово,\"ru\":значения}]} в том же порядке."
)


def load():
    ru = dict(l.rstrip("\n").split("\t", 1) for l in open(os.path.join(ASSETS, "ru.tsv"), encoding="utf-8"))
    ai = dict(l.rstrip("\n").split("\t", 1) for l in open(os.path.join(ASSETS, "ru_ai.tsv"), encoding="utf-8"))
    hsk = {l.split("\t")[0] for l in open(os.path.join(ASSETS, "hsk.tsv"), encoding="utf-8")}
    rows = []
    with gzip.open(os.path.join(HERE, "..", "data", "zh", "lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, en, _rank, freq = line.rstrip("\n").split("\t")
            # A capitalised reading is a name (北京); with a second, common reading in "[...]" (大方 Dàfāng
            # county / [dà fang] generous) the word is ordinary and the name goes last.
            if len(w) < 2 or not en or (p[:1].isupper() and "[" not in en):
                continue
            if any(m in en for m in ("(idiom)", "(proverb)", "(xiehouyu)")):
                continue
            if int(freq) < MIN_FREQ and w not in hsk:
                continue
            rows.append((w, p, en, ru.get(w, ""), ai.get(w, "")))
    return rows


def call(batch):
    lines = "\n".join(f"{w}\t{p}\tEN: {en[:260]}\tW: {r[:160]}\tA: {a[:160]}" for w, p, en, r, a in batch)
    body = {"model": "deepseek-chat", "temperature": 0.1, "response_format": {"type": "json_object"},
            "thinking": {"type": "disabled"},
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": lines}]}
    req = urllib.request.Request(API, json.dumps(body, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json",
                                  "Authorization": "Bearer " + os.environ.get("DEEPSEEK_API_KEY", "proxy-injected")})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                items = json.loads(json.load(r)["choices"][0]["message"]["content"]).get("items", [])
            asked = {b[0] for b in batch}
            out = {}
            for it in items:
                w = str(it.get("w", "")).strip()
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                if w in asked and ru and not BAD.search(ru) and re.search("[а-яё]", ru, re.I):
                    out[w] = ru
            return out
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError, OSError) as e:
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    done = set()
    if os.path.exists(a.out):
        done = {json.loads(l)["w"] for l in open(a.out, encoding="utf-8") if l.strip()}
    todo = [r for r in load() if r[0] not in done]
    if a.limit:
        random.Random(4).shuffle(todo)
        todo = todo[:a.limit]
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    print(f"{len(todo)} words in {len(batches)} batches", file=sys.stderr)
    with open(a.out, "a", encoding="utf-8") as f, concurrent.futures.ThreadPoolExecutor(a.threads) as pool:
        for n, res in enumerate(pool.map(call, batches), 1):
            for w, ru in res.items():
                f.write(json.dumps({"w": w, "ru": ru}, ensure_ascii=False) + "\n")
            f.flush()
            if n % 50 == 0:
                print(f"{n}/{len(batches)}", file=sys.stderr)


if __name__ == "__main__":
    main()
