#!/usr/bin/env python3
"""Build a compact, reproducible English dictionary from the audited Kaikki files.

dict.tsv: normalized key TAB JSON array of POS-specific entries.
forms.tsv: normalized surface TAB JSON array of typed lemma references.
Only definitions, translations, IPA and grammatical forms are copied. No examples,
quotations, etymologies or audio. See data/en/README.md and NOTICE.txt.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from audit_en_dictionary import (INFLECTION_TAGS, NON_LEMMA_LINKS, SKIP_POS,
                                 SKIP_TAGS, definitions, frequencies, normalize,
                                 records, spelling_redirect)

POS = {"noun", "verb", "adj", "adv", "pron", "det", "article", "prep", "conj",
       "intj", "num", "phrase", "proverb", "particle", "contraction", "name"}
WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’-][A-Za-zÀ-ÖØ-öø-ÿ]+)*'?")
CYRILLIC = re.compile(r"[А-Яа-яЁё]")


def valid_key(word):
    parts = word.split(" ")
    return 1 <= len(parts) <= 6 and all(WORD.fullmatch(p) for p in parts)


def clean(text, limit=300):
    value = re.sub(r"\s+", " ", text).strip()
    return value if len(value) <= limit else value[:limit].rsplit(" ", 1)[0] + "…"


def append_unique(target, values, limit):
    for value in values:
        if value and value not in target and len(target) < limit:
            target.append(value)


def russian_translations(entry):
    groups = defaultdict(list)
    translations = list(entry.get("translations", []))
    for sense in entry.get("senses", []):
        for translation in sense.get("translations", []):
            translations.append({"sense": " / ".join(sense.get("glosses", [])), **translation})
    for translation in translations:
        if (translation.get("lang_code") or translation.get("code")) != "ru":
            continue
        if set(translation.get("tags", [])) & SKIP_TAGS:
            continue
        word = unicodedata.normalize("NFC", translation.get("word", "")).replace("\u0301", "")
        if CYRILLIC.search(word):
            append_unique(groups[clean(translation.get("sense", ""))], [clean(word)], 6)
    return [{"g": sense, "r": words} for sense, words in list(groups.items())[:8]]


def valid_form_link(surface, lemma, pos, tags):
    if pos == "noun" and "plural" in tags and lemma.endswith("man"):
        return surface in (lemma[:-3] + "men", lemma + "s")
    return True


def sync_reader_data(out, source):
    """Sync small reviewed overlays; never regenerate/download the large source dictionaries."""
    manifest = out / "manifest.json"
    summary = json.loads(manifest.read_text(encoding="utf-8"))
    for name in ("manual.tsv", "context.tsv"):
        data = (source / name).read_bytes()
        (out / name).write_bytes(data)
        summary["files"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    manifest.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


class Builder:
    def __init__(self, frequency_rows):
        self.ranks = {word: rank for rank, word, _ in reversed(frequency_rows)}
        self.entries = {}  # (lookup key, display word, POS): compact record
        self.links = set()  # surface, lemma, POS, tags, required exact surface
        self.processed = defaultdict(int)
        self.russian_keys = {}

    def add(self, entry, edition):
        if entry.get("lang_code") != "en":
            return
        self.processed[edition] += 1
        raw, pos = entry.get("word", ""), entry.get("pos", "")
        key = normalize(raw)
        if pos not in POS or pos in SKIP_POS or not valid_key(key):
            return
        sensitive = pos == "name" or (raw.isupper() and not (raw == "I" and pos == "pron"))
        glosses = list(definitions(entry))
        if raw[:1].isupper() and any(re.search(r"фамилия|(?:мужское|женское|личное) имя|surname|given name", g, re.I) for g in glosses):
            sensitive = True
        ru = [clean(g) for g in glosses if CYRILLIC.search(g)] if edition == "ru" else []
        en = [clean(g) for g in glosses] if edition == "en" else []
        translated = russian_translations(entry) if edition == "en" else []
        forms = entry.get("forms", [])
        has_frequent_form = any(normalize(f.get("form", "")) in self.ranks for f in forms)
        # English-only fallback is limited to the frequency vocabulary and forms
        # of it. Russian-bearing entries and phrases are retained independently.
        selected = bool(ru or translated or key in self.ranks or has_frequent_form or
                        any(k[0] == key and k[2] == pos for k in self.entries_by_key(key)))
        if selected and (ru or en or translated):
            identity = (key, raw, pos)
            out = self.entries.setdefault(identity, {"w": raw, "p": pos, "r": [], "e": [],
                                                       "t": [], "i": [], "f": self.ranks.get(key, 0), "c": sensitive})
            append_unique(out["r"], ru, 6)
            append_unique(out["e"], en, 6)
            append_unique(out["t"], translated, 8)
            ipa = [{"v": sound["ipa"], "t": sound.get("tags", []) + sound.get("raw_tags", [])}
                   for sound in entry.get("sounds", []) if sound.get("ipa")]
            # Preserve labels that distinguish British, American and other readings.
            append_unique(out["i"], ipa, 8)
        for sense in entry.get("senses", []):
            tags = set(sense.get("tags", []))
            if tags & SKIP_TAGS or "contraction" in tags or pos == "contraction":
                continue
            abbreviation = bool(tags & (NON_LEMMA_LINKS - {"contraction"}))
            if abbreviation and not sensitive:
                continue
            for field, link_tag in (("form_of", "form-of"), ("alt_of", "alternative")):
                for ref in sense.get(field, []):
                    lemma = normalize(ref.get("word", ""))
                    if valid_key(lemma) and lemma != key and valid_form_link(key,lemma,pos,tags):
                        kept = sorted(tags & INFLECTION_TAGS) if field == "form_of" else [link_tag]
                        self.links.add((key, lemma, pos, tuple(kept or [link_tag]), raw if sensitive else ""))
            spelling = spelling_redirect(sense)
            if spelling and valid_key(spelling) and spelling != key:
                self.links.add((key, spelling, pos, ("alternative",), raw if sensitive else ""))
        if not selected:
            return
        for form in forms:
            raw_form = form.get("form", "")
            surface = normalize(raw_form)
            tags = set(form.get("tags", []))
            if surface == key or not valid_key(surface) or tags & (SKIP_TAGS | NON_LEMMA_LINKS):
                continue
            grammar = tags & INFLECTION_TAGS
            if grammar and valid_form_link(surface,key,pos,grammar):
                self.links.add((surface, key, pos, tuple(sorted(grammar)), raw_form if sensitive else ""))
            elif "alternative" in tags:
                self.links.add((surface, key, pos, ("alternative",), raw_form if sensitive else ""))

    def entries_by_key(self, key):
        # Set in build() after the Russian pass; no full-dictionary scan per entry.
        return self.russian_keys.get(key, ())

    def build(self, sources, out):
        for entry in records(sources / "ru-english.jsonl"):
            self.add(entry, "ru")
        for identity in self.entries:
            self.russian_keys.setdefault(identity[0], []).append(identity)
        for entry in records(sources / "en-english.jsonl.gz"):
            self.add(entry, "en")
            if self.processed["en"] % 200_000 == 0:
                print(f"English source entries: {self.processed['en']:,}", flush=True)
        by_key = defaultdict(list)
        for (key, _, _), entry in self.entries.items():
            by_key[key].append(entry)
        # Retain reference chains only when they reach an actual selected entry.
        reachable = {(key, e["p"]) for key, entries in by_key.items() for e in entries}
        retained = set()
        for _ in range(6):
            new = {link for link in self.links if (link[1], link[2]) in reachable}
            if new <= retained:
                break
            retained.update(new)
            reachable.update((link[0], link[2]) for link in new)
        by_form = defaultdict(list)
        for surface, lemma, pos, tags, exact in sorted(retained):
            by_form[surface].append({"l": lemma, "p": pos, "t": list(tags), "c": exact})
            rank = self.ranks.get(surface)
            if rank:
                for e in by_key.get(lemma, []):
                    if e["p"] == pos:
                        e["f"] = min(e["f"] or rank, rank)
        # Russian definitions first; then English-to-Russian translations; names
        # after common words. POS alternatives remain separate, never merged.
        for entries in by_key.values():
            entries.sort(key=lambda e: (e["c"], not bool(e["r"]), not bool(e["t"]), e["w"], e["p"]))
        out.mkdir(parents=True, exist_ok=True)
        def write(name, mapping):
            with (out / name).open("w", encoding="utf-8", newline="\n") as stream:
                for key in sorted(mapping, key=lambda value: value.encode("utf-8")):
                    stream.write(key + "\t" + json.dumps(mapping[key], ensure_ascii=False, separators=(",", ":")) + "\n")
        write("dict.tsv", by_key)
        write("forms.tsv", by_form)
        # Opening a book must not parse every JSON article to discover word levels.
        ranks = {}
        for entries in by_key.values():
            for entry in entries:
                if entry["c"] or entry["p"] == "name" or not 0 < entry["f"] <= 16000:
                    continue
                word = entry["w"]
                ranks[word] = min(ranks.get(word, entry["f"]), entry["f"])
        with (out / "ranks.tsv").open("w", encoding="utf-8", newline="\n") as stream:
            for word in sorted(ranks, key=lambda value: value.encode("utf-8")):
                stream.write(f"{word}\t{ranks[word]}\n")
        def digest(path):
            sha = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    sha.update(chunk)
            return {"bytes": path.stat().st_size, "sha256": sha.hexdigest()}
        summary = {"format": 2, "source_entries": dict(self.processed), "keys": len(by_key),
                   "pos_entries": len(self.entries), "form_keys": len(by_form), "form_links": len(retained),
                   "russian_keys": sum(any(e["r"] or e["t"] for e in es) for es in by_key.values()),
                   "sources": {name: digest(sources / name) for name in
                               ("ru-english.jsonl", "en-english.jsonl.gz", "en_50k.txt")},
                   "files": {name: digest(out / name) for name in ("dict.tsv", "forms.tsv", "ranks.tsv")}}
        (out / "manifest.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=Path(".work/en-dictionary/sources"))
    parser.add_argument("--out", type=Path, default=Path("app/src/main/assets/en"))
    parser.add_argument("--reader-data", type=Path, default=Path(__file__).resolve().parents[1] / "data/en")
    parser.add_argument("--sync-reader-data", action="store_true", help="Only sync manual/context data into an existing dictionary")
    args = parser.parse_args()
    if args.sync_reader_data:
        print(json.dumps(sync_reader_data(args.out,args.reader_data),ensure_ascii=False,indent=2))
        return
    rows, _ = frequencies(args.sources / "en_50k.txt")
    summary = Builder(rows).build(args.sources, args.out)
    notice = Path(__file__).resolve().parents[1] / "data/en/NOTICE.txt"
    (args.out / "NOTICE.txt").write_bytes(notice.read_bytes())
    summary = sync_reader_data(args.out,args.reader_data)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
