#!/usr/bin/env python3
"""Semantic families on frozen provisional references; never exact string equality."""
import json,re,argparse,collections,unicodedata
from pathlib import Path

def semantic_text(text):
 return unicodedata.normalize('NFC',unicodedata.normalize('NFD',text).replace('\u0301','').replace('\u0300',''))

def assess(g,p):
 expected=g['expected']
 if g['lemma']=='give up':expected+='|сдава|сдать'
 if g['unit'] in ["can't","can’t"]:expected+='|не иметь возможности|неспособ'
 if g['unit'].lower()=='might' and 'мог|' in expected:expected=expected.replace('мог|',r'мог(?:у|ут|л[аои]?)?\b|')
 match=lambda x:bool(re.search(expected,semantic_text(x),re.I))
 ru=p.get('russian',[]);primary=p.get('primary','')
 russian=any(re.search('[А-Яа-яЁё]',x) and not x.startswith('Сокращение:') for x in ru)
 lemma=bool(re.fullmatch(g['lemma'],p.get('lemma',''),re.I) or g['category']=='morphology' and re.fullmatch(g['lemma'],p.get('lemma','').split(' ')[0],re.I));pos=bool(re.fullmatch(g['pos'],p.get('pos',''),re.I))
 expansion=(p.get('pos')=='сокр.' and any(re.search(g['expansion'],x,re.I) for x in p.get('english',[]))) if g.get('expansion') else True
 other=[x for a in p.get('all',[]) for x in a['russian']]
 if g['confidence']=='review' or (g['book']=='biography' and g.get('candidate_index')==268) or p.get('error'):status='UNRESOLVED'
 elif not p.get('found'):status='PHRASE_ERROR' if g['category']=='phrase' else 'NOT_FOUND'
 elif g.get('proper_name') and match(primary):status='ACCEPTABLE'
 elif g['category']=='contraction' and not expansion:status='MORPHOLOGY_ERROR'
 elif g['category']=='morphology' and not (lemma and pos):status='MORPHOLOGY_ERROR'
 elif not russian:status='LANGUAGE_FALLBACK'
 elif match(primary):status='CORRECT'
 elif g.get('acceptable') and re.search(g['acceptable'],semantic_text(primary),re.I):status='ACCEPTABLE'
 elif any(match(x) for x in ru+other):status='WRONG_SENSE'
 else:status='MISSING_SENSE'
 return dict(id=g['id'],book=g['book'],split=g['split'],category=g['category'],status=status,top1=status=='CORRECT',usable=status in ['CORRECT','ACCEPTABLE'],top3=status!='UNRESOLVED' and russian and any(match(x) for x in ru[:3]),morphology=lemma and pos,requires_morphology=g['category']=='morphology',expansion=expansion,requires_expansion=g['category']=='contraction',russian=russian,mwe=g['category']=='phrase',mwe_ok=g['category']=='phrase' and status in ['CORRECT','ACCEPTABLE'],found=bool(p.get('found')),list_only=len(ru)>1 and not p.get('contextMeaning'),context_marked=bool(p.get('contextMeaning')),wrong_russian_primary=russian and status in ['WRONG_SENSE','MISSING_SENSE','MORPHOLOGY_ERROR'],wrong_context_marked=bool(p.get('contextMeaning')) and status not in ['CORRECT','ACCEPTABLE','UNRESOLVED'])
def summary(rows):
 def metric(key,subset=rows):return dict(n=sum(bool(r[key]) for r in subset),denominator=len(subset),percent=round(100*sum(bool(r[key]) for r in subset)/len(subset),2) if subset else None)
 return dict(total=len(rows),statuses=dict(collections.Counter(r['status'] for r in rows)),top1=metric('top1'),usable=metric('usable'),top3=metric('top3'),mwe=metric('mwe_ok',[r for r in rows if r['mwe']]),morphology=metric('morphology',[r for r in rows if r['requires_morphology']]),contraction_expansion=metric('expansion',[r for r in rows if r['requires_expansion']]),russian=metric('russian'),wrong_russian_primary=metric('wrong_russian_primary'),context_marks=sum(r['context_marked'] for r in rows),wrong_context_marks=sum(r['wrong_context_marked'] for r in rows),lists_without_choice=sum(r['list_only'] for r in rows))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('gold');p.add_argument('predictions');p.add_argument('output');a=p.parse_args()
 gold=list(map(json.loads,Path(a.gold).read_text().splitlines()));pred=list(map(json.loads,Path(a.predictions).read_text().splitlines()));g={r['id']:r for r in gold}
 assert len(g)==len(gold)==len(pred)==len({r['id'] for r in pred}) and {r['id'] for r in pred}==set(g)
 rows=[assess(g[r['id']],r) for r in pred];out={'all':summary(rows)}
 for key in ['book','split','category']:out['by_'+key]={v:summary([r for r in rows if r[key]==v]) for v in sorted({r[key] for r in rows})}
 Path(a.output).write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');Path(a.output).with_suffix('.rows.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows));print(json.dumps(out['all'],ensure_ascii=False))
