#!/usr/bin/env python3
"""One-time translation of CC-CEDICT meanings into Russian with the DeepSeek chat API.

Builds the input from the app's own data:
  app/src/main/assets/zh/lexicon.dat  word, pinyin, English (CC-CEDICT), HSK rank, Jieba freq
  app/src/main/assets/zh/hsk.tsv      every HSK word goes first
  app/src/main/assets/zh/ru.tsv       words the Russian Wiktionary already covers are skipped
Selection: all HSK words, then the most frequent other CC-CEDICT words, up to --top words.
Proper nouns (capitalised CEDICT reading) are skipped.

Output: JSONL, one line per word: {"w": word, "ru": "смысл 1; смысл 2"}. Progress is kept: words
already in the output file are not sent again, so the run can be stopped and resumed.
The result is a derivative of CC-CEDICT and is distributed under CC BY-SA 4.0.

The API key comes from DEEPSEEK_API_KEY; in a Claude cloud session the network proxy inserts it,
so any placeholder value works there.

Usage:
  python3 tools/translate_cedict_ru.py --out ru_pilot.jsonl --limit 200 --spread  # pilot
  python3 tools/translate_cedict_ru.py --out ru_ai.jsonl --top 40000        # full run
"""
import argparse
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "zh")
API = "https://api.deepseek.com/chat/completions"

SYSTEM = (
    "Ты лексикограф китайско-русского словаря. Для каждого китайского слова даны пиньинь и английские "
    "значения из CC-CEDICT. Дай краткие русские значения в стиле словаря: 1–4 значения через «; », "
    "каждое из 1–5 слов, без пояснений, примеров и транскрипции. Сохраняй части речи и порядок значений "
    "исходника, опускай малоупотребительные и пометки вроде «variant of», «surname». Для служебных слов "
    "(частиц, счётных слов) пиши кратко назначение, например «счётное слово для книг». "
    "Пиши только по-русски: без китайских иероглифов и английских слов, кроме общепринятых "
    "аббревиатур (GPS) и ссылок вида «вариант 焰». "
    "Ответ — только JSON-объект {\"items\":[{\"w\":слово,\"ru\":значения}]} в том же порядке."
)


def load_selection(top: int):
    hsk = {line.split("\t")[0] for line in open(os.path.join(ASSETS, "hsk.tsv"), encoding="utf-8")}
    ru = {line.split("\t")[0] for line in open(os.path.join(ASSETS, "ru.tsv"), encoding="utf-8")}
    rows = []
    with gzip.open(os.path.join(ASSETS, "lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, g, _rank, freq = line.rstrip("\n").split("\t")
            if g and not p[:1].isupper():
                rows.append((w, p, g, int(freq), w in hsk))
    listed = [r for r in rows if r[4]]
    rest = sorted((r for r in rows if not r[4]), key=lambda r: -r[3])
    chosen = (listed + rest)[:top]
    return [r for r in chosen if r[0] not in ru]


def gloss(g: str) -> str:
    # Keep the request short: the first senses are enough for a dictionary entry.
    parts = [s.strip() for s in g.replace("/", ";").split(";") if s.strip()]
    return "; ".join(parts[:6])[:220]


def call(batch, key: str, model: str, thinking: bool):
    lines = "\n".join(f"{w}\t{p}\t{gloss(g)}" for w, p, g, _, _ in batch)
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        # Reasoning is not needed for dictionary entries and more than doubles the output tokens.
        "thinking": {"type": "enabled" if thinking else "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": "Слово\tпиньинь\tанглийский:\n" + lines},
        ],
    }
    req = urllib.request.Request(
        API,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"]
    items = json.loads(content).get("items", [])
    return items, data.get("usage", {})


def parse_items(items, batch):
    """Keep answers only for words that were asked, with a non-empty meaning."""
    asked = {r[0] for r in batch}
    out = {}
    for it in items:
        w = str(it.get("w", "")).strip()
        ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
        if w in asked and ru and w not in out:
            out[w] = ru
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=40000)
    ap.add_argument("--limit", type=int, default=0, help="stop after this many new words (pilot)")
    ap.add_argument("--spread", action="store_true", help="with --limit: pick words evenly across the list")
    ap.add_argument("--batch", type=int, default=50)
    ap.add_argument("--model", default="deepseek-flash")
    ap.add_argument("--thinking", action="store_true", help="let the model reason first (slower, ~3x output)")
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
            batch = todo[i : i + args.batch]
            for attempt in range(4):
                try:
                    items, u = call(batch, key, args.model, args.thinking)
                    break
                except (urllib.error.URLError, json.JSONDecodeError, KeyError, TimeoutError) as e:
                    if attempt == 3:
                        print(f"batch {i // args.batch}: giving up: {e}", file=sys.stderr)
                        items, u = [], {}
                    else:
                        time.sleep(2 ** (attempt + 1))
            got = parse_items(items, batch)
            for w, _, _, _, _ in batch:
                if w in got:
                    out.write(json.dumps({"w": w, "ru": got[w]}, ensure_ascii=False) + "\n")
                    written += 1
            out.flush()
            for k in usage:
                usage[k] += int(u.get(k, 0) or 0)
            print(
                f"{min(i + args.batch, len(todo))}/{len(todo)} words, answered {len(got)}/{len(batch)}, "
                f"tokens in {usage['prompt_tokens']} out {usage['completion_tokens']}",
                flush=True,
            )
    print(json.dumps({"written": written, **usage}))


if __name__ == "__main__":
    main()
