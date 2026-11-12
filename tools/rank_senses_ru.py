#!/usr/bin/env python3
"""Put the commonest sense first in Russian entries (DeepSeek, one-time; after tools/order_senses_ru.py).

Rewriting entries in large batches, the model often kept a rare first sense (东西 «восток и запад»
before «вещь»). Here it only ranks: each word comes with its numbered senses and the answer is the
numbers by how common the sense is in modern Chinese, commonest first — easy to check (it must be
a permutation) and nothing is reworded.

Input:  JSONL {"w", "ru"} (tools/order_senses_ru.py output). Output: JSONL {"w", "ru"} reordered;
only entries whose order changed are written. Usage: python3 tools/rank_senses_ru.py <in.jsonl> <out.jsonl>
"""
import concurrent.futures
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://api.deepseek.com/chat/completions"
BATCH = 20

SYSTEM = (
    "Ты лексикограф. Для каждого китайского слова даны пиньинь, английские значения и пронумерованные "
    "русские значения. Расставь номера русских значений по убыванию употребительности слова в этом значении "
    "в современном китайском (речь, книги, новости): самое частое — первым. Пример: 东西 dōngxi — "
    "1 восток и запад; 2 вещь; 3 штука → [2,3,1]. Верни все номера ровно по одному разу. "
    "Ответ — только JSON {\"items\":[{\"w\":слово,\"order\":[номера]}]}."
)


def call(batch, english):
    lines = []
    for w, senses in batch:
        p, en = english.get(w, ("", ""))
        lines.append(f"{w} {p} — EN: {en[:200]} — " + "; ".join(f"{i + 1} {s}" for i, s in enumerate(senses)))
    body = {"model": "deepseek-chat", "temperature": 0, "response_format": {"type": "json_object"},
            "thinking": {"type": "disabled"},
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": "\n".join(lines)}]}
    req = urllib.request.Request(API, json.dumps(body, ensure_ascii=False).encode(),
                                 {"Content-Type": "application/json",
                                  "Authorization": "Bearer " + os.environ.get("DEEPSEEK_API_KEY", "proxy-injected")})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                items = json.loads(json.load(r)["choices"][0]["message"]["content"]).get("items", [])
            senses = dict(batch)
            out = {}
            for it in items:
                w = str(it.get("w", "")).strip().split(" ")[0]
                order = it.get("order")
                if w not in senses or not isinstance(order, list):
                    continue
                n = len(senses[w])
                try:
                    order = [int(x) for x in order]
                except (TypeError, ValueError):
                    continue
                if sorted(order) == list(range(1, n + 1)) and order != list(range(1, n + 1)):
                    out[w] = "; ".join(senses[w][k - 1] for k in order)
            return out
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError, OSError) as e:
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 ** attempt)
    return {}


def main(src, dst):
    english = {}
    with gzip.open(os.path.join(HERE, "..", "data", "zh", "lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, en, _r, _f = line.rstrip("\n").split("\t")
            english[w] = (p, en)
    entries = []
    for line in open(src, encoding="utf-8"):
        e = json.loads(line)
        senses = [s.strip() for s in e["ru"].split(";") if s.strip()]
        if len(senses) >= 2:
            entries.append((e["w"], senses))
    batches = [entries[i:i + BATCH] for i in range(0, len(entries), BATCH)]
    print(f"{len(entries)} entries in {len(batches)} batches", file=sys.stderr)
    with open(dst, "w", encoding="utf-8") as f, concurrent.futures.ThreadPoolExecutor(8) as pool:
        for n, res in enumerate(pool.map(lambda b: call(b, english), batches), 1):
            for w, ru in res.items():
                f.write(json.dumps({"w": w, "ru": ru}, ensure_ascii=False) + "\n")
            f.flush()
            if n % 100 == 0:
                print(f"{n}/{len(batches)}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
