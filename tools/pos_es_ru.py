#!/usr/bin/env python3
"""Russian meanings by part of speech for Spanish words that are several parts of speech.

The dictionary keeps one row per word, and the Russian senses of «haber» were those of the verb
only: «bienes y haberes» read as a form of the verb. For every word the English Wiktionary gives
under two or more parts of speech (haber: verb, noun; poder; deber; joven: adjective, noun), the
DeepSeek chat API returns the Russian senses of each part of speech separately. With --narrow, also
words of one part of speech with a single Russian sense where Wiktionary has several.

Input: the kaikki.org extract of the English Wiktionary (see build_es_dict.py) and the built
dict.tsv. Output: data/es/ru_pos.jsonl, one line per word:
{"w": "haber", "pos": {"гл.": {"short": "…", "ru": "…; …"}, "сущ.": {"short": "имущество", "ru": "…"}}};
build_es_dict.py takes it in. Runs can be resumed and split (--shard k/n). Derivative of Wiktionary, CC BY-SA 4.0.

Usage: python3 tools/pos_es_ru.py <en.jsonl> --out data/es/ru_pos.jsonl [--narrow] [--shard 0/4]
"""
import argparse
import collections
import json
import os
import sys
import time
import urllib.error

sys.path.insert(0, os.path.dirname(__file__))
import build_es_dict as b  # noqa: E402
from refine_es_ru import API, DICT, cyrillic  # noqa: E402
import urllib.request  # noqa: E402

SYSTEM = (
    "Ты редактор испанско-русского словаря. Для каждого испанского слова даны нынешние русские значения "
    "(все части речи вперемешку, бывают неполные и ошибочные) и английские значения из Викисловаря, отдельно "
    "по частям речи; у английских в скобках — где так говорят и стиль («(Мексика, разг.) work»). "
    "Верни для КАЖДОЙ данной части речи отдельно:\n"
    "short — самое распространённое значение этой части речи, 1–3 русских слова, без помет и скобок;\n"
    "ru — от 1 до 4 значений по убыванию распространённости, через «; », каждое из 1–5 слов. Не сужай: если у слова "
    "несколько употребительных значений, дай их все (до четырёх). Значения должны быть той же части речи: "
    "для «сущ.» — существительные (haber сущ.: «имущество; актив; кредит; жалованье»), для «гл.» — инфинитивы, "
    "для «прил.» — прилагательные. Региональному или стилистически окрашенному значению поставь в начале ту же "
    "помету в скобках по-русски, кратко: «(Мекс., разг.) работа», «(Исп.) компьютер», «(Лат. Ам.) автомобиль». "
    "Нейтральные значения — без помет и первыми. Не выдумывай значений, которых нет в исходниках. Пиши только по-русски. "
    "Ответ — только JSON-объект {\"items\":[{\"w\":слово,\"pos\":{\"гл.\":{\"short\":…,\"ru\":…},\"сущ.\":{\"short\":…,\"ru\":…}}}]} "
    "в том же порядке, с теми же названиями частей речи."
)


def english_by_pos(path):
    out = collections.OrderedDict()
    for line in open(path, encoding="utf-8"):
        e = json.loads(line)
        w, p = e.get("word", "").strip().lower(), b.POS.get(e.get("pos"))
        if not w or not p or p == "имя":
            continue
        for s in e.get("senses", []):
            tags = s.get("tags", [])
            if s.get("form_of") or "form-of" in tags or b.SKIP_TAGS & set(tags):
                continue
            lab = b.label(tags)
            for g in s.get("glosses", []):
                g = b.clean(g)
                if lab:
                    g = f"({lab}) {g}"
                senses = out.setdefault(w, collections.OrderedDict()).setdefault(p, [])
                if g and g not in senses:
                    senses.append(g)
    return out


def selection(en_path, narrow):
    ru = {}
    for line in open(DICT, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        ru[f[0]] = (f[2], f[5] if len(f) > 5 else "")
    rows = []
    for w, by in english_by_pos(en_path).items():
        if w not in ru or w in b.MANUAL_RU:
            continue
        r, rank = ru[w]
        if len(by) > 1:
            rows.append((w, r, by))
        elif narrow and rank and len([x for x in r.split(" ; ") if x]) == 1 and len(next(iter(by.values()))) >= 2:
            rows.append((w, r, by))
    return rows


def call(batch, key, model):
    lines = []
    for w, r, by in batch:
        en = " | ".join(f"{p} " + "; ".join(s[:6])[:320] for p, s in by.items())
        lines.append(f"{w}\tру: {r[:200]}\tангл: {en}")
    body = {
        "model": model, "temperature": 0.2, "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": "Слово\tрусские\tанглийские по частям речи:\n" + "\n".join(lines)}],
    }
    req = urllib.request.Request(API, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=240) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return json.loads(data["choices"][0]["message"]["content"]).get("items", [])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("en")
    ap.add_argument("--out", required=True)
    ap.add_argument("--narrow", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=25)
    ap.add_argument("--model", default="deepseek-flash")
    ap.add_argument("--shard", default="0/1")
    args = ap.parse_args()
    k, n = (int(x) for x in args.shard.split("/"))
    key = os.environ.get("DEEPSEEK_API_KEY", "placeholder-inserted-by-proxy")
    done = set()
    if os.path.exists(args.out):
        done = {json.loads(l)["w"] for l in open(args.out, encoding="utf-8") if l.strip()}
    todo = [r for r in selection(args.en, args.narrow) if r[0] not in done][k::n]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(done)} done, {len(todo)} to go", flush=True)
    written = 0
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
            asked = {r[0]: r[2] for r in batch}
            seen = set()
            for it in items:
                w = str(it.get("w", "")).strip()
                got = it.get("pos")
                if w not in asked or w in seen or not isinstance(got, dict):
                    continue
                clean = {}
                for p in asked[w]:
                    v = got.get(p)
                    if not isinstance(v, dict):
                        continue
                    short = " ".join(str(v.get("short", "")).split()).strip(" ;")
                    ru = " ".join(str(v.get("ru", "")).split()).strip(" ;")
                    if short and ru and cyrillic(short) and cyrillic(ru):
                        clean[p] = {"short": short, "ru": ru}
                # Every part of speech answered, or the word is asked again on the next run.
                if len(clean) == len(asked[w]):
                    seen.add(w)
                    out.write(json.dumps({"w": w, "pos": clean}, ensure_ascii=False) + "\n")
                    written += 1
            out.flush()
            print(f"{min(i + args.batch, len(todo))}/{len(todo)}, answered {len(seen)}/{len(batch)}", flush=True)
    print(json.dumps({"written": written}))


if __name__ == "__main__":
    main()
