#!/usr/bin/env python3
"""Build the Spanish dictionary: app/src/main/assets/es/dict.tsv and forms.tsv.

Inputs, kaikki.org wiktextract extracts (Wiktionary text: CC BY-SA 4.0, see
assets/licenses/ES_WIKTIONARY_NOTICE.txt):
  ru.jsonl  Spanish words of the Russian Wiktionary (https://kaikki.org/ruwiktionary/Испанский/)
  en.jsonl  Spanish words of the English Wiktionary (https://kaikki.org/dictionary/Spanish/)

dict.tsv   lemma \t part of speech \t Russian senses \t English senses   (senses joined by " ; ")
forms.tsv  form \t lemma:n|lemma:n   (forms that differ from the lemma; n = line of grammar.tsv, 0 = none)
grammar.tsv  one description of a form per line: «3 л. ед. ч., претерит»
Verbs with attached pronouns (dámelo) and voseo forms are left out: the app strips the pronouns itself.
Both lower-case, sorted by UTF-8 bytes (the app binary-searches them).

Usage: python3 tools/build_es_dict.py <ru.jsonl> <en.jsonl> <out_dir>
"""
import json
import re
import sys

MAX_SENSES = 4
MAX_SENSE_LEN = 90

POS = {"noun": "сущ.", "verb": "гл.", "adj": "прил.", "adv": "нар.", "name": "имя", "pron": "мест.",
       "prep": "предл.", "conj": "союз", "intj": "межд.", "phrase": "выраж.", "det": "опред.",
       "article": "артикль", "num": "числ.", "proverb": "пословица", "particle": "частица",
       "contraction": "слияние"}

# Grammar of a form, in the order it is written: «3 л. ед. ч., претерит».
PERSON = {"first-person": "1 л.", "second-person": "2 л.", "third-person": "3 л."}
NUMBER = {"singular": "ед. ч.", "plural": "мн. ч."}
GENDER = {"masculine": "м. р.", "feminine": "ж. р."}
TENSE = [("present", "наст."), ("preterite", "претерит"), ("imperfect", "имперфект"), ("future", "буд."),
         ("conditional", "условн."), ("past", "прош.")]
MOOD = [("subjunctive", "субх."), ("imperative", "повел."), ("gerund", "герундий"),
        ("participle", "причастие"), ("infinitive", "инфинитив"), ("diminutive", "уменьш."),
        ("augmentative", "увелич."), ("superlative", "превосх.")]
# Russian meanings the Wiktionary lacks for the most frequent words of real books.
MANUAL_RU = {
    "del": ["de + el: из, от, о (перед словом мужского рода)"],
    "al": ["a + el: к, в, на (перед словом мужского рода)"],
}
SKIP_TAGS = {"obsolete", "archaic", "rare", "proscribed", "misspelling", "nonstandard"}


def clean(text):
    text = re.sub(r"\s+", " ", text.replace("\t", " ")).strip(" ;,.")
    if len(text) > MAX_SENSE_LEN:
        text = text[:MAX_SENSE_LEN].rsplit(" ", 1)[0] + "…"
    return text


def describe(tags):
    t = set(tags)
    parts = []
    person = next((v for k, v in PERSON.items() if k in t), None)
    number = next((v for k, v in NUMBER.items() if k in t and not any(x.startswith("object-") and x.endswith(k) for x in t)), None)
    if "object-singular" in t or "object-plural" in t or "object-first-person" in t:
        pass  # the pronouns attached to a verb (dámelo): left out of the description
    if person:
        parts.append(person + (" " + number if number else ""))
    elif number:
        parts.append(number)
    gender = next((v for k, v in GENDER.items() if k in t), None)
    if gender:
        parts.append(gender)
    tense = next((v for k, v in TENSE if k in t), None)
    mood = next((v for k, v in MOOD if k in t), None)
    if tense and mood:
        parts.append(f"{tense} {mood}")
    elif tense or mood:
        parts.append(tense or mood)
    if "with-voseo" in t:
        parts.append("восео")
    if any(x.startswith("object-") for x in t):
        parts.append("+ местоим.")
    return ", ".join(parts)


