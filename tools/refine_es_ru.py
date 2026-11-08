#!/usr/bin/env python3
"""A second pass over the Russian meanings of frequent Spanish words, by the DeepSeek chat API.

The Russian Wiktionary lists senses in no particular order (metro: «метро; метр», atacar:
«завязывать…; атаковать»), and the short note over a word takes the first one. For the most
frequent words, the model sees the Russian senses (Wiktionary or machine translation) with the
English ones and returns the main meaning in today's language (1-3 words) and up to four senses,
most common first.

Input: app/src/main/assets/es/dict.tsv. Output: data/es/ru_refined.jsonl, one line per word:
{"w": word, "short": "метр", "ru": "метр; метро; стихотворный размер"}; build_es_dict.py takes it in.
Progress is kept, runs can be resumed and split (--shard k/n). Derivative of Wiktionary, CC BY-SA 4.0.

Usage: python3 tools/refine_es_ru.py --out data/es/ru_refined.jsonl --top 20000 [--shard 0/4]
       python3 tools/refine_es_ru.py --regional --out data/es/ru_regional.jsonl   # words with regional senses
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
    "Ты редактор испанско-русского словаря. Для каждого испанского слова даны часть речи, русские "
    "значения (из Викисловаря или машинного перевода; порядок случайный, бывают устаревшие и ошибочные) "
    "и английские значения из Викисловаря. Верни для каждого слова:\n"
    "short — самое частое значение в современном испанском, 1–3 русских слова, без помет и скобок; "
    "глагол — инфинитивом, существительное — в именительном падеже единственного числа;\n"
    "ru — от 1 до 4 значений по убыванию частоты, через «; », каждое из 1–5 слов, без помет "
    "(геогр., разг., устар.), примеров и пояснений.\n"
    "Опирайся на данные значения; исправляй явные ошибки и порядок, не выдумывай редких значений. "
    "Пиши только по-русски. Ответ — только JSON-объект "
    "{\"items\":[{\"w\":слово,\"short\":основное,\"ru\":значения}]} в том же порядке."
)


REGIONAL = (
    "Ты редактор испанско-русского словаря. Для каждого испанского слова даны часть речи, русские значения "
    "и английские значения из Викисловаря; у английских в скобках — где так говорят и стиль "
    "(«(Мексика, разг.) work»). Верни для каждого слова:\n"
    "short — самое распространённое значение во всём испанском мире, 1–3 русских слова, без помет и скобок;\n"
    "ru — от 1 до 4 значений по убыванию распространённости, через «; », каждое из 1–5 слов; региональному или "
    "стилистически окрашенному значению поставь в начале ту же помету в скобках по-русски, кратко: "
    "«(Мекс., разг.) работа», «(Исп.) компьютер», «(Лат. Ам.) автомобиль», «(Ла-Плата) парень». "
    "Нейтральные общеупотребительные значения — без помет и первыми. Не выдумывай значений, которых нет в исходниках. "
    "Пиши только по-русски. Ответ — только JSON-объект {\"items\":[{\"w\":слово,\"short\":основное,\"ru\":значения}]} в том же порядке."
)


def load_selection(top, regional=False):
    """Frequent words by rank; with [regional], every word with a regional or marked sense."""
    words = None
    if regional:
        words = {l.strip() for l in open(os.path.join(os.path.dirname(DICT), "..", "..", "..", "..", "..", "data", "es", "regional_words.txt"), encoding="utf-8") if l.strip()}
    edited = set()
    if regional:
        path = os.path.join(os.path.dirname(DICT), "..", "..", "..", "..", "..", "data", "es", "ru_refined.jsonl")
        if os.path.exists(path):
            edited = {json.loads(l)["w"] for l in open(path, encoding="utf-8") if l.strip()}
    rows = []
    for line in open(DICT, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 6 or not f[2] or f[1] == "имя":
            continue
        if regional:
            if f[0] not in words:
                continue
            # Frequent words already edited stay as they are unless regional senses are half or more
            # (computadora, guagua), so the general meaning of «haber» or «una» is not redone.
            senses = [x for x in f[3].split(" ; ") if x]
            marked = sum(1 for x in senses if x.startswith("("))
            if f[0] in edited and marked * 2 < max(len(senses), 1):
                continue
        elif not f[5]:
            continue
        rows.append((f[0], f[1], f[2], f[3], int(f[5]) if f[5] else 10**9))
    rows.sort(key=lambda r: r[4])
    return rows if regional else rows[:top]


def call(batch, key, model, system=SYSTEM):
    lines = "\n".join(f"{w}\t{pos}\tру: {ru[:200]}\tангл: {en[:300]}" for w, pos, ru, en, _ in batch)
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": "Слово\tчасть речи\tрусские\tанглийские:\n" + lines}],
    }
    req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"]).get("items", []), data.get("usage", {})


def cyrillic(x):
    return any("а" <= c.lower() <= "я" or c in "ёЁ" for c in x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=20000)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=40)
    ap.add_argument("--model", default="deepseek-flash")
    ap.add_argument("--shard", default="0/1")
    ap.add_argument("--regional", action="store_true", help="words with regional or marked senses: labels in the Russian senses")
    args = ap.parse_args()
    k, n = (int(x) for x in args.shard.split("/"))
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    done = set()
    if os.path.exists(args.out):
        done = {json.loads(l)["w"] for l in open(args.out, encoding="utf-8") if l.strip()}
    todo = [r for r in load_selection(args.top, args.regional) if r[0] not in done][k::n]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(done)} done, {len(todo)} to go", flush=True)
    written = 0
    tokens = [0, 0]
    with open(args.out, "a", encoding="utf-8") as out:
        for i in range(0, len(todo), args.batch):
            batch = todo[i: i + args.batch]
            items, u = [], {}
            for attempt in range(4):
                try:
                    items, u = call(batch, key, args.model, REGIONAL if args.regional else SYSTEM)
                    break
                except (urllib.error.URLError, json.JSONDecodeError, KeyError, OSError) as e:
                    if attempt == 3:
                        print(f"batch {i}: giving up: {e}", file=sys.stderr)
                    else:
                        time.sleep(2 ** (attempt + 1))
            asked = {r[0] for r in batch}
            seen = set()
            for it in items:
                w = str(it.get("w", "")).strip()
                short = " ".join(str(it.get("short", "")).split()).strip(" ;")
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                if w in asked and w not in seen and short and ru and cyrillic(short) and cyrillic(ru):
                    seen.add(w)
                    out.write(json.dumps({"w": w, "short": short, "ru": ru}, ensure_ascii=False) + "\n")
                    written += 1
            out.flush()
            tokens[0] += int(u.get("prompt_tokens", 0) or 0)
            tokens[1] += int(u.get("completion_tokens", 0) or 0)
            print(f"{min(i + args.batch, len(todo))}/{len(todo)}, answered {len(seen)}/{len(batch)}, tokens {tokens}", flush=True)
    print(json.dumps({"written": written, "prompt_tokens": tokens[0], "completion_tokens": tokens[1]}))


if __name__ == "__main__":
    main()
