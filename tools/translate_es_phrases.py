#!/usr/bin/env python3
"""Russian meanings of Spanish set phrases the English Wiktionary lacks, from the Spanish Wiktionary.

The Spanish Wiktionary has thousands of locutions (ir a la yugular, no tener ni cobre, de todo en
todo) with definitions in Spanish. Those of two to four words missing from dict.tsv are translated
into Russian by the DeepSeek chat API from their Spanish definitions.

Input: the multi-word entries of the kaikki.org extract of the Spanish Wiktionary
(https://kaikki.org/eswiktionary/Español/, CC BY-SA 4.0), one JSON per line:
{"w": phrase, "pos": pos, "senses": [{"g": definition, "tags": [...], "form_of": bool}]}
(tools/translate_es_phrases.py --extract < kaikki.org-dictionary-Español.jsonl > phrases.jsonl).
Output: data/es/ru_phrases.jsonl: {"w": phrase, "pos": "гл.", "short": "…", "ru": "…; …"};
build_es_dict.py takes it in for phrases that have no Russian meaning yet.

Usage: python3 tools/translate_es_phrases.py phrases.jsonl --out data/es/ru_phrases.jsonl [--shard 0/4]
       python3 tools/translate_es_phrases.py phrases.jsonl --out data/es/ru_phrases.jsonl --verify   # a second look
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import build_es_dict as b  # noqa: E402
from refine_es_ru import API, DICT, cyrillic  # noqa: E402

POS = {"noun": "сущ.", "intj": "межд.", "proverb": "пословица", "adv": "нар.", "adj": "прил.", "verb": "гл."}

SYSTEM = (
    "Ты редактор испанско-русского словаря. Даны испанские устойчивые выражения с толкованиями на испанском "
    "из Викисловаря; в скобках перед толкованием — где так говорят и стиль. Для каждого выражения верни:\n"
    "short — основное значение по-русски, 1–4 слова, без помет и скобок;\n"
    "ru — от 1 до 3 значений через «; », каждое из 1–6 слов: точный перевод смысла, по возможности русским "
    "устойчивым выражением того же стиля (no tener ni cobre: «быть без гроша»); глагольное выражение — инфинитивом. "
    "Региональному или стилистически окрашенному значению поставь в начале помету в скобках по-русски, кратко: "
    "«(Арг., разг.) …», «(Мекс.) …», «(груб.) …». Не переводи дословно, если смысл переносный. Если толкование "
    "непонятно или это не выражение, а имя, фамилия или название, верни пустые short и ru. "
    "Пиши только по-русски. Ответ — только JSON-объект {\"items\":[{\"w\":выражение,\"short\":…,\"ru\":…}]} в том же порядке."
)


VERIFY = (
    "Ты редактор испанско-русского словаря и хорошо знаешь испанскую фразеологию. Даны испанские устойчивые выражения "
    "и их русские переводы, сделанные по толкованиям Викисловаря; толкования иногда ошибочны. Проверь каждый перевод по "
    "тому, что выражение действительно значит в живом испанском (hablar entre dientes — «бормотать сквозь зубы», а не "
    "«говорить напрямик»). Если перевод верен, верни его без изменений; если неверен или неточен — исправь. Пометы в "
    "скобках сохраняй. Формат тот же: short — 1–4 слова без помет; ru — 1–3 значения через «; ». Пиши только по-русски. "
    "Ответ — только JSON-объект {\"items\":[{\"w\":выражение,\"short\":…,\"ru\":…}]} в том же порядке."
)


def verify(path, model, batch=40):
    """A second look at the translated phrases: [path] is rewritten with the corrected ones."""
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    todo = [r for r in rows if r["ru"] and not r.get("checked")]
    changed = 0
    for i in range(0, len(todo), batch):
        part = todo[i: i + batch]
        body = {
            "model": model, "temperature": 0.1, "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
            "messages": [{"role": "system", "content": VERIFY},
                         {"role": "user", "content": "Выражение\tперевод:\n" + "\n".join(f"{r['w']}\t{r['ru']}" for r in part)}],
        }
        req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        try:
            with urllib.request.urlopen(req, timeout=240) as resp:
                items = json.loads(json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]).get("items", [])
        except (urllib.error.URLError, json.JSONDecodeError, KeyError, OSError) as e:
            print(f"batch {i}: {e}", file=sys.stderr)
            continue
        by = {r["w"]: r for r in part}
        for it in items:
            r = by.get(str(it.get("w", "")).strip())
            short = " ".join(str(it.get("short", "")).split()).strip(" ;")
            ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
            if r is None or not (short and ru and cyrillic(short) and cyrillic(ru)):
                continue
            if ru != r["ru"]:
                changed += 1
                print(f"{r['w']}: {r['ru']} -> {ru}", flush=True)
            r["short"], r["ru"], r["checked"] = short, ru, True
        with open(path, "w", encoding="utf-8") as out:
            out.write("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    print(json.dumps({"checked": len(todo), "changed": changed}))


def extract():
    for line in sys.stdin:
        if '"word": "' not in line:
            continue
        e = json.loads(line)
        w = e.get("word", "")
        if " " not in w.strip() or e.get("lang_code") != "es":
            continue
        senses = [{"g": (s.get("glosses") or [""])[0], "tags": s.get("tags", []), "form_of": bool(s.get("form_of"))}
                  for s in e.get("senses", []) if (s.get("glosses") or [""])[0]]
        print(json.dumps({"w": w, "pos": e.get("pos"), "senses": senses}, ensure_ascii=False))


def selection(path):
    have = {}
    for line in open(DICT, encoding="utf-8"):
        f = line.split("\t")
        have[f[0]] = f[2]
    rows, seen = [], set()
    for line in open(path, encoding="utf-8"):
        e = json.loads(line)
        w = " ".join(e["w"].split())
        n = len(w.split())
        if e["pos"] in ("name", "abbrev", "unknown") or not 2 <= n <= 4 or w != w.lower() or w in seen or have.get(w):
            continue
        if not re.fullmatch(r"[^\W\d_]+( [^\W\d_]+)+", w):
            continue
        senses = []
        for s in e["senses"]:
            if s["form_of"] or b.SKIP_TAGS & set(s["tags"]) or re.match(r"(Apellido|Variante|Grafía)", s["g"]):
                continue
            lab = b.label(s["tags"])
            senses.append((f"({lab}) " if lab else "") + s["g"][:220])
        if not senses:
            continue
        seen.add(w)
        pos = POS.get(e["pos"]) or ("гл." if re.search(r"(ar|er|ir|ír)(se|los?|las?|le)?$", w.split()[0]) else "выраж.")
        rows.append((w, pos, senses[:3]))
    return rows


def call(batch, key, model):
    lines = "\n".join(f"{w}\t" + " | ".join(senses) for w, _, senses in batch)
    body = {
        "model": model, "temperature": 0.2, "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": "Выражение\tтолкования:\n" + lines}],
    }
    req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=240) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"]).get("items", [])


def main():
    if "--extract" in sys.argv:
        return extract()
    ap = argparse.ArgumentParser()
    ap.add_argument("phrases")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=30)
    ap.add_argument("--model", default="deepseek-flash")
    ap.add_argument("--shard", default="0/1")
    ap.add_argument("--verify", action="store_true", help="check the translations in --out against the real meaning of the phrases")
    args = ap.parse_args()
    if args.verify:
        return verify(args.out, args.model)
    k, n = (int(x) for x in args.shard.split("/"))
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    done = set()
    if os.path.exists(args.out):
        done = {json.loads(l)["w"] for l in open(args.out, encoding="utf-8") if l.strip()}
    todo = [r for r in selection(args.phrases) if r[0] not in done][k::n]
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
            asked = {r[0]: r[1] for r in batch}
            seen = set()
            for it in items:
                w = str(it.get("w", "")).strip()
                short = " ".join(str(it.get("short", "")).split()).strip(" ;")
                ru = " ".join(str(it.get("ru", "")).split()).strip(" ;")
                if w in asked and w not in seen:
                    seen.add(w)
                    # An empty answer is kept too (a name, an unclear definition), so it is not asked again.
                    ok = bool(short and ru and cyrillic(short) and cyrillic(ru))
                    out.write(json.dumps({"w": w, "pos": asked[w], "short": short if ok else "", "ru": ru if ok else ""}, ensure_ascii=False) + "\n")
            out.flush()
            print(f"{min(i + args.batch, len(todo))}/{len(todo)}, answered {len(seen)}/{len(batch)}", flush=True)


if __name__ == "__main__":
    main()
