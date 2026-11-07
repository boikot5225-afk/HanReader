#!/usr/bin/env python3
"""Russian meanings of single Chinese characters, by reading, with the DeepSeek chat API.

Why: a character such as 得 or 地 is read differently in different places (dé «получить»,
de — the particle after a verb). The Russian Wiktionary describes characters as a whole, often
with readings mixed or only the rare ones; the reader needs the meaning of the reading on the page.

Input (the app's own data): data/zh/lexicon.dat — single characters with their CC-CEDICT English
(readings tagged like "[de] structural particle"), the reading the app shows, HSK words first,
then by Jieba frequency.
Output: JSONL, one line per character: {"w": "得", "r": [{"p": "dé", "ru": "..."}, {"p": "de", "ru": "..."}]}
Resumable: characters already in the output are not sent again. A derivative of CC-CEDICT,
distributed under CC BY-SA 4.0.

The API key comes from DEEPSEEK_API_KEY; in a Claude cloud session the network proxy inserts it,
so any placeholder value works there.

Usage: python3 tools/translate_chars_ru.py --out .work/ru_chars.jsonl --top 4000 [--limit 20]
"""
import argparse
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..")
API = "https://api.deepseek.com/chat/completions"

SYSTEM = (
    "Ты лексикограф китайско-русского словаря. Даны китайские иероглифы (как самостоятельные слова), "
    "чтение, которое показывает приложение, и английские значения из CC-CEDICT, где значения других чтений "
    "помечены как [pinyin]. Для каждого иероглифа перечисли его употребительные чтения как отдельного слова "
    "(без фамилий и устаревших вариантов написания) и для каждого чтения дай 1–4 кратких русских значения через «; », "
    "каждое из 1–6 слов, в стиле словаря. Для служебных слов пиши назначение по-русски, например "
    "«структурная частица после глагола: указывает на степень или результат (说得快 — говорит быстро)». "
    "Чтение пиши пиньинем со знаками тона (нейтральный тон без знака). Первым ставь самое частое в современном тексте чтение. "
    "Пиши только по-русски, без английских слов; китайские иероглифы допустимы только в коротком примере в скобках. "
    "Ответ — только JSON-объект {\"items\":[{\"w\":иероглиф,\"r\":[{\"p\":чтение,\"ru\":значения}]}]} в том же порядке."
)


def load(top: int):
    hsk = {line.split("\t")[0] for line in open(os.path.join(ROOT, "app/src/main/assets/zh/hsk.tsv"), encoding="utf-8")}
    rows = []
    with gzip.open(os.path.join(ROOT, "data/zh/lexicon.dat"), "rt", encoding="utf-8") as f:
        for line in f:
            w, p, g, _rank, freq = line.rstrip("\n").split("\t")
            if len(w) == 1 and g and "一" <= w <= "鿿":
                rows.append((w, p, g, int(freq), w in hsk))
    listed = sorted((r for r in rows if r[4]), key=lambda r: -r[3])
    rest = sorted((r for r in rows if not r[4]), key=lambda r: -r[3])
    return (listed + rest)[:top]


def call(batch, key, model):
    lines = "\n".join(f"{w}\t{p}\t{g[:400]}" for w, p, g, _, _ in batch)
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": "Иероглиф\tчтение в приложении\tанглийский:\n" + lines},
        ],
    }
    req = urllib.request.Request(
        API, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read())
    usage = data.get("usage", {})
    items = json.loads(data["choices"][0]["message"]["content"]).get("items", [])
    return items, usage


def valid(item):
    rs = item.get("r")
    if not isinstance(rs, list) or not rs:
        return False
    for r in rs:
        ru = str(r.get("ru", ""))
        if not r.get("p") or not ru or any("a" <= c.lower() <= "z" for c in ru):
            return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--top", type=int, default=4000)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=25)
    ap.add_argument("--model", default="deepseek-chat")
    a = ap.parse_args()
    key = os.environ.get("DEEPSEEK_API_KEY", "proxy-injected")
    done = set()
    if os.path.exists(a.out):
        for line in open(a.out, encoding="utf-8"):
            done.add(json.loads(line)["w"])
    todo = [r for r in load(a.top) if r[0] not in done]
    if a.limit:
        todo = todo[: a.limit]
    print(f"to translate: {len(todo)} (done {len(done)})", flush=True)
    tokens_in = tokens_out = 0
    with open(a.out, "a", encoding="utf-8") as out:
        for i in range(0, len(todo), a.batch):
            batch = todo[i:i + a.batch]
            for attempt in range(4):
                try:
                    items, usage = call(batch, key, a.model)
                    break
                except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
                    print(f"retry {attempt + 1}: {e}", flush=True)
                    time.sleep(2 ** (attempt + 1))
            else:
                print("giving up on batch", i, flush=True)
                continue
            tokens_in += usage.get("prompt_tokens", 0)
            tokens_out += usage.get("completion_tokens", 0)
            wanted = {w for w, *_ in batch}
            kept = 0
            for it in items:
                if it.get("w") in wanted and valid(it):
                    out.write(json.dumps({"w": it["w"], "r": [{"p": r["p"], "ru": r["ru"]} for r in it["r"]]}, ensure_ascii=False) + "\n")
                    kept += 1
            out.flush()
            print(f"{i + len(batch)}/{len(todo)} kept {kept}/{len(batch)} tokens in {tokens_in} out {tokens_out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
