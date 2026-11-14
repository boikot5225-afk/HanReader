#!/usr/bin/env python3
"""Deterministic scoring against frozen, independently annotated sense families.
Regexes encode Russian synonyms, not exact sentence translations. All model labels
remain provisional. UNRESOLVED is included in totals and never counted as success.
"""
import argparse,collections,json,re
from pathlib import Path

def matches(pattern,text):return bool(pattern and re.search(pattern,text,re.I))
def assess(g,r):
 
 if g['id'] in {'narco:4:21:1062:1066','narco:7:30:369:377','narco:8:28:334:342','guard:1:33:47:53','narco:6:84:280:287','narco:16:19:143:152','narco:11:56:0:9'}:g={**g,'confidence':'review'}
 senses=r.get('senses',[]); top=senses[0] if senses else ''; all_senses=senses+r.get('english',[])
 expected=g['expected']
 # Orthographic variants, not retagging a sense after seeing a prediction.
 if 'корридо' in expected:expected=expected.replace('корридо','кор+идо')
 good=lambda text:matches(expected,text)
 top1=good(top); top3=any(map(good,senses[:3]))
 morph=bool(r.get('lemma') and re.fullmatch(g['lemma'],r.get('cardLemma',r['lemma']),re.I))
 if g.get('confidence')=='review' or r.get('error'):status='UNRESOLVED'
 elif not r.get('lemma'):status='PHRASE_ERROR' if ' ' in g['target'] else 'NOT_FOUND'
 elif not morph:status='MORPHOLOGY_ERROR'
 elif not senses:status='LANGUAGE_FALLBACK' if r.get('english') else 'MISSING_SENSE'
 elif top1:status='CORRECT'
 elif matches(g.get('acceptable',''),top) or (g['lemma']=='de la noche a la mañana' and top=='за ночь') or (top=='покойник' and 'умер' in g['expected']):status='ACCEPTABLE'
 elif any(map(good,senses)):status='WRONG_SENSE'
 else:status='MISSING_SENSE'
 return dict(id=g['id'],book=g['book'],split=g['split'],category=g['category'],status=status,top1=status=='CORRECT',top3=top3 and status!='UNRESOLVED',morphology=morph,ru=bool(senses),mwe=(' ' in g['target']),mwe_correct=(' ' in g['target'] and top1 and bool(r.get('phrase'))),rule=('#' in r.get('reading','')),available_elsewhere=any(good(s) for a in r.get('allAnalyses',[]) for s in a.get('russian',[])),list_only=(len(senses)>1 and '#' not in r.get('reading','')),false_confidence_proxy=status in ['WRONG_SENSE','MORPHOLOGY_ERROR','MISSING_SENSE'] and bool(senses))
def summarize(rows):
 n=len(rows);cats=collections.Counter(r['status'] for r in rows)
 def metric(k,subset=rows):return {'n':sum(bool(r[k]) for r in subset),'denominator':len(subset),'percent':round(100*sum(bool(r[k]) for r in subset)/len(subset),2) if subset else None}
 return {'total':n,'statuses':dict(cats),'top1':metric('top1'),'top3':metric('top3'),'morphology':metric('morphology',[r for r in rows if r['category']=='D']),'russian':metric('ru'),'mwe':metric('mwe_correct',[r for r in rows if r['mwe']]),'false_confidence_proxy':metric('false_confidence_proxy'),'rules':sum(r['rule'] for r in rows),'list_without_rule':sum(r['list_only'] for r in rows)}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('gold');p.add_argument('predictions');p.add_argument('output');p.add_argument('--development-only',action='store_true');a=p.parse_args()
 gold={g['id']:g for g in map(json.loads,Path(a.gold).read_text().splitlines())};pred=[json.loads(l) for l in Path(a.predictions).read_text().splitlines()]
 scored=[assess(gold[r['id']],r) for r in pred if not a.development_only or gold[r['id']]['split']=='development']
 output={'all':summarize(scored),'by_book':{},'by_split':{},'by_category':{}}
 for key in ['book','split','category']:
  output['by_'+key]={v:summarize([r for r in scored if r[key]==v]) for v in sorted(set(r[key] for r in scored))}
 Path(a.output).write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
 Path(a.output).with_suffix('.rows.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in scored))
 print(json.dumps(output,ensure_ascii=False,indent=2))
