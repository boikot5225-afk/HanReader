#!/usr/bin/env python3
"""Measure frozen real/synthetic contrast pairs; shared anchors are reported explicitly."""
import argparse,json
from pathlib import Path
from score import assess
p=argparse.ArgumentParser();p.add_argument('gold');p.add_argument('predictions');p.add_argument('output');a=p.parse_args()
gold={r['id']:r for r in map(json.loads,Path(a.gold).read_text().splitlines())};pred={r['id']:r for r in map(json.loads,Path(a.predictions).read_text().splitlines())}
assert len(pred)==len(gold) and set(pred)==set(gold)
pairs=json.loads(Path(__file__).with_name('pairs.json').read_text());rows=[]
for pair in pairs:
 left,right=pair['left'],pair['right'];ls,rs=assess(gold[left],pred[left]),assess(gold[right],pred[right]);both=ls['usable'] and rs['usable'];changed=pred[left]['primary']!=pred[right]['primary']
 rows.append(dict(**pair,left_status=ls['status'],right_status=rs['status'],both_usable=both,changed_primary=changed,both_usable_and_changed=both and changed,synthetic_members=sum(gold[i]['provenance']=='synthetic' for i in [left,right])))
out=dict(pairs=len(rows),unique_examples=len(gold),real_examples=sum(g['provenance']!='synthetic' for g in gold.values()),synthetic_examples=sum(g['provenance']=='synthetic' for g in gold.values()),both_usable=sum(r['both_usable'] for r in rows),both_usable_and_changed=sum(r['both_usable_and_changed'] for r in rows),rows=rows)
Path(a.output).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:v for k,v in out.items() if k!='rows'}))
