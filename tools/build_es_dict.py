#!/usr/bin/env python3
"""Build the Spanish dictionary: app/src/main/assets/es/dict.tsv and forms.tsv.

Inputs, kaikki.org wiktextract extracts (Wiktionary text: CC BY-SA 4.0, see
assets/licenses/ES_WIKTIONARY_NOTICE.txt):
  ru.jsonl  Spanish words of the Russian Wiktionary (https://kaikki.org/ruwiktionary/Испанский/)
  en.jsonl  Spanish words of the English Wiktionary (https://kaikki.org/dictionary/Spanish/)

dict.tsv   lemma \t part of speech \t Russian senses \t English senses \t Russian source \t frequency rank \t main meaning \t gender \t other parts of speech
           (senses joined by " ; "; source: w = Russian Wiktionary, a = machine translation (ru_ai.jsonl),
           r = Wiktionary or machine senses edited by the second pass (ru_refined.jsonl);
           rank: 1 = most frequent, empty = not in the frequency list; main meaning: 1-3 words, edited pass only;
           gender of a noun: м, ж, мж (both), from the first noun entry of the English Wiktionary)
forms.tsv  form \t lemma:n|lemma:n   (forms that differ from the lemma; n = line of grammar.tsv, 0 = none)
grammar.tsv  one description of a form per line: «3 л. ед. ч., претерит»
Verbs with attached pronouns (dámelo) are left out: the app strips the pronouns itself. Voseo forms
(sos, tenés, andás: Argentina, Uruguay) are kept and marked.
Both lower-case, sorted by UTF-8 bytes (the app binary-searches them).

Also read from data/es (committed):
  es_50k.txt    word frequencies of Spanish subtitles (FrequencyWords, OpenSubtitles 2018, CC BY-SA 4.0)
  ru_ai.jsonl   Russian meanings by machine translation (tools/translate_es_ru.py), for words the
                Russian Wiktionary lacks
  ru_refined.jsonl  frequent words: main meaning and senses in order of use (tools/refine_es_ru.py)

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
    "estado", "visto", "puesto", "río", "haya", "hayas", "hayan", "hayamos",
}
# Where a sense is used, and its register: «(Мексика, разг.) work».
REGIONS = {
    "Spain": "Испания", "Mexico": "Мексика", "Latin-America": "Лат. Америка", "Rioplatense": "Ла-Плата",
    "Argentina": "Аргентина", "Uruguay": "Уругвай", "Chile": "Чили", "Colombia": "Колумбия", "Peru": "Перу",
    "Venezuela": "Венесуэла", "Cuba": "Куба", "Central-America": "Центр. Америка", "Caribbean": "Карибы",
    "Canary-Islands": "Канары", "Andalusia": "Андалусия", "Philippines": "Филиппины", "US": "США",
    "Puerto-Rico": "Пуэрто-Рико", "Dominican-Republic": "Доминикана", "Ecuador": "Эквадор", "Bolivia": "Боливия",
    "Paraguay": "Парагвай", "Guatemala": "Гватемала", "Honduras": "Гондурас", "Nicaragua": "Никарагуа",
    "El-Salvador": "Сальвадор", "Costa-Rica": "Коста-Рика", "Panama": "Панама", "South-America": "Юж. Америка",
}
LATIN_AMERICA = set(REGIONS) - {"Spain", "Canary-Islands", "Andalusia", "Philippines", "US"}
REGISTER = {"colloquial": "разг.", "familiar": "разг.", "slang": "сленг", "vulgar": "груб.", "derogatory": "пренебр.",
            "dated": "устар.", "informal": "разг.", "offensive": "оскорб."}


def label(tags):
    """«Мексика, разг.» for a sense's tags; empty for a sense used everywhere in a neutral style."""
    t = set(tags)
    regions = [r for r in REGIONS if r in t]
    # Three or more Latin American countries: «Лат. Америка», with the others kept (Испания, Филиппины).
    latin = [r for r in regions if r in LATIN_AMERICA]
    if len(latin) >= 3:
        regions = ["Latin-America"] + [r for r in regions if r not in LATIN_AMERICA]
    regions = regions[:3]
    words = [REGIONS[r] for r in regions]
    reg = []
    for k, v in REGISTER.items():
        if k in t and v not in reg:
            reg.append(v)
    return ", ".join(words + reg)


