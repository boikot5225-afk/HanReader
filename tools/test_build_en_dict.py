import gzip
import json
from pathlib import Path
import tempfile
import unittest

from build_en_dict import Builder


class EnglishBuilderTest(unittest.TestCase):
    def build(self, folder):
        source = folder / 'sources'
        source.mkdir(exist_ok=True)
        ru = [
            {'word': 'see', 'lang_code': 'en', 'pos': 'verb', 'senses': [{'glosses': ['видеть']}]},
            {'word': 'saw', 'lang_code': 'en', 'pos': 'noun', 'senses': [{'glosses': ['пила']}]},
            {'word': 'go', 'lang_code': 'en', 'pos': 'verb', 'senses': [{'glosses': ['идти']}]},
        ]
        en = [
            {'word': 'see', 'lang_code': 'en', 'pos': 'verb', 'senses': [{'glosses': ['perceive'],
                'examples': [{'text': 'DO NOT EXPORT'}]}], 'forms': [{'form': 'saw', 'tags': ['past']}]},
            {'word': 'go', 'lang_code': 'en', 'pos': 'verb', 'senses': [{'glosses': ['move']}],
                'forms': [{'form': 'went', 'tags': ['past']}, {'form': 'goeth', 'tags': ['archaic', 'third-person']}]},
            {'word': 'GO', 'lang_code': 'en', 'pos': 'noun', 'senses': [{'glosses': ['a test acronym']}]},
            {'word': 'organise', 'lang_code': 'en', 'pos': 'verb', 'senses': [{
                'glosses': ['Non-Oxford British standard spelling of organize.'],
                'links': [['organize', 'organize#English']]}]},
            {'word': 'organize', 'lang_code': 'en', 'pos': 'verb', 'senses': [{'glosses': ['arrange']}],
                'translations': [{'code': 'ru', 'word': 'организова́ть', 'sense': 'arrange'}]},
            {'word': "I'd", 'lang_code': 'en', 'pos': 'contraction', 'senses': [{
                'tags': ['alt-of', 'contraction'], 'alt_of': [{'word': 'I', 'extra': '+ had'}]}]},
            {'word': 'I', 'lang_code': 'en', 'pos': 'pron', 'senses': [{'glosses': ['the speaker']}]},
        ]
        (source / 'ru-english.jsonl').write_text(''.join(json.dumps(e) + '\n' for e in ru))
        with gzip.open(source / 'en-english.jsonl.gz', 'wt') as stream:
            for e in en:
                stream.write(json.dumps(e) + '\n')
        (source / 'en_50k.txt').write_text('go 10\nsee 9\nsaw 8\ni 7\n')
        out = folder / 'out'
        summary = Builder([(1, 'go', 10), (2, 'see', 9), (3, 'saw', 8), (4, 'i', 7)]).build(source, out)
        def read(name):
            return {key: json.loads(value) for key, value in
                    (line.split('\t', 1) for line in (out / name).read_text().splitlines())}
        return summary, out, read('dict.tsv'), read('forms.tsv')

    def test_pos_case_spelling_and_translation_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, out, words, forms = self.build(Path(tmp))
            self.assertEqual(words['saw'][0]['p'], 'noun')
            self.assertTrue(any(f['l'] == 'see' and f['p'] == 'verb' for f in forms['saw']))
            self.assertEqual({e['w']: e['c'] for e in words['go']}, {'go': False, 'GO': True})
            self.assertEqual(words['organize'][0]['t'], [{'g': 'arrange', 'r': ['организовать']}])
            self.assertEqual(forms['organise'][0]['l'], 'organize')
            self.assertNotIn('organise', words)
            self.assertNotIn('goeth', forms)
            self.assertNotIn("i'd", forms)
            self.assertNotIn('DO NOT EXPORT', (out / 'dict.tsv').read_text())
            ranks = dict(line.split('\t') for line in (out / 'ranks.tsv').read_text().splitlines())
            self.assertEqual(ranks['go'], '1')
            self.assertNotIn('GO', ranks)

    def test_outputs_are_deterministic_and_utf8_sorted(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            first, out, _, _ = self.build(folder)
            second, _, _, _ = self.build(folder)
            self.assertEqual(first['files'], second['files'])
            for name in ('dict.tsv', 'forms.tsv', 'ranks.tsv'):
                keys = [line.split(b'\t', 1)[0] for line in (out / name).read_bytes().splitlines()]
                self.assertEqual(keys, sorted(keys))


if __name__ == '__main__':
    unittest.main()
