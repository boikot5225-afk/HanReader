#!/usr/bin/env python3
"""Russian meanings of the pronominal (reflexive) forms of frequent Spanish verbs.

«limitarse a buscar» is «ограничиваться», not «ограничивать»; irse is «уходить», not «идти». The
English Wiktionary mostly lists verbs without -se, so for every frequent verb of dict.tsv that has
no entry with -se the DeepSeek chat API returns what the verb means with a reflexive pronoun.

Output: data/es/ru_reflexive.jsonl: {"w": "limitar", "short": "ограничиваться", "ru": "ограничиваться; сводиться"}
(empty short and ru: the verb is not used with -se); build_es_dict.py adds the entry «limitarse».
Derivative of Wiktionary, CC BY-SA 4.0.

Usage: python3 tools/reflexive_es_ru.py --out data/es/ru_reflexive.jsonl [--shard 0/4]
       python3 tools/reflexive_es_ru.py --out data/es/ru_reflexive.jsonl --verify   # a second look at the drafts
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from refine_es_ru import API, DICT, cyrillic  # noqa: E402

SYSTEM = (
    "Ты редактор испанско-русского словаря. Даны испанские глаголы с русскими и английскими значениями. Для каждого "
    "верни значение его местоименной (возвратной) формы — глагола с -se (limitar → limitarse, ir → irse, poner → ponerse), "
    "как она употребляется в живом испанском:\n"
    "short — одно самое частое значение, 1–3 русских слова, инфинитивом, без помет и без «;»;\n"
    "ru — от 1 до 3 значений по убыванию частоты через «; », инфинитивами: «ограничиваться; сводиться», «уходить; уезжать», "
    "«становиться; надевать; начинать». Не образуй форму механически на «-ся», если по-русски так не говорят "
    "(dormirse — «засыпать», а не «спаться»). У переходных глаголов форма с -se почти всегда есть "
    "(entenderse — «понимать друг друга; ладить», matarse — «убиться; покончить с собой», tomarse — «выпить; принять»): "
    "давай её значение. Пустые short и ru возвращай, только если глагол с -se действительно не употребляется (ser, haber, poder). "
    "Пиши только по-русски. Ответ — только JSON-объект {\"items\":[{\"w\":глагол,\"short\":…,\"ru\":…}]} в том же порядке."
)


VERIFY = (
    "Ты лексикограф-испанист. Даны испанские местоименные глаголы (с -se) и черновые русские переводы. Проверь каждый по "
    "реальному употреблению в испанском и исправь ошибки: перевод должен быть значением именно формы с -se (negarse — "
    "«отказываться», а не «отрицать»; sacudirse — «отряхиваться; избавляться»), настоящим русским словом (не «звучаться», "
    "«препятствоваться»); если по-русски возвратного глагола нет, передай описательно. Если глагол с -se практически не "
    "употребляется, верни пустые строки. short — одно значение, 1–3 слова; ru — 1–3 значения через «; ». "
    "Ответ — только JSON {\"items\":[{\"w\":глагол с -se,\"short\":…,\"ru\":…}]} в том же порядке."
)


def verify(path, model, batch=40, threads=6):
    """A second look at the drafts in [path], which is rewritten."""
    from concurrent.futures import ThreadPoolExecutor
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    todo = [r for r in rows if r["ru"] and not r.get("checked")]

    def one(part):
        body = {
            "model": model, "temperature": 0.1, "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
            "messages": [{"role": "system", "content": VERIFY},
                         {"role": "user", "content": "\n".join(f"{r['w']}se\t{r['ru']}" for r in part)}],
        }
        req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=240) as resp:
                    return part, json.loads(json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]).get("items", [])
            except (urllib.error.URLError, json.JSONDecodeError, KeyError, OSError):
                time.sleep(2 ** (attempt + 1))
        return part, []

    changed = 0
    with ThreadPoolExecutor(threads) as pool:
        for part, items in pool.map(one, [todo[i: i + batch] for i in range(0, len(todo), batch)]):
            by = {r["w"]: r for r in part}
            for it in items:
                r = by.get(str(it.get("w", "")).strip().removesuffix("se")) or by.get(str(it.get("w", "")).strip())
                if r is None:
                    continue
                short = " ".join(str(it.get("short", "")).split(";")[0].split()).strip(" ;")
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                ok = bool(short and ru and cyrillic(short) and cyrillic(ru))
                if (ru if ok else "") != r["ru"]:
                    changed += 1
                r["short"], r["ru"], r["checked"] = (short, ru, True) if ok else ("", "", True)
    for r in rows:
        r.pop("checked", None)
    with open(path, "w", encoding="utf-8") as out:
        out.write("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    print(json.dumps({"checked": len(todo), "changed": changed}))


def selection():
    rows, have = [], set()
    for line in open(DICT, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[2]:
            have.add(f[0])
        if f[1] == "гл." and f[5] and " " not in f[0] and not f[0].endswith("se") and f[2]:
            rows.append((f[0], f[2], f[3], int(f[5])))
    rows.sort(key=lambda r: r[3])
    return [r for r in rows if r[0] + "se" not in have]


def call(batch, key, model):
    lines = "\n".join(f"{w}\tру: {ru[:150]}\tангл: {en[:200]}" for w, ru, en, _ in batch)
    body = {
        "model": model, "temperature": 0.2, "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": "Глагол\tрусские\tанглийские:\n" + lines}],
    }
    req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=240) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"]).get("items", [])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=40)
    ap.add_argument("--model", default="deepseek-flash")
    ap.add_argument("--shard", default="0/1")
    ap.add_argument("--verify", action="store_true", help="a second look at the drafts in --out")
    args = ap.parse_args()
    if args.verify:
        return verify(args.out, args.model)
    k, n = (int(x) for x in args.shard.split("/"))
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    # The whole selection is split first, then what is done is left out: shards never overlap on a resume.
    done = set()
    if os.path.exists(args.out):
        done = {json.loads(l)["w"] for l in open(args.out, encoding="utf-8") if l.strip()}
    todo = [r for r in selection()[k::n] if r[0] not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(done)} done, {len(todo)} to go", flush=True)
    with open(args.out, "a", encoding="utf-8") as out:
        for i in range(0, len(todo), args.batch):
            batch = todo[i: i + args.batch]
            items = []
            for attempt in range(4):
                try:
                    items = call(batch, key, args.model)
                    break
                except (urllib.error.URLError, json.JSONDecodeError, KeyError, OSError) as e:
                    if attempt == 3:
                        print(f"batch {i}: giving up: {e}", file=sys.stderr)
                    else:
                        time.sleep(2 ** (attempt + 1))
            asked = {r[0] for r in batch}
            seen = set()
            for it in items:
                w = str(it.get("w", "")).strip().removesuffix("se") if str(it.get("w", "")).strip() not in asked else str(it.get("w", "")).strip()
                short = " ".join(str(it.get("short", "")).split(";")[0].split()).strip(" ;")
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                if w in asked and w not in seen:
                    seen.add(w)
                    ok = bool(short and ru and cyrillic(short) and cyrillic(ru))
                    out.write(json.dumps({"w": w, "short": short if ok else "", "ru": ru if ok else ""}, ensure_ascii=False) + "\n")
            out.flush()
            print(f"{min(i + args.batch, len(todo))}/{len(todo)}, answered {len(seen)}/{len(batch)}", flush=True)


if __name__ == "__main__":
    main()
