#!/usr/bin/env python3
"""Russian meanings for Spanish words the Russian Wiktionary lacks, by the DeepSeek chat API.

Input: app/src/main/assets/es/dict.tsv (built by tools/build_es_dict.py): words with English
meanings, no Russian ones, not proper names, ordered by frequency rank (most frequent first).
Output: data/es/ru_ai.jsonl, one line per word: {"w": word, "ru": "смысл 1; смысл 2"}; then
build_es_dict.py takes it in (marked as machine translation in the app). Progress is kept: words
already in the output are not sent again, so a run can be stopped and resumed.
The result is a derivative of Wiktionary (CC BY-SA 4.0) and keeps that licence.

The API key comes from DEEPSEEK_API_KEY; in a Claude cloud session the network proxy inserts it.

Usage:
  python3 tools/translate_es_ru.py --out data/es/ru_ai_pilot.jsonl --limit 200 --spread   # pilot
  python3 tools/translate_es_ru.py --out data/es/ru_ai.jsonl --top 10000                   # full run
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

DICT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "es", "dict.tsv")
API = "https://api.deepseek.com/chat/completions"

SYSTEM = (
    "Ты лексикограф испанско-русского словаря. Для каждого испанского слова даны часть речи и английские "
    "значения из Викисловаря. Дай краткие русские значения в стиле словаря: 1–4 значения через «; », "
    "каждое из 1–5 слов, без пояснений, примеров и транскрипции. Сохраняй часть речи (глагол — инфинитивом, "
    "существительное — в именительном падеже) и порядок значений исходника, опускай редкие значения и "
    "пометки вроде «alternative form of», «plural of». Если английское значение говорит, что слово — "
    "вариант или форма другого слова, дай значение того слова. Пиши только по-русски, без английских слов. "
    "Ответ — только JSON-объект {\"items\":[{\"w\":слово,\"ru\":значения}]} в том же порядке."
)


def load_selection(top):
    rows = []
    for line in open(DICT, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 6:
            continue
        w, pos, ru, en, _src, rank = f[:6]
        if ru or not en or pos == "имя" or not rank:
            continue
        rows.append((w, pos, en, int(rank)))
    rows.sort(key=lambda r: r[3])
    return rows[:top]


def gloss(g):
    parts = [s.strip() for s in g.split(" ; ") if s.strip()]
    return "; ".join(parts[:5])[:260]


def call(batch, key, model):
    lines = "\n".join(f"{w}\t{pos}\t{gloss(g)}" for w, pos, g, _ in batch)
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": "Слово\tчасть речи\tанглийский:\n" + lines},
        ],
    }
    req = urllib.request.Request(
        API,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    items = json.loads(data["choices"][0]["message"]["content"]).get("items", [])
    return items, data.get("usage", {})


def parse_items(items, batch):
    """Answers only for words that were asked, with a non-empty Cyrillic meaning."""
    asked = {r[0] for r in batch}
    out = {}
    for it in items:
        w = str(it.get("w", "")).strip()
        ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
        if w in asked and ru and w not in out and any("а" <= c.lower() <= "я" or c in "ёЁ" for c in ru):
            out[w] = ru
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=10000)
    ap.add_argument("--limit", type=int, default=0, help="stop after this many new words (pilot)")
    ap.add_argument("--spread", action="store_true", help="with --limit: pick words evenly across the list")
    ap.add_argument("--batch", type=int, default=50)
    ap.add_argument("--model", default="deepseek-flash")
    args = ap.parse_args()

    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    done = set()
    if os.path.exists(args.out):
        for line in open(args.out, encoding="utf-8"):
            if line.strip():
                done.add(json.loads(line)["w"])
    todo = [r for r in load_selection(args.top) if r[0] not in done]
    if args.limit:
        if args.spread and len(todo) > args.limit:
            step = len(todo) / args.limit
            todo = [todo[int(k * step)] for k in range(args.limit)]
        else:
            todo = todo[: args.limit]
    print(f"{len(done)} already translated, {len(todo)} to go", flush=True)

    usage = {"prompt_tokens": 0, "completion_tokens": 0, "prompt_cache_hit_tokens": 0}
    written = 0
    with open(args.out, "a", encoding="utf-8") as out:
        for i in range(0, len(todo), args.batch):
            batch = todo[i: i + args.batch]
            items, u = [], {}
            for attempt in range(4):
                try:
                    items, u = call(batch, key, args.model)
                    break
                except (urllib.error.URLError, json.JSONDecodeError, KeyError, OSError) as e:
                    if attempt == 3:
                        print(f"batch {i // args.batch}: giving up: {e}", file=sys.stderr)
                    else:
                        time.sleep(2 ** (attempt + 1))
            got = parse_items(items, batch)
            for w, _, _, _ in batch:
                if w in got:
                    out.write(json.dumps({"w": w, "ru": got[w]}, ensure_ascii=False) + "\n")
                    written += 1
            out.flush()
            for k in usage:
                usage[k] += int(u.get(k, 0) or 0)
            print(f"{min(i + args.batch, len(todo))}/{len(todo)} words, answered {len(got)}/{len(batch)}, "
                  f"tokens in {usage['prompt_tokens']} out {usage['completion_tokens']}", flush=True)
    print(json.dumps({"written": written, **usage}))


if __name__ == "__main__":
    main()
