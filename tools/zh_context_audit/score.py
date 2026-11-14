#!/usr/bin/env python3
"""Occurrence-level Chinese scoring against frozen independent provisional annotations."""
import json,re,unicodedata,collections,argparse
from pathlib import Path

def reading(s):return unicodedata.normalize('NFC',s).lower().replace(' ','').replace('·','')
def assess(g,p):
 ru=p.get('russian',[]);top=ru[0] if ru else p.get('name') or p.get('card','').split(';')[0]
 pattern=g['expected_ru']
 if g['target']=='将':pattern='частиц|объект|взяв|брать|конструкц'
 if g['target']=='造反':pattern+='|восстав'
 if g['target']=='本家':pattern+='|клан'
 if g['target']=='假洋鬼子':pattern+='|подражающ.*иностран|низкопоклонник'
 if g['target']=='放心':pattern+='|успока'
 if g['target']=='慢吞吞':pattern+='|медлител'
 if g['target']=='招呼':pattern+='|здоров'
 if g['id']=='hp:69:202:146:148':pattern+='|достоинств'
 match=lambda x:bool(re.search(pattern,x,re.I))
 allowed_readings=[reading(x) for x in g['expected_reading'].split('|')]
 # 行李 is attested with both neutral and third-tone 李; do not invent a reading defect.
 if g['target']=='行李':allowed_readings.append('xínglǐ')
 readok=not g['expected_reading'] or reading(p['reading']) in allowed_readings
 if g['confidence']=='review' or p.get('phoneticOnly'):status='UNRESOLVED'
 elif g['category']=='segmentation' and not p['exact']:status='SEGMENTATION_ERROR'
 elif g['expected_reading'] and not readok:status='READING_ERROR'
 elif match(top):status='CORRECT'
 elif g['target']=='赌' and top=='делать ставку':status='ACCEPTABLE'
 elif p.get('name'):status='ACCEPTABLE'
 elif g['target']=='变形' and top=='менять форму':status='ACCEPTABLE'
 elif g.get('acceptable_ru') and re.search(g['acceptable_ru'],top,re.I):status='ACCEPTABLE'
 elif any(match(x) for x in ru[1:]):status='WRONG_SENSE'
 elif not re.search('[А-Яа-яЁё]',top) and top:status='LANGUAGE_FALLBACK'
 elif not top:status='NOT_FOUND'
 else:status='MISSING_SENSE'
 return dict(id=g['id'],book=g['book'],volume=g['volume'],split=g['split'],category=g['category'],status=status,top1=status=='CORRECT',usable=status in ['CORRECT','ACCEPTABLE'],top3=status!='UNRESOLVED' and (any(match(x) for x in ru[:3]) or status in ['CORRECT','ACCEPTABLE']),reading=readok,has_reading=bool(g['expected_reading']),russian=bool(re.search('[А-Яа-яЁё]',top)),whole=bool(p['exact']),requires_whole=g['category']=='segmentation',rules_contextual_sense=bool(p.get("contextMeaning")))
def summary(rows):
 def metric(field,subset=rows):return dict(n=sum(bool(r[field]) for r in subset),denominator=len(subset),percent=round(100*sum(bool(r[field]) for r in subset)/len(subset),2) if subset else None)
 return dict(total=len(rows),statuses=dict(collections.Counter(r['status'] for r in rows)),top1=metric('top1'),usable=metric('usable'),top3=metric('top3'),reading=metric('reading',[r for r in rows if r['has_reading']]),russian=metric('russian'),segmentation=metric('whole',[r for r in rows if r['requires_whole']]))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('gold');p.add_argument('predictions');p.add_argument('output');p.add_argument('--development-only',action='store_true');a=p.parse_args()
 gold_rows=list(map(json.loads,Path(a.gold).read_text().splitlines()))
 gold={g['id']:g for g in gold_rows};pred=list(map(json.loads,Path(a.predictions).read_text().splitlines()))
 if len(gold)!=len(gold_rows):raise ValueError('Duplicate gold IDs')
 ids=[r['id'] for r in pred]
 if len(set(ids))!=len(ids) or set(ids)!=set(gold):raise ValueError('Predictions must cover every gold ID exactly once')
 rows=[assess(gold[r['id']],r) for r in pred if not a.development_only or gold[r['id']]['split']=='development']
 out={'all':summary(rows)}
 for k in ['book','split','category','volume']:out['by_'+k]={str(v):summary([r for r in rows if r[k]==v]) for v in sorted(set(r[k] for r in rows))}
 Path(a.output).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');Path(a.output).with_suffix('.rows.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
 print(json.dumps(out['all'],ensure_ascii=False))
