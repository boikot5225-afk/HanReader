#!/usr/bin/env python3
"""Measure frozen contrasts separately from corpus and mandatory regression tests."""
import json,re
from pathlib import Path
from score import assess
root=Path(__file__).resolve().parents[2];w=root/'.work/context-audit'
gold={g['id']:g for g in map(json.loads,(w/'contrast-gold.jsonl').read_text().splitlines())}
pairs=json.loads((root/'tools/context_audit/contrast_pairs.json').read_text());output={}
for stage in ['baseline','after']:
 pred={g['id']:g for name in [f'{stage}.jsonl',f'synthetic-{stage}.jsonl'] for g in map(json.loads,(w/name).read_text().splitlines())}
 rows=[]
 for pair in pairs:
  a,b=[pred[pair[k]] for k in ['left','right']]
  scores=[assess(gold[pair[k]],pred[pair[k]]) for k in ['left','right']]
  same=a.get('senses',[])[:1]==b.get('senses',[])[:1]
  rows.append(dict(**pair,both_correct=all(s['status'] in ['CORRECT','ACCEPTABLE'] for s in scores),same_top1=same,same_gloss_allowed=pair.get('same_russian_gloss_allowed',False),statuses=[s['status'] for s in scores]))
 output[stage]={'total':len(rows),'both_correct':sum(r['both_correct'] for r in rows),'same_top1':sum(r['same_top1'] for r in rows),'same_gloss_allowed':sum(r['same_gloss_allowed'] for r in rows),'real':{'n':sum(r['both_correct'] for r in rows if r['provenance']=='epub'),'denominator':sum(r['provenance']=='epub' for r in rows)},'synthetic':{'n':sum(r['both_correct'] for r in rows if r['provenance']=='synthetic'),'denominator':sum(r['provenance']=='synthetic' for r in rows)},'rows':rows}
(w/'contrast-metrics.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
for stage,r in output.items():print(stage,{k:v for k,v in r.items() if k!='rows'})
