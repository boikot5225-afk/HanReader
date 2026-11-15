#!/usr/bin/env python3
"""Audit English dictionary sources without adding a language or assets to the app.

Reads Kaikki JSONL (plain or gzip), FrequencyWords en_50k.txt and two Gutenberg
books from --sources. Only aggregates, dictionary definitions and word lists are
exported; quotations, examples, etymologies and book passages are never exported.
Coverage means that *some* dictionary analysis has a definition, not that its
part of speech or meaning is correct in context. No heuristic stemming is used.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import re
import unicodedata


TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*(?:-[^\W\d_]+(?:['’][^\W\d_]+)*)*", re.UNICODE)
CYRILLIC = re.compile(r"[А-Яа-яЁё]")
SKIP_TAGS = {"obsolete", "archaic", "misspelling", "nonstandard", "error-unrecognized-form"}
SKIP_POS = {"symbol", "character", "letter", "punct", "prefix", "suffix", "infix", "interfix", "affix", "root", "combining_form"}
NON_LEMMA_LINKS = {"abbreviation", "acronym", "initialism", "clipping", "contraction"}
INFLECTION_TAGS = {"plural", "singular", "past", "present", "participle", "gerund", "comparative",
                   "superlative", "third-person", "first-person", "second-person", "infinitive", "possessive", "genitive"}
PROBES = """went gone saw left better best children mice feet written does don't doesn't can't won't
I'm I'd he's we've John's teachers' get set run colour color organise organize read lead a the an""".split() + [
    "look up", "looked up", "turn off", "turned off", "take off", "give up"]
PROBE_KEYS = {unicodedata.normalize("NFC", p).replace("’", "'").casefold() for p in PROBES}
SOURCE_URLS = {
    "ru-english.jsonl": "https://kaikki.org/ruwiktionary/Английский/kaikki.org-dictionary-Английский.jsonl",
    "en-english.jsonl.gz": "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
    "en_50k.txt": "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt",
    "alice.txt": "https://www.gutenberg.org/cache/epub/11/pg11.txt",
    "sherlock.txt": "https://www.gutenberg.org/cache/epub/1661/pg1661.txt",
}


def normalize(word):
    return unicodedata.normalize("NFC", word).replace("’", "'").casefold().strip()


def records(path):
    with path.open("rb") as source:
        compressed = source.read(2) == b"\x1f\x8b"
    opener = gzip.open if compressed else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


def book_words(text):
    start = re.search(r"^\*\*\* START OF .+?\*\*\*\s*$", text, re.MULTILINE)
    end = re.search(r"^\*\*\* END OF .+?\*\*\*\s*$", text, re.MULTILINE)
    if not start or not end or end.start() <= start.end():
        raise ValueError("Expected Gutenberg start/end markers; refusing to count boilerplate")
    return Counter(normalize(m.group()) for m in TOKEN.finditer(text[start.end():end.start()]))


def frequencies(path):
    rows, excluded = [], []
    for rank, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        word, count = line.rsplit(maxsplit=1)
        row = (rank, normalize(word), int(count))
        # Leading/trailing apostrophe fragments ('s, n't, d') aren't reader words.
        (rows if TOKEN.fullmatch(word) else excluded).append(row)
    return rows, excluded


def spelling_redirect(sense):
    """Some extracts omit alt_of; accept a spelling pointer only with its link."""
    linked = {normalize(link[0]) for link in sense.get("links", []) if link}
    for gloss in sense.get("glosses", []):
        match = re.search(r"\bspelling of ([A-Za-z][A-Za-z'-]*(?: [A-Za-z][A-Za-z'-]*)*)\.?$", gloss)
        if match and normalize(match[1]) in linked:
            return normalize(match[1])
    return None


def definitions(entry):
    """Exclude redirects and grammatical descriptions even when they have glosses."""
    for sense in entry.get("senses", []):
        tags = set(sense.get("tags", []))
        if sense.get("form_of") or sense.get("alt_of") or spelling_redirect(sense) or tags & (SKIP_TAGS | {"form-of", "alt-of", "no-gloss"}):
            continue
        for gloss in sense.get("glosses", []):
            if gloss.strip():
                yield gloss.strip()


class Index:
    def __init__(self, targets):
        self.targets = set(targets)
        self.ru = set()
        self.en = set()
        self.translated = set()
        self.ipa = set()
        self.names = set()
        self.links = defaultdict(set)
        self.aliases = defaultdict(set)
        self.details = defaultdict(list)
        self.stats = {}

    def ingest(self, entries, edition):
        heads, phrases, with_forms = set(), set(), set()
        count = senses = translations = skipped = 0
        for entry in entries:
            if entry.get("lang_code") != "en":
                continue
            count += 1
            word = normalize(entry.get("word", ""))
            if not word:
                continue
            heads.add(word)
            if " " in word:
                phrases.add(word)
            raw_word, pos = entry.get("word", ""), entry.get("pos")
            # A case-insensitive audit must not turn ordinary "go" into the
            # acronym GO. Production lookup will need its own case policy.
            if pos in SKIP_POS or (raw_word.isupper() and not (raw_word == "I" and pos == "pron")):
                skipped += 1
                continue
            glosses = list(definitions(entry))
            if edition == "ru":
                glosses = [g for g in glosses if CYRILLIC.search(g)]
            if glosses:
                (self.ru if edition == "ru" else self.en).add(word)
                senses += len(glosses)
            if entry.get("pos") == "name":
                self.names.add(word)
            ipa = [s["ipa"] for s in entry.get("sounds", []) if s.get("ipa")]
            if ipa:
                self.ipa.add(word)
            # Preserve the source's sense label. Entry-level translations must not
            # be presented as translations of every English sense of this word.
            ru_translations = []
            if edition == "en":
                lists = [entry.get("translations", [])]
                lists.extend(s.get("translations", []) for s in entry.get("senses", []))
                for group in lists:
                    for t in group:
                        if (t.get("lang_code") or t.get("code")) != "ru":
                            continue
                        value = unicodedata.normalize("NFC", t.get("word", "")).replace("\u0301", "")
                        if CYRILLIC.search(value) and not set(t.get("tags", [])) & SKIP_TAGS:
                            ru_translations.append({"word": value, "sense": t.get("sense", ""), "tags": t.get("tags", [])})
                if ru_translations:
                    self.translated.add(word)
                    translations += len(ru_translations)
            for sense in entry.get("senses", []):
                if set(sense.get("tags", [])) & (SKIP_TAGS | NON_LEMMA_LINKS):
                    continue
                redirect = spelling_redirect(sense)
                if redirect and redirect != word and pos != "contraction":
                    self.aliases[word].add(redirect)
                for field in ("form_of", "alt_of"):
                    for ref in sense.get(field, []):
                        lemma = normalize(ref.get("word", ""))
                        if lemma and lemma != word:
                            (self.links if field == "form_of" else self.aliases)[word].add(lemma)
            for form in entry.get("forms", []):
                value = normalize(form.get("form", ""))
                tags = set(form.get("tags", []))
                if value not in self.targets or value == word or tags & (SKIP_TAGS | NON_LEMMA_LINKS):
                    continue
                if tags & INFLECTION_TAGS:
                    self.links[value].add(word)
                elif "alternative" in tags:
                    self.aliases[value].add(word)
                else:
                    continue
                with_forms.add(word)
            if word in PROBE_KEYS:
                self.details[word].append({
                    "edition": edition, "word": raw_word, "pos": pos, "glosses": glosses[:8],
                    "ipa": ipa[:8], "ru_translations": ru_translations[:12],
                    "forms": [{"form": f.get("form"), "tags": f.get("tags", [])}
                              for f in entry.get("forms", []) if not set(f.get("tags", [])) & SKIP_TAGS][:30],
                })
            if count % 100_000 == 0:
                print(f"{edition}: {count:,} entries", flush=True)
        self.stats[edition] = {"entries": count, "distinct_headwords": len(heads),
                               "excluded_uppercase_or_nonlexical_entries": skipped,
                               "multiword_headwords": len(phrases), "usable_glosses": senses,
                               "headwords_with_target_forms": len(with_forms), "russian_translation_items": translations}

    def candidates(self, word):
        """All source-attested analyses, not a context-sensitive lemma choice."""
        found, pending = set(), [normalize(word)]
        while pending:
            w = pending.pop()
            if w in found:
                continue
            found.add(w)
            defined = w in self.ru or w in self.en or w in self.translated
            # A grammatical form may itself have meanings (saw), but once a
            # lemma is reached do not wander through its unrelated readings.
            if w == normalize(word) or not defined:
                pending.extend(self.links.get(w, set()) - found)
            if not defined:
                pending.extend(self.aliases.get(w, set()) - found)
        return found

    def coverage(self, counts):
        result = {"types": len(counts), "tokens": sum(counts.values())}
        combined = self.ru | self.translated
        fields = {"ru_direct": lambda w, c: w in self.ru,
                  "ru_with_forms": lambda w, c: bool(c & self.ru),
                  "ru_combined_with_forms": lambda w, c: bool(c & combined),
                  "en_with_forms": lambda w, c: bool(c & self.en),
                  "ipa_with_forms": lambda w, c: bool(c & self.ipa)}
        totals = {key: [0, 0] for key in fields}
        for word, count in counts.items():
            candidates = self.candidates(word)
            for key, predicate in fields.items():
                if predicate(word, candidates):
                    totals[key][0] += 1
                    totals[key][1] += count
        for key, (types, tokens) in totals.items():
            result[key] = {"types": types, "tokens": tokens,
                           "type_pct": round(100 * types / len(counts), 3) if counts else 0,
                           "token_pct": round(100 * tokens / result["tokens"], 3) if result["tokens"] else 0}
        return result


def source_manifest(sources):
    result = {}
    for name, url in SOURCE_URLS.items():
        path = sources / name
        sha = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                sha.update(chunk)
        result[name] = {"url": url, "bytes": path.stat().st_size, "sha256": sha.hexdigest()}
    for name, page in [("ru-english.jsonl", "ru-english-index.html"), ("en-english.jsonl.gz", "english-index.html")]:
        html = (sources / page).read_text()
        match = re.search(r"extracted on (\d{4}-\d{2}-\d{2}).+?dump dated (\d{4}-\d{2}-\d{2})", html)
        if match:
            result[name].update(extraction_date=match[1], dump_date=match[2])
    return {"audited_at_utc": datetime.now(timezone.utc).isoformat(), "sources": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=Path(".work/en-dictionary/sources"))
    parser.add_argument("--out", type=Path, default=Path("data/en/audit"))
    args = parser.parse_args()
    rows, excluded = frequencies(args.sources / "en_50k.txt")
    books = {name: book_words((args.sources / (name + ".txt")).read_text(encoding="utf-8-sig"))
             for name in ("alice", "sherlock")}
    targets = {word for _, word, _ in rows} | {normalize(p) for p in PROBES}
    for counts in books.values():
        targets.update(counts)
    index = Index(targets)
    index.ingest(records(args.sources / "ru-english.jsonl"), "ru")
    index.ingest(records(args.sources / "en-english.jsonl.gz"), "en")
    report = {"source_stats": index.stats,
              "definitions": {"ru_headwords": len(index.ru), "en_headwords": len(index.en),
                              "en_headwords_with_russian_translations": len(index.translated),
                              "extra_russian_headwords": len(index.translated - index.ru)},
              "frequency_excluded": {"rows": len(excluded), "tokens": sum(r[2] for r in excluded),
                                     "first_20": excluded[:20]},
              "frequency": {}, "books": {}}
    for band in (1000, 3000, 5000, 10000, 50000):
        counts = Counter()
        for rank, word, count in rows:
            if rank <= band:
                counts[word] += count
        report["frequency"][str(band)] = index.coverage(counts)
    for name, counts in books.items():
        report["books"][name] = index.coverage(counts)
    args.out.mkdir(parents=True, exist_ok=True)
    def save(name, value):
        (args.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    save("coverage.json", report)
    save("sources.json", source_manifest(args.sources))
    probes = {}
    for probe in PROBES:
        key = normalize(probe)
        candidates = index.candidates(key)
        probes[probe] = {"candidates": sorted(candidates), "russian_candidates": sorted(candidates & index.ru),
                         "translated_candidates": sorted(candidates & index.translated),
                         "entries": index.details[key]}
    save("probes.json", probes)
    # Missing in the top 5,000 subtitle ranks: candidates for review, not an API job.
    with (args.out / "gaps_top5000.tsv").open("w", encoding="utf-8") as f:
        f.write("rank\tword\tcount\tenglish_candidates\tstatus\n")
        combined = index.ru | index.translated
        for rank, word, count in rows:
            if rank > 5000:
                continue
            candidates = index.candidates(word)
            if candidates & combined:
                continue
            english = candidates & index.en
            status = "russian_not_resolved" if english else "lookup_or_normalization_needed"
            if candidates & index.names:
                status += ";possible_name"
            f.write(f"{rank}\t{word}\t{count}\t{' | '.join(sorted(english))}\t{status}\n")
    with (args.out / "book_gaps.tsv").open("w", encoding="utf-8") as f:
        f.write("book\tword\tcount\tenglish_candidates\tstatus\n")
        for name, counts in books.items():
            missing = 0
            for word, count in counts.most_common():
                candidates = index.candidates(word)
                if candidates & combined:
                    continue
                english = candidates & index.en
                status = "russian_not_resolved" if english else "lookup_or_normalization_needed"
                if candidates & index.names:
                    status += ";possible_name"
                f.write(f"{name}\t{word}\t{count}\t{' | '.join(sorted(english))}\t{status}\n")
                missing += 1
                if missing == 50:
                    break
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
