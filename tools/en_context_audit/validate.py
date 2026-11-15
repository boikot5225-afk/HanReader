#!/usr/bin/env python3
"""Validate immutable private corpus references and prediction completeness; no network."""
from pathlib import Path
import json,hashlib
root=Path(__file__).resolve().parents[2];w=root/'.work/en-context-audit'
load=lambda name:list(map(json.loads,(w/name).read_text().splitlines()))
expected={'gold.jsonl':('064762eba547743b8245a727ac531d95e5cefed2226c4c362e31550742a0e1d4',310),'followup-gold.jsonl':('1cc0ee76fda710f0d168ffd8630ec1efba7eedffef5a1b0e5662272ebf420fac',60),'contrast-gold.jsonl':('bc3a031af76812d7d6d30b14271251b9f753ce9a40a4ffd9ded4c01934dc5d21',60)}
corpora={b:{(r['chapter'],r['block']):r for r in load(b+'-blocks.jsonl')} for b in ['best','complete','biography']}
for file,(sha,count) in expected.items():
 assert hashlib.sha256((w/file).read_bytes()).hexdigest()==sha,file+' changed'
 rows=load(file);assert len(rows)==count and len({r['id'] for r in rows})==count
 for r in rows:
  assert r['text'][r['char_start']:r['char_end']]==r['target']
  assert len(r['text'][:r['char_start']].encode('utf-16-le'))//2==r['start']
  assert len(r['text'][:r['char_end']].encode('utf-16-le'))//2==r['end']
  if r['provenance']=='synthetic':continue
  source=corpora[r.get('source_book',r['book'])][r['chapter'],r['block']]
  assert source['text']==r['text'] and source['path']==r['path']
original=load('gold.jsonl');followup=load('followup-gold.jsonl')
paragraph=lambda r:(r.get('source_book',r['book']),r['chapter'],r['block'])
assert not {paragraph(r) for r in original}&{paragraph(r) for r in followup}
devchap={r['chapter'] for r in original if r['split']=='development'};heldchap={r['chapter'] for r in original if r['split']=='holdout'};assert not devchap&heldchap
bestpaths={r['path'].split('/')[-1] for r in corpora['best'].values()}
assert all(r['path'].split('/')[-1] not in bestpaths for r in original+followup if r.get('source_book',r['book'])=='complete')
for gold,pred in [('gold.jsonl','baseline.jsonl'),('gold.jsonl','final-after.jsonl'),('followup-gold.jsonl','followup-baseline.jsonl'),('followup-gold.jsonl','followup-final.jsonl'),('contrast-gold.jsonl','contrast-baseline.jsonl'),('contrast-gold.jsonl','contrast-final.jsonl')]:
 rows=load(pred);assert len(rows)==len(load(gold)) and {r['id'] for r in rows}=={r['id'] for r in load(gold)}
 assert all(r['ai'] is False and 'error' not in r for r in rows)
for record in json.loads((w/'inputs.json').read_text()):assert hashlib.sha256(Path(record['path']).read_bytes()).hexdigest()==record['sha256']
for name,meta in json.loads((root/'app/src/main/assets/en/manifest.json').read_text())['files'].items():
 path=root/'app/src/main/assets/en'/name;assert path.stat().st_size==meta['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest()==meta['sha256']
for name in ['manual.tsv','context.tsv']:assert (root/'data/en'/name).read_bytes()==(root/'app/src/main/assets/en'/name).read_bytes()
print('Validated 370 real references +40 pairs on60 examples; hashes, provenance, UTF-16, source separation, no repeated original/followup paragraphs, complete local predictions and asset manifest.')