def strip_label(x):
    """«(Мекс., разг.) работа» -> «работа», lower-case."""
    return re.sub(r"^\([^)]*\)\s*", "", x.strip()).lower()


SKIP_TAGS = {"obsolete", "archaic", "rare", "proscribed", "misspelling", "nonstandard"}


def clean(text):
    text = re.sub(r"\s+", " ", text.replace("\t", " ")).strip(" ;,.")
    if len(text) > MAX_SENSE_LEN:
        text = text[:MAX_SENSE_LEN].rsplit(" ", 1)[0] + "…"
    return text


REFLEXIVE = {("1 л.", "ед. ч."): "me", ("2 л.", "ед. ч."): "te", ("3 л.", "ед. ч."): "se",
             ("1 л.", "мн. ч."): "nos", ("2 л.", "мн. ч."): "os", ("3 л.", "мн. ч."): "se"}


def describe(tags):
    t = set(tags)
    parts = []
    # An infinitive or gerund has no person of its own: a person here is the reflexive pronoun
    # attached to it (levantarme, levantándote) - «инфинитив + me», not «1 л. ед. ч., инфинитив».
    if "infinitive" in t or "gerund" in t:
        form = "инфинитив" if "infinitive" in t else "герундий"
        person = next((v for k, v in PERSON.items() if k in t), None)
        number = next((v for k, v in NUMBER.items() if k in t), None)
        pron = REFLEXIVE.get((person, number)) if person and number else None
        return f"{form} + {pron}" if pron else form
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
    regional = set()  # words with a sense used only in some countries or in a marked style
    gender = {}       # noun -> м / ж / мж
    en_pos = {}       # lemma -> {part of speech: [senses]}
    form_pos = {}     # (form, lemma) -> parts of speech of the form (haberes: a noun; reflectores: a noun and an adjective)

    def set_pos(w, p):
        # A common word first: «al» is a contraction before it is an initialism (имя); an infinitive
        # that is also a noun (poder, querer) is a verb.
        if p in POS and (w not in pos or (pos[w] == "имя" and POS[p] != "имя") or (POS[p] == "гл." and pos[w] == "сущ.")):
            pos[w] = POS[p]

    def add_form(form, lemma, desc, part=None):
        form, lemma = form.lower().strip(), lemma.lower().strip()
        if not form or form == lemma or " " in form or not re.fullmatch(r"[^\W\d_]+", form):
            return
        d = forms.setdefault(form, {})
        if lemma not in d or (desc and not d[lemma]):
            d[lemma] = desc
        if part:
            form_pos.setdefault((form, lemma), []).append(part)

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
                if SKIP_TAGS & set(tags) or any(t.startswith("object-") for t in tags):
                    continue
                for f in s["form_of"]:
                    add_form(w, f.get("word", ""), describe(tags), POS.get(e.get("pos")))
                continue
            if "form-of" in tags or SKIP_TAGS & set(tags):
                continue
            lemma_entry = True
            lab = label(tags)
            for g in s.get("glosses", []):
                g = clean(g)
                if lab:
                    g = f"({lab}) {g}"
                    regional.add(w)
                if g and g not in en.setdefault(w, []):
                    en[w].append(g)
                by = en_pos.setdefault(w, {}).setdefault(POS.get(e.get("pos"), ""), [])
                if g and g not in by:
                    by.append(g)
        if lemma_entry:
            set_pos(w, e.get("pos"))
            if e.get("pos") == "noun" and w not in gender:
                g = next((h.get("args", {}).get("1", "") for h in e.get("head_templates", []) if h.get("args", {}).get("1", "") not in ("", "es")), "")
                gender[w] = "мж" if g.startswith("mf") or g in ("m-f", "fm") else "м" if g.startswith("m") else "ж" if g.startswith("f") else ""

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
    short = {}
    # Later files win: the regional pass (labels in the Russian senses) over the general edit.
    for refined_path in (os.path.join(data, "ru_refined.jsonl"), os.path.join(data, "ru_regional.jsonl")):
        if not os.path.exists(refined_path):
            continue
        for line in open(refined_path, encoding="utf-8"):
            if not line.strip():
                continue
            it = json.loads(line)
            senses = [x.strip() for x in it["ru"].split(";") if x.strip()]
            if senses and it["w"] not in MANUAL_RU:
                ru[it["w"]] = senses
                source[it["w"]] = "r"
                short[it["w"]] = it["short"].strip()
    # Words that are several parts of speech (haber: verb and noun «имущество»; pos_es_ru.py): the main
    # part of speech keeps the row, the others go to the last column, each with its own senses.
    alts = {}
    pos_path = os.path.join(data, "ru_pos.jsonl")
    if os.path.exists(pos_path):
        for line in open(pos_path, encoding="utf-8"):
            if not line.strip():
                continue
            it = json.loads(line)
            w, by = it["w"], it["pos"]
            if w in MANUAL_RU or w not in pos:
                continue
            # The main part of speech is the one of the word's main meaning so far (médico «врач»: the
            # noun, not the adjective; este «этот», not «восток»); with no match it stays as it was.
            was = [strip_label(x) for x in (short.get(w, "").split(";") + ru.get(w, [])[:1]) if x.strip()]
            def match(part):
                v = by[part]
                senses = [strip_label(x) for x in v["ru"].split(";")]
                return max([3 - k for k, x in enumerate(was[:2]) if x == strip_label(v["short"])] +
                           [1 for x in was[:2] if x in senses] + [0])
            best = max(by, key=lambda part: (match(part), part == pos[w]))
            if match(best) > 0:
                pos[w] = best
            # An edited frequent word whose main meaning matches none of the new ones keeps its senses
            # (haber «вспомогательный глагол», el «определённый артикль…»); it only gains the other parts of speech.
            keep = match(best) == 0 and w in short
            for part, v in by.items():
                senses = [x.strip().replace("|", "/") for x in v["ru"].split(";") if x.strip()]
                if part == pos[w]:
                    if keep:
                        continue
                    ru[w], source[w], short[w] = senses, "r", strip_label(v["short"]) if v["short"].startswith("(") else v["short"].strip()
                    if en_pos.get(w, {}).get(part):
                        en[w] = en_pos[w][part]
                elif senses:
                    e = [x.replace("|", "/") for x in en_pos.get(w, {}).get(part, [])[:MAX_SENSES]]
                    alts.setdefault(w, []).append((part, re.sub(r"^\([^)]*\)\s*", "", v["short"].strip()).replace("|", "/"), senses[:MAX_SENSES], e))
    # Set phrases of the Spanish Wiktionary the English one lacks (translate_es_phrases.py).
    phrases_path = os.path.join(data, "ru_phrases.jsonl")
    if os.path.exists(phrases_path):
        for line in open(phrases_path, encoding="utf-8"):
            if not line.strip():
                continue
            it = json.loads(line)
            w = it["w"]
            if not it["ru"] or ru.get(w):
                continue
            ru[w] = [x.strip() for x in it["ru"].split(";") if x.strip()]
            source[w], short[w] = "a", it["short"]
            # A verb phrase starts with a dictionary verb (ir a la yugular; «cráter volcánico» does not).
            first = w.split()[0]
            verb = pos.get(first) == "гл." or (first.endswith("se") and pos.get(first[:-2]) == "гл.")
            pos.setdefault(w, "гл." if verb else "выраж." if it["pos"] == "гл." else it["pos"])
    # Pronominal verbs (limitarse «ограничиваться», reflexive_es_ru.py) the dictionary has no entry for.
    reflexive_path = os.path.join(data, "ru_reflexive.jsonl")
    if os.path.exists(reflexive_path):
        for line in open(reflexive_path, encoding="utf-8"):
            if not line.strip():
                continue
            it = json.loads(line)
            w = it["w"] + "se"
            if not it["ru"] or ru.get(w) or pos.get(it["w"]) != "гл.":
                continue
            ru[w] = [x.strip() for x in it["ru"].split(";") if x.strip()]
            source[w], short[w], pos[w] = "a", it["short"], "гл."
    # Idioms Wiktionary lacks (traer cola «иметь последствия»), written for HanReader; a Wiktionary
    # entry with a Russian meaning wins.
    idioms_path = os.path.join(data, "idioms.tsv")
    if os.path.exists(idioms_path):
        for line in open(idioms_path, encoding="utf-8"):
            if line.startswith("#") or not line.strip():
                continue
            w, p, senses, hint = line.rstrip("\n").split("\t")
            if ru.get(w):
                continue
            ru[w] = [x.strip() for x in senses.split(";") if x.strip()]
            source[w] = "a"
            short[w] = hint
            pos.setdefault(w, p)
    with open(os.path.join(data, "regional_words.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(regional)) + "\n")

    lemmas = sorted(set(ru) | set(en), key=lambda x: x.encode("utf-8"))
    rank = frequency_ranks(os.path.join(data, "es_50k.txt"), set(lemmas), forms, ru)
    with open(f"{out_dir}/dict.tsv", "w", encoding="utf-8") as f:
        n = 0
        for w in lemmas:
            r = ru.get(w, [])[:MAX_SENSES]
            g = en.get(w, [])[:MAX_SENSES]
            if not r and not g:
                continue
            alt = " || ".join(f"{part}|{sh}|{' ; '.join(senses)}|{' ; '.join(e)}" for part, sh, senses, e in alts.get(w, []))
            f.write(f"{w}\t{pos.get(w, '')}\t{' ; '.join(r)}\t{' ; '.join(g)}\t{source.get(w, '')}\t{rank.get(w, '')}\t{short.get(w, '')}\t{gender.get(w, '') if pos.get(w) == 'сущ.' or any(a[0] == 'сущ.' for a in alts.get(w, [])) else ''}\t{alt}\n")
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
            hint = {}
            for l, d in forms[form].items():
                if l in known:
                    resolved.setdefault(l, d)
                    # The form belongs to another part of speech of the word than its main one (haberes: the noun).
                    # Only when it is not a form of the main one too (reflectores: noun and adjective - no hint).
                    parts = form_pos.get((form, l), [])
                    if parts and pos.get(l) not in parts and any(a[0] == parts[0] for a in alts.get(l, [])):
                        hint[l] = ":" + parts[0]
                elif l in forms:
                    for l2, d2 in forms[l].items():
                        if l2 in known:
                            resolved.setdefault(l2, d or d2)
            # The usual reading first: rare grammar (subjunctive, imperative, 2nd person) after a
            # plain one (ataques: the plural of ataque before a subjunctive of atacar), then by frequency.
            def rare(d):
                return any(x in d for x in ("субх.", "повел.", "2 л."))
            items = sorted(resolved.items(), key=lambda kv: (rare(kv[1]), rank.get(kv[0], 10**9)))
            items = [(l, d) for l, d in items if l in known][:3]
            if not items:
                continue
            for _, d in items:
                if d not in index:
                    index[d] = len(grammar)
                    grammar.append(d)
            f.write(form + "\t" + ("!" if form in FORM_FIRST else "") + "|".join(f"{l}:{index[d]}{hint.get(l, '')}" for l, d in items) + "\n")
            m += 1
    with open(f"{out_dir}/grammar.tsv", "w", encoding="utf-8") as f:
        f.write("\n".join(grammar) + "\n")
    print(f"{n} lemmas ({sum(1 for w in lemmas if ru.get(w))} with Russian), {m} forms -> {out_dir}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