def main(ru_path, en_path, out_dir):
    ru = {}       # lemma -> [senses]
    en = {}       # lemma -> [senses]
    pos = {}      # lemma -> part of speech
    forms = {}    # form -> {lemma: description}

    def set_pos(w, p):
        # A common word first: «al» is a contraction before it is an initialism (имя).
        if p in POS and (w not in pos or (pos[w] == "имя" and POS[p] != "имя")):
            pos[w] = POS[p]

    def add_form(form, lemma, desc):
        form, lemma = form.lower().strip(), lemma.lower().strip()
        if not form or form == lemma or " " in form or not re.fullmatch(r"[^\W\d_]+", form):
            return
        d = forms.setdefault(form, {})
        if lemma not in d or (desc and not d[lemma]):
            d[lemma] = desc

    for line in open(ru_path, encoding="utf-8"):
        e = json.loads(line)
        w = e.get("word", "").strip().lower()
        if not w:
            continue
        for s in e.get("senses", []):
            for g in s.get("glosses", []):
                g = clean(g)
                if g and "?" not in g and g not in ru.setdefault(w, []):
                    ru[w].append(g)
        set_pos(w, e.get("pos"))
        for f in e.get("forms", []):
            add_form(f.get("form", ""), w, "")

    for line in open(en_path, encoding="utf-8"):
        e = json.loads(line)
        w = e.get("word", "").strip().lower()
        if not w:
            continue
        lemma_entry = False
        for s in e.get("senses", []):
            tags = s.get("tags", [])
            if s.get("form_of"):
                if SKIP_TAGS & set(tags) or "with-voseo" in tags or any(t.startswith("object-") for t in tags):
                    continue
                for f in s["form_of"]:
                    add_form(w, f.get("word", ""), describe(tags))
                continue
            if "form-of" in tags or SKIP_TAGS & set(tags):
                continue
            lemma_entry = True
            for g in s.get("glosses", []):
                g = clean(g)
                if g and g not in en.setdefault(w, []):
                    en[w].append(g)
        if lemma_entry:
            set_pos(w, e.get("pos"))

    for w, senses in MANUAL_RU.items():
        ru[w] = senses + [x for x in ru.get(w, []) if x not in senses]

    lemmas = sorted(set(ru) | set(en), key=lambda x: x.encode("utf-8"))
    with open(f"{out_dir}/dict.tsv", "w", encoding="utf-8") as f:
        n = 0
        for w in lemmas:
            r = ru.get(w, [])[:MAX_SENSES]
            g = en.get(w, [])[:MAX_SENSES]
            if not r and not g:
                continue
            f.write(f"{w}\t{pos.get(w, '')}\t{' ; '.join(r)}\t{' ; '.join(g)}\n")
            n += 1
    known = set(lemmas)
    grammar = [""]
    index = {"": 0}
    with open(f"{out_dir}/forms.tsv", "w", encoding="utf-8") as f:
        m = 0
        for form in sorted(forms, key=lambda x: x.encode("utf-8")):
            # Lemmas with a Russian meaning first, then the ones in the dictionary at all.
            # A form of a form (controlada -> controlado -> controlar): to the dictionary word, keeping
            # the outer description (feminine participle).
            resolved = {}
            for l, d in forms[form].items():
                if l in known:
                    resolved.setdefault(l, d)
                elif l in forms:
                    for l2, d2 in forms[l].items():
                        if l2 in known:
                            resolved.setdefault(l2, d or d2)
            items = sorted(resolved.items(), key=lambda kv: (kv[0] not in ru, kv[0] not in known))
            items = [(l, d) for l, d in items if l in known][:3]
            if not items or form in ru:
                continue
            for _, d in items:
                if d not in index:
                    index[d] = len(grammar)
                    grammar.append(d)
            f.write(form + "\t" + "|".join(f"{l}:{index[d]}" for l, d in items) + "\n")
            m += 1
    with open(f"{out_dir}/grammar.tsv", "w", encoding="utf-8") as f:
        f.write("\n".join(grammar) + "\n")
    print(f"{n} lemmas ({sum(1 for w in lemmas if ru.get(w))} with Russian), {m} forms -> {out_dir}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
