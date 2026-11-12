#!/usr/bin/env python3
"""One-time check of the Russian Wiktionary meanings (assets/zh/ru.tsv) with the DeepSeek chat API.

The kaikki extract of the Russian Wiktionary has wrong entries: a word glossed with a neighbouring
sense (迫不及待 "экстренный"), a character's page header instead of a meaning ("ключ 109 目+7 черт…").
Each entry is sent with its pinyin and CC-CEDICT English (data/zh/lexicon.dat) when there is one;
the model answers whether the Russian matches the main meaning and, if not, gives a corrected one.

Output: JSONL, one line per checked word: {"w": word, "ok": true} or {"w": word, "ok": false,
"ru": "исправленное значение"} ("ru" empty: drop the entry). Progress is kept, the run resumes.
tools/clean_ru_wiktionary.py turns the verdicts into data/zh/ru_fixes.tsv and applies them.

The API key comes from DEEPSEEK_API_KEY; in a Claude cloud session the network proxy inserts it.

Also checks the machine translation: --source ru_ai.tsv (senses separated by "; ").

Usage: python3 tools/verify_ru_wiktionary.py --out ru_verdicts.jsonl [--source ru.tsv|ru_ai.tsv] [--limit 200] [--threads 8]
"""
import argparse
import concurrent.futures
import gzip
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "zh")
API = "https://api.deepseek.com/chat/completions"
BATCH = 40

SYSTEM = (
    "Ты редактор китайско-русского словаря. Тебе дают записи: китайское слово, пиньинь и английские "
    "значения из CC-CEDICT (если есть) и русское значение из словаря. Проверь русское значение. "
    "Оно ВЕРНО, если передаёт основное или одно из основных значений слова, даже если неполно или "
    "стилистически неидеально. Оно НЕВЕРНО, если: означает другое слово или другой смысл; содержит "
    "служебный текст вместо значения (ключ, число черт, метод ввода, иероглифы вместо перевода); не на "
    "русском; пусто или «?». Не придирайся: синонимы, пометки вроде «комп.», «разг.», устаревшие, но "
    "существующие значения — это верно. Для неверных дай исправленное значение в стиле словаря: 1–4 "
    "значения через «; », каждое из 1–5 слов, только по-русски. Для одного иероглифа давай его основные "
    "значения; для служебных слов кратко назначение. Отвечай строго JSON: "
    "{\"items\": [{\"w\": слово, \"ok\": true} | {\"w\": слово, \"ok\": false, \"ru\": \"…\"}]} "
    "для каждой записи, в том же порядке."
)


def load_entries(source="ru.tsv"):
    ru = []
    for line in open(os.path.join(ASSETS, source), encoding="utf-8"):
        w, m = line.rstrip("\n").split("\t", 1)
        ru.append((w, m))
    cedict = {}
    with gzip.open(os.path.join(HERE, "..", "data", "zh", "lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, g, _rank, _freq = line.rstrip("\n").split("\t")
            if w not in cedict:
                cedict[w] = (p, g)
            else:  # several readings: keep them all
                p0, g0 = cedict[w]
                cedict[w] = (p0 + " | " + p, g0 + " | " + g)
    return ru, cedict


def ask(batch, cedict):
    lines = []
    for w, m in batch:
        p, g = cedict.get(w, ("", ""))
        item = {"w": w, "ru": m}
        if p:
            item["pinyin"] = p
        if g:
            item["en"] = g[:300]
        lines.append(json.dumps(item, ensure_ascii=False))
    body = json.dumps({
        "model": "deepseek-chat",
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": "\n".join(lines)}],
        "response_format": {"type": "json_object"},
        "temperature": 0,
    }).encode()
    key = os.environ.get("DEEPSEEK_API_KEY", "proxy-injected")
    for attempt in range(6):
        try:
            req = urllib.request.Request(API, body, {"Content-Type": "application/json", "Authorization": "Bearer " + key})
            with urllib.request.urlopen(req, timeout=300) as r:
                content = json.load(r)["choices"][0]["message"]["content"]
            items = json.loads(content)["items"]
            by_word = {str(it.get("w", "")): it for it in items}
            out = []
            for w, _m in batch:
                it = by_word.get(w)
                if it is None:
                    continue
                if it.get("ok") is True:
                    out.append({"w": w, "ok": True})
                else:
                    ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                    out.append({"w": w, "ok": False, "ru": ru})
            return out
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError, json.JSONDecodeError, OSError) as e:
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0, help="check only this many words (a random sample)")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--source", default="ru.tsv")
    a = ap.parse_args()
    ru, cedict = load_entries(a.source)
    done = set()
    if os.path.exists(a.out):
        for line in open(a.out, encoding="utf-8"):
            done.add(json.loads(line)["w"])
    todo = [e for e in ru if e[0] not in done]
    if a.limit:
        random.Random(7).shuffle(todo)
        todo = todo[:a.limit]
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    print(f"{len(todo)} words in {len(batches)} batches", file=sys.stderr)
    lock = threading.Lock()
    with open(a.out, "a", encoding="utf-8") as f, concurrent.futures.ThreadPoolExecutor(a.threads) as pool:
        for n, res in enumerate(pool.map(lambda b: ask(b, cedict), batches), 1):
            with lock:
                for it in res:
                    f.write(json.dumps(it, ensure_ascii=False) + "\n")
                f.flush()
            if n % 10 == 0:
                print(f"{n}/{len(batches)}", file=sys.stderr)


if __name__ == "__main__":
    main()
