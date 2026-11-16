#!/usr/bin/env python3
"""Read-only validation of the retained private 40-context comfort audit; no network."""
import hashlib
import json
import unicodedata
from pathlib import Path
from score import assess, summary

ROOT = Path(__file__).resolve().parents[2]
W = ROOT / '.work/en-context-audit'
N = ROOT / '.work/en-context-next'
RESULTS = json.loads((ROOT / 'tools/en_context_audit/comfort-results.json').read_text())
load = lambda p: list(map(json.loads, p.read_text().splitlines()))
gold = load(N / 'gold-fresh40.jsonl')
assert len(gold) == len({r['id'] for r in gold}) == 40
assert hashlib.sha256((N / 'gold-fresh40.jsonl').read_bytes()).hexdigest() == RESULTS['fresh40']['frozen_gold_sha256']
normalize = lambda s: ' '.join(unicodedata.normalize('NFKC', s).split())
old = load(W / 'gold.jsonl') + load(W / 'followup-gold.jsonl')
assert len(old) == 370
assert not {normalize(r['text']) for r in old} & {normalize(r['text']) for r in gold}
assert len({normalize(r['text']) for r in gold}) == 40
corpora = {b: {(r['chapter'], r['block']): r for r in load(W / (b + '-blocks.jsonl'))} for b in ['best', 'complete']}
for r in gold:
    source = corpora[r['book']][r['chapter'], r['block']]
    assert source['text'] == r['text'] and source['path'] == r['path']
    assert r['text'][r['char_start']:r['char_end']] == r['target']
    assert len(r['text'][:r['char_start']].encode('utf-16-le')) // 2 == r['start']
    assert len(r['text'][:r['char_end']].encode('utf-16-le')) // 2 == r['end']
for path, sha in RESULTS['production_source_sha256'].items():
    assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == sha
for name, key in [('before-fresh40.jsonl', 'before'), ('after-fresh40.jsonl', 'after_first_blind')]:
    predictions = load(N / name)
    assert len(predictions) == len(gold) and {r['id'] for r in predictions} == {r['id'] for r in gold}
    assert all(r['ai'] is False and 'error' not in r for r in predictions)
    by_id = {r['id']: r for r in predictions}
    assert summary([assess(r, by_id[r['id']]) for r in gold]) == RESULTS['fresh40'][key]['all']
assert load(N / 'after-fresh40.jsonl') == load(N / 'after-fresh40-read.jsonl')
regressions = []
for refs, before, after in [('gold.jsonl', 'final-after.jsonl', 'final-original310.jsonl'),
                             ('followup-gold.jsonl', 'followup-final.jsonl', 'final-original60.jsonl')]:
    b = {r['id']: r for r in load(W / before)}
    a = {r['id']: r for r in load(N / after)}
    for r in load(W / refs):
        assert r['id'] in a and a[r['id']]['ai'] is False and 'error' not in a[r['id']]
        if assess(r, b[r['id']])['usable'] and not assess(r, a[r['id']])['usable']:
            regressions.append(r['id'])
assert not regressions, regressions
print('Validated frozen fresh40, no paragraph overlap with prior370, EPUB positions/UTF-16, unchanged production source, metrics, mmap parity and no new usable-answer regressions. No cloud/build.')
