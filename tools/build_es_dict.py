#!/usr/bin/env python3
"""Build the Spanish dictionary: app/src/main/assets/es/dict.tsv and forms.tsv.

Inputs, kaikki.org wiktextract extracts (Wiktionary text: CC BY-SA 4.0, see
assets/licenses/ES_WIKTIONARY_NOTICE.txt):
  ru.jsonl  Spanish words of the Russian Wiktionary (https://kaikki.org/ruwiktionary/Испанский/)
  en.jsonl  Spanish words of the English Wiktionary (https://kaikki.org/dictionary/Spanish/)

dict.tsv   lemma \t part of speech \t Russian senses \t English senses \t Russian source \t frequency rank
           (senses joined by " ; "; source: w = Russian Wiktionary, a = machine translation (ru_ai.jsonl);
           rank: 1 = most frequent, empty = not in the frequency list)
forms.tsv  form \t lemma:n|lemma:n   (forms that differ from the lemma; n = line of grammar.tsv, 0 = none)
grammar.tsv  one description of a form per line: «3 л. ед. ч., претерит»
Verbs with attached pronouns (dámelo) and voseo forms are left out: the app strips the pronouns itself.
Both lower-case, sorted by UTF-8 bytes (the app binary-searches them).

Also read from data/es (committed):
  es_50k.txt    word frequencies of Spanish subtitles (FrequencyWords, OpenSubtitles 2018, CC BY-SA 4.0)
  ru_ai.jsonl   Russian meanings by machine translation (tools/translate_es_ru.py), for words the
                Russian Wiktionary lacks

Usage: python3 tools/build_es_dict.py <ru.jsonl> <en.jsonl> <out_dir>
"""
import os
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
# Frequent verb forms that are also rare words of their own (era «эра», son «мелодия», ha, sé «да»):
# in text they are almost always the verb, so the verb reading comes first (forms.tsv marks them with
# "!") and their frequency goes to the verb. Words like como («как», also a form of comer) are not
# here: the word itself comes first.
FORM_FIRST = {
    "es", "era", "eras", "eran", "son", "soy", "eres", "sea", "sean", "seas", "fue", "fui", "fuera", "fueron",
    "ha", "he", "has", "han", "había", "habían", "habías", "sé", "va", "vas", "ve", "ves", "dicho", "sido",
    "estado", "visto", "puesto", "río",
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


def frequency_ranks(path, lemmas, forms, ru):
    """Rank of each dictionary word by the summed frequency of its forms (hablo, habló -> hablar)."""
    counts = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        w, c = parts[0].lower(), int(parts[1])
        if w in lemmas and not (w in FORM_FIRST and w in forms):
            target = w
        elif w in forms:
            cands = sorted((l for l in forms[w] if l in lemmas), key=lambda l: l not in ru)
            target = cands[0] if cands else None
        else:
            target = None
        if target:
            counts[target] = counts.get(target, 0) + c
    ordered = sorted(counts, key=lambda w: -counts[w])
    return {w: i + 1 for i, w in enumerate(ordered)}


def main(ru_path, en_path, out_dir):
    ru = {}       # lemma -> [senses]
    en = {}       # lemma -> [senses]
    pos = {}      # lemma -> part of speech
    forms = {}    # form -> {lemma: description}

    def set_pos(w, p):
        # A common word first: «al» is a contraction before it is an initialism (имя); an infinitive
        # that is also a noun (poder, querer) is a verb.
        if p in POS and (w not in pos or (pos[w] == "имя" and POS[p] != "имя") or (POS[p] == "гл." and pos[w] == "сущ.")):
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
        # Its inflection tables are not used: verb tables hold bare endings ("o", "es" for abrir).

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
    source = {w: "w" for w in ru if ru[w]}
    data = os.path.join(os.path.dirname(__file__), "..", "data", "es")
    ai_path = os.path.join(data, "ru_ai.jsonl")
    if os.path.exists(ai_path):
        for line in open(ai_path, encoding="utf-8"):
            if not line.strip():
                continue
            it = json.loads(line)
            w, senses = it["w"], [x.strip() for x in it["ru"].split(";") if x.strip()]
            if senses and not ru.get(w):
                ru[w] = senses
                source[w] = "a"

    lemmas = sorted(set(ru) | set(en), key=lambda x: x.encode("utf-8"))
    rank = frequency_ranks(os.path.join(data, "es_50k.txt"), set(lemmas), forms, ru)
    with open(f"{out_dir}/dict.tsv", "w", encoding="utf-8") as f:
        n = 0
        for w in lemmas:
            r = ru.get(w, [])[:MAX_SENSES]
            g = en.get(w, [])[:MAX_SENSES]
            if not r and not g:
                continue
            f.write(f"{w}\t{pos.get(w, '')}\t{' ; '.join(r)}\t{' ; '.join(g)}\t{source.get(w, '')}\t{rank.get(w, '')}\n")
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
            if not items:
                continue
            for _, d in items:
                if d not in index:
                    index[d] = len(grammar)
                    grammar.append(d)
            f.write(form + "\t" + ("!" if form in FORM_FIRST else "") + "|".join(f"{l}:{index[d]}" for l, d in items) + "\n")
            m += 1
    with open(f"{out_dir}/grammar.tsv", "w", encoding="utf-8") as f:
        f.write("\n".join(grammar) + "\n")
    print(f"{n} lemmas ({sum(1 for w in lemmas if ru.get(w))} with Russian), {m} forms -> {out_dir}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
