#!/usr/bin/env python3
"""Validate private frozen references, provenance, disjoint paragraphs and predictions."""
from pathlib import Path
import json,hashlib
w=Path(__file__).resolve().parents[2]/'.work/zh-context-audit'
expected={'gold.jsonl':('349d7fe9419d83c882dea3776d75737c7b84bc27aff7c49de1592abe10661b73',310),'aq-followup-gold.jsonl':('9d146f423045bd449bc6996ab9a95381a021c11f473c61e893b7d2945f09e02a',60),'hp-followup-gold.jsonl':('e6f29f708e585f4157e310f892ba711dfe18455fbebdaa17cab63bf84dbc4190',50)}
load=lambda name:list(map(json.loads,(w/name).read_text().splitlines()))
corpora={book:{(r['chapter'],r['block']):r for r in load(book+'-blocks.jsonl')} for book in ['hp','aq']}
sets={}
for name,(sha,n) in expected.items():
 assert hashlib.sha256((w/name).read_bytes()).hexdigest()==sha,name+' changed'
 rows=load(name);assert len(rows)==n and len({r['id'] for r in rows})==n
 for r in rows:
  book=r['book'].removesuffix('-new');source=corpora[book][r['chapter'],r['block']]
  assert r['provenance']=='epub' and r['text']==source['text']
  assert r['text'][r['char_start']:r['char_end']]==r['target']
  assert len(r['text'][:r['char_start']].encode('utf-16-le'))//2==r['start']
 sets[name]={(r['book'].removesuffix('-new'),r['chapter'],r['block']) for r in rows}
 assert set(r['volume'] for r in rows if r['book'].startswith('hp')) in [set(),set(range(1,8))]
for name in ['aq-followup-gold.jsonl','hp-followup-gold.jsonl']:assert not sets['gold.jsonl'] & sets[name]
for gold,predictions in [('gold.jsonl','baseline.jsonl'),('gold.jsonl','phase-a-after.jsonl'),('gold.jsonl','phase-b-after.jsonl'),('followup-gold.jsonl','followup-baseline.jsonl'),('followup-gold.jsonl','followup-current.jsonl')]:
 rows=load(predictions);assert len(rows)==len(load(gold))
 assert {r['id'] for r in rows}=={r['id'] for r in load(gold)}
 assert all(r['ai'] is False and 'error' not in r for r in rows)
print('Validated 420 real references, original hashes, UTF-16 locations, seven volumes, paragraph-disjoint followups and complete local predictions.')
