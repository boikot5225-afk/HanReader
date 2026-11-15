"""Checks for false coverage gains in the English source audit."""
import unittest
from collections import Counter
from pathlib import Path
import tempfile

from audit_en_dictionary import Index, book_words, definitions, frequencies, normalize


def entry(word, gloss, **extra):
    return {"word": word, "lang_code": "en", "pos": "verb", "senses": [{"glosses": [gloss]}], **extra}


class DictionaryAuditTest(unittest.TestCase):
    def test_curly_apostrophes_and_case_share_a_lookup_key(self):
        self.assertEqual(normalize("I’D"), "i'd")
        self.assertEqual(normalize("cafe\u0301"), "café")

    def test_form_descriptions_are_not_translations(self):
        self.assertEqual(list(definitions(entry("went", "past tense of go", senses=[{
            "glosses": ["past tense of go"], "form_of": [{"word": "go"}]}]))), [])
        self.assertEqual(list(definitions(entry("went", "форма глагола", senses=[{
            "glosses": ["форма глагола"], "tags": ["form-of"]}]))), [])

    def test_source_forms_recover_translations_without_claiming_direct_entries(self):
        index = Index({"went", "go"})
        index.ingest([entry("go", "идти")], "ru")
        index.ingest([entry("go", "move", forms=[{"form": "went", "tags": ["past"]}])], "en")
        counts = index.coverage(Counter({"went": 3, "unknown": 1}))
        self.assertEqual(counts["ru_direct"]["tokens"], 0)
        self.assertEqual(counts["ru_with_forms"]["tokens"], 3)
        self.assertEqual(counts["ru_with_forms"]["token_pct"], 75)

    def test_cycles_keep_all_attested_analyses_and_do_not_invent_a_choice(self):
        index = Index({"saw"})
        index.links["saw"].update({"see", "saw"})
        index.links["see"].add("saw")
        self.assertEqual(index.candidates("saw"), {"see", "saw"})

    def test_russian_translations_are_separate_and_keep_the_sense(self):
        index = Index({"run"})
        index.ingest([entry("run", "move quickly", translations=[{
            "code": "ru", "word": "бе\u0301гать", "sense": "move quickly"}])], "en")
        self.assertNotIn("run", index.ru)
        self.assertIn("run", index.translated)
        counts = index.coverage(Counter({"run": 1}))
        self.assertEqual(counts["ru_with_forms"]["tokens"], 0)
        self.assertEqual(counts["ru_combined_with_forms"]["tokens"], 1)
        translated = index.details["run"][0]["ru_translations"][0]
        self.assertEqual(translated, {"word": "бегать", "sense": "move quickly", "tags": []})

    def test_obsolete_forms_do_not_inflate_coverage(self):
        index = Index({"oldform"})
        index.ingest([entry("run", "move", forms=[{
            "form": "oldform", "tags": ["obsolete"]}])], "en")
        self.assertEqual(index.candidates("oldform"), {"oldform"})

    def test_uppercase_acronyms_do_not_pollute_ordinary_words(self):
        index = Index({"go", "went"})
        index.ingest([entry("go", "move", forms=[{"form": "went", "tags": ["past"]}]),
                      entry("GO", "an acronym", senses=[{"alt_of": [{"word": "graphene oxide"}],
                                                          "tags": ["abbreviation", "alt-of"]}])], "en")
        self.assertEqual(index.candidates("went"), {"went", "go"})

    def test_contraction_of_two_words_is_not_covered_by_just_its_pronoun(self):
        index = Index({"i'd"})
        index.ingest([entry("I", "я", pos="pron")], "ru")
        index.ingest([entry("I'd", "Contraction of I + had", pos="contraction", senses=[{
            "alt_of": [{"word": "I", "extra": "+ had"}], "tags": ["alt-of", "contraction"]}])], "en")
        self.assertEqual(index.coverage(Counter({"i'd": 1}))["ru_with_forms"]["tokens"], 0)

    def test_spelling_redirects_work_but_do_not_replace_defined_homonyms(self):
        index = Index({"organise", "saw"})
        index.ingest([entry("organize", "организовывать"), entry("see", "видеть"), entry("saw", "пила")], "ru")
        index.ingest([entry("organise", "", senses=[{"alt_of": [{"word": "organize"}],
                                                     "tags": ["alt-of", "alternative"]}]),
                      entry("saw", "", senses=[{"form_of": [{"word": "see"}], "tags": ["form-of"]}])], "en")
        self.assertIn("organize", index.candidates("organise"))
        self.assertEqual(index.candidates("saw"), {"saw", "see"})

    def test_linked_british_spelling_description_is_a_redirect_not_a_definition(self):
        index = Index({"organise"})
        index.ingest([entry("organize", "организовывать")], "ru")
        index.ingest([entry("organise", "", senses=[{
            "glosses": ["Non-Oxford British standard spelling of organize."],
            "links": [["organize", "organize#English"]]}])], "en")
        self.assertNotIn("organise", index.en)
        self.assertEqual(index.candidates("organise"), {"organise", "organize"})

    def test_subtitle_fragments_are_reported_instead_of_counted_as_words(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "frequencies.txt"
            path.write_text("the 10\n's 8\ndon't 6\nabc123 4\n", encoding="utf-8")
            included, excluded = frequencies(path)
        self.assertEqual([r[1] for r in included], ["the", "don't"])
        self.assertEqual(sum(r[2] for r in excluded), 12)

    def test_book_counts_exclude_gutenberg_boilerplate(self):
        text = ("Header ignored\n*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\n"
                "He didn’t run.\n*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\nFooter ignored")
        self.assertEqual(book_words(text), Counter({"he": 1, "didn't": 1, "run": 1}))
        with self.assertRaises(ValueError):
            book_words("A text without book boundaries")


if __name__ == "__main__":
    unittest.main()
