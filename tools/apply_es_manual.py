#!/usr/bin/env python3
"""Apply hand-checked Spanish fixes to the app's dictionary (assets/es/dict.tsv, forms.tsv).

The reader sends words whose translation was wrong in a real book («Переводы под вопросом»), or a
whole book is checked; fixes go into two files:

data/es/manual.tsv — dictionary words and phrases, one per line:
  lemma \t part of speech \t Russian senses (" ; " between) \t main meaning (over the word) \t English \t other parts of speech \t note
  - A lemma the dictionary has: the given columns replace its own (empty ones are kept); "-" in
    «other parts of speech» clears them. The source becomes "h" (checked by hand).
  - A new lemma (a phrase like «en lo que se refiere a»): part of speech and Russian senses are needed.
  - Other parts of speech: «сущ.|главное|значения ; через ; точку с запятой|English», several joined by « || ».

data/es/forms_manual.tsv — forms: form \t analyses ("lemma:grammar line|…", as in forms.tsv);
  "+lemma:n" adds an analysis after the existing ones instead of replacing them.

And one rule over all forms: a feminine or plural participle that leads only to the adjective
(contenidas → contenido) also gets the verb (contener, «мн. ч., ж. р., прош. причастие»), after the
adjective. Masculine participles already lead to the verb.

Run after editing; it is idempotent:
  python3 tools/apply_es_manual.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT = os.path.join(ROOT, "app/src/main/assets/es/dict.tsv")
FORMS = os.path.join(ROOT, "app/src/main/assets/es/forms.tsv")
GRAMMAR = os.path.join(ROOT, "app/src/main/assets/es/grammar.tsv")
MANUAL = os.path.join(ROOT, "data/es/manual.tsv")
FORMS_MANUAL = os.path.join(ROOT, "data/es/forms_manual.tsv")
COLUMNS = 9  # lemma, pos, ru, en, source, rank, short, gender, alts


def rows(path):
    if not os.path.exists(path):
        return []
    out = []
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        out.append((n, line.split("\t")))
    return out


def write_sorted(path, table):
    # The app finds lines by binary search over their UTF-8 bytes: keep that order.
    with open(path, "w", encoding="utf-8") as f:
        for key in sorted(table, key=lambda k: k.encode("utf-8")):
            f.write(key + "\t" + table[key] + "\n")


def main():
    dict_rows = {}
    for line in open(DICT, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        p += [""] * (COLUMNS - len(p))
        dict_rows[p[0]] = p
    words = 0
    for n, p in rows(MANUAL):
        p = [x.strip() for x in p] + [""] * 7
        lemma, pos, ru, short, en, alts = p[0].lower() if not p[1] == "имя" else p[0], p[1], p[2], p[3], p[4], p[5]
        if not lemma:
            sys.exit(f"data/es/manual.tsv:{n}: no lemma")
        e = dict_rows.get(lemma)
        if e is None:
            if not pos or not ru:
                sys.exit(f"data/es/manual.tsv:{n}: {lemma} is new: give its part of speech and Russian senses")
            e = [lemma, pos, "", "", "h", "", "", "", ""]
            dict_rows[lemma] = e
        if pos:
            e[1] = pos
        if ru:
            e[2] = ru
        if en:
            e[3] = en
        if short:
            e[6] = short
        elif ru and not e[6]:
            e[6] = ru.split(" ; ")[0]
        if alts == "-":
            e[8] = ""
        elif alts:
            e[8] = alts
        if ru or short:
            e[4] = "h"
        words += 1
    with open(DICT, "w", encoding="utf-8") as f:
        for key in sorted(dict_rows, key=lambda k: k.encode("utf-8")):
            f.write("\t".join(dict_rows[key][:COLUMNS]) + "\n")

    forms = {}
    for line in open(FORMS, encoding="utf-8"):
        k, _, v = line.rstrip("\n").partition("\t")
        forms[k] = v
    fixed = 0
    for n, p in rows(FORMS_MANUAL):
        if len(p) < 2:
            sys.exit(f"data/es/forms_manual.tsv:{n}: form \\t analyses")
        form, value = p[0].strip().lower(), p[1].strip()
        if value.startswith("+"):
            old = forms.get(form, "")
            add = value[1:]
            if add.split(":")[0] not in [x.lstrip("!").split(":")[0] for x in old.split("|") if x]:
                forms[form] = (old + "|" + add) if old else add
        else:
            forms[form] = value
        fixed += 1

    # Feminine and plural participles: the verb beside the adjective.
    grammar = [l.rstrip("\n") for l in open(GRAMMAR, encoding="utf-8")]
    line_of = {g: i for i, g in enumerate(grammar)}
    participle = {"a": line_of["ед. ч., ж. р., прош. причастие"], "as": line_of["мн. ч., ж. р., прош. причастие"],
                  "os": line_of["мн. ч., м. р., прош. причастие"]}
    added = 0
    for form, value in list(forms.items()):
        m = re.match(r"^(.*(?:ad|id|íd))(a|as|os)$", form)
        if not m:
            continue
        masc = m.group(1) + "o"
        items = [x for x in value.split("|") if x]
        lemmas = [x.lstrip("!").split(":")[0] for x in items]
        if masc not in lemmas:
            continue
        # The verb the masculine participle leads to (contenido → contener).
        verb = next((x.lstrip("!").split(":")[0] for x in forms.get(masc, "").split("|")
                     if x and x.lstrip("!").split(":")[1:2] == ["11"]), None)
        if verb is None or verb in lemmas or verb not in dict_rows:
            continue
        forms[form] = value + "|" + f"{verb}:{participle[m.group(2)]}"
        added += 1
    write_sorted(FORMS, forms)
    print(f"{words} dictionary lines, {fixed} forms by hand, {added} participles linked to their verb")


if __name__ == "__main__":
    main()
