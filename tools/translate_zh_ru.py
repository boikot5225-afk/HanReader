#!/usr/bin/env python3
"""Russian meanings for Chinese entries with the DeepSeek chat API (one-time dictionary work).

Input:  TSV  word \\t pinyin \\t english \\t kind   (kind: word | name | idiom)
        made by tools/dict_worklist.py from data/zh/lexicon.dat (CC-CEDICT + English Wiktionary).
Output: JSONL {"w": word, "ru": "значение; значение"}; progress is kept, a run resumes.
Then tools/update_ru_ai.py writes the answers into app/src/main/assets/zh/ru_ai.tsv.

Each batch holds one kind, with its own instructions:
  word   dictionary senses, as in tools/translate_cedict_ru.py;
  idiom  chengyu, proverbs, xiehouyu: the figurative meaning or a Russian equivalent first,
         then «букв.: …» when the literal sense differs;
  name   proper names and terms: the accepted Russian name or the Palladius transcription,
         with a few words on what it is.
The result is a derivative of CC-CEDICT / Wiktionary (CC BY-SA 4.0).
The API key comes from DEEPSEEK_API_KEY; in a Claude cloud session the network proxy inserts it.

Usage: python3 tools/translate_zh_ru.py <worklist.tsv> <out.jsonl> [--threads 8] [--limit N]
"""
import argparse
import concurrent.futures
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.deepseek.com/chat/completions"
# Answers mixing Latin and Cyrillic in a word (Цзявaн) or holding characters are asked again.
BAD = re.compile(r"[а-яё][a-z]|[a-z][а-яё]|[\u3400-\u9fff]", re.IGNORECASE)
BATCH = 40

COMMON = (
    "Пиши только по-русски: без иероглифов, пиньиня и английских слов (кроме общепринятых аббревиатур). "
    "Ответ — только JSON-объект {\"items\":[{\"w\":слово,\"ru\":значение}]} для каждой записи, в том же порядке."
)
PROMPTS = {
    "word": (
        "Ты лексикограф китайско-русского словаря. Для каждого китайского слова даны пиньинь и английские "
        "значения (CC-CEDICT или Викисловарь). Дай краткие русские значения в стиле словаря: 1–4 значения "
        "через «; », каждое из 1–5 слов, без пояснений и примеров. Сохраняй части речи и порядок значений, "
        "опускай редкие и пометки вроде «variant of». Для служебных слов кратко назначение "
        "(«счётное слово для книг»). Если слово — разговорное, книжное или бранное, добавь пометку "
        "«разг.», «книжн.», «бран.» перед значением. "
    ),
    "idiom": (
        "Ты лексикограф китайско-русского словаря. Даны китайские чэнъюи, пословицы и иносказания (сехоуюй) "
        "с пиньинем и английскими значениями. Для каждого дай: сначала переносный смысл — 1–3 варианта или "
        "устоявшийся русский аналог, если он точно совпадает по смыслу («убить двух зайцев»), через «; »; "
        "затем, если буквальный смысл отличается от переносного, «букв.: …» — кратко, по иероглифам. "
        "Не выдумывай аналоги, которые не совпадают по смыслу. Не длиннее 160 символов. "
    ),
    "name": (
        "Ты лексикограф китайско-русского словаря. Даны собственные имена и названия (места, люди, "
        "организации, праздники, эпохи, термины) с пиньинем и английскими значениями. Для каждого дай "
        "принятое русское название (北京 — Пекин, 长城 — Великая Китайская стена, 端午节 — праздник Дуаньу) "
        "или, если его нет, транскрипцию по системе Палладия (张伟 — Чжан Вэй), и через «; » — 2–6 слов, "
        "что это («город в провинции Хэнань», «династия», «поэт эпохи Тан»). Не длиннее 120 символов. "
    ),
}


def call(kind, batch):
    lines = "\n".join(f"{w}\t{p}\t{en[:300]}" for w, p, en, _ in batch)
    body = {
        "model": "deepseek-chat",
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": PROMPTS[kind] + COMMON},
            {"role": "user", "content": "Слово\tпиньинь\tанглийский:\n" + lines},
        ],
    }
    key = os.environ.get("DEEPSEEK_API_KEY", "proxy-injected")
    req = urllib.request.Request(API, json.dumps(body, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json", "Authorization": "Bearer " + key})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                content = json.load(r)["choices"][0]["message"]["content"]
            asked = {b[0] for b in batch}
            out = {}
            for it in json.loads(content).get("items", []):
                w = str(it.get("w", "")).strip()
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                if w in asked and ru and w not in out and not BAD.search(ru) and re.search("[а-яё]", ru, re.I):
                    out[w] = ru
            return out
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError, OSError) as e:
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("worklist")
    ap.add_argument("out")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0, help="a random sample of this many (pilot)")
    a = ap.parse_args()
    done = set()
    if os.path.exists(a.out):
        done = {json.loads(l)["w"] for l in open(a.out, encoding="utf-8") if l.strip()}
    todo = []
    for line in open(a.worklist, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        w, p, en, kind = line.rstrip("\n").split("\t")
        if w not in done:
            todo.append((w, p, en, kind))
    if a.limit:
        random.Random(3).shuffle(todo)
        todo = todo[:a.limit]
    batches = []
    for kind in PROMPTS:
        rows = [t for t in todo if t[3] == kind]
        batches += [(kind, rows[i:i + BATCH]) for i in range(0, len(rows), BATCH)]
    print(f"{len(todo)} entries in {len(batches)} batches", file=sys.stderr)
    with open(a.out, "a", encoding="utf-8") as f, concurrent.futures.ThreadPoolExecutor(a.threads) as pool:
        for n, res in enumerate(pool.map(lambda b: call(*b), batches), 1):
            for w, ru in res.items():
                f.write(json.dumps({"w": w, "ru": ru}, ensure_ascii=False) + "\n")
            f.flush()
            if n % 25 == 0:
                print(f"{n}/{len(batches)}", file=sys.stderr)


if __name__ == "__main__":
    main()
