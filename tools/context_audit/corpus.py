#!/usr/bin/env python3
"""Local corpus preparation from the app's EPUB extraction, retaining source spans.
Book-specific exclusions are audit metadata, never translation rules.
Do not commit outputs. Original gold runs on exact reader paragraphs; reconstructed
sentences are for annotation, not a replacement for the reader's real context window.
"""
import argparse,json,re
from pathlib import Path
ABBR=set('sr sra srta sres dr dra ud uds vd vds d dña ee uu etc pág págs núm nº vol cap art av avda ed eds fig ibíd cf aprox tel st mr mrs ms'.split())
def ends(text,i):
 if text[i]!='.':return text[i] in '!?…;'
 if i and i+1<len(text) and text[i-1].isdigit() and text[i+1].isdigit():return False
 m=re.search(r'([^\W\d_]+)$',text[:i]);word=m[0] if m else ''
 if word.lower() in ABBR or len(word)==1 and word.isupper():return False
 rest=text[i+1:].lstrip(' \n\t”’»")')
 return not rest or not rest[0].islower()
def main():
 p=argparse.ArgumentParser();p.add_argument('book',choices=['narco','guard']);p.add_argument('blocks');p.add_argument('output');a=p.parse_args()
 rows=list(map(json.loads,Path(a.blocks).read_text().splitlines()));included=[];excluded=[]
 narrative={4,6,7,8,9,10,11,13,14,15,16,17,19,20,21,22}
 for r in rows:
  ok=(r['chapter'] in narrative if a.book=='narco' else 3<=r['block']<=64 and not re.fullmatch(r'Página\s+\d+\s+de\s+\d+',r['text']))
  if not ok or r['heading'] or r['text']==r['title'] or not re.search(r'[a-záéíóúñü]{3}',r['text'],re.I):excluded.append({k:r[k] for k in ['chapter','path','block']});continue
  included.append(r)
 joined=[]
 for r in included:
  text=r['text']; span=dict(chapter=r['chapter'],path=r['path'],block=r['block'],source_start=0,source_end=len(text),joined_start=0,joined_end=len(text))
  # Preserve prose split by page layout; never join separate speeches or chapters.
  if joined and joined[-1]['chapter']==r['chapter'] and text[0].islower() and not re.search(r'[.!?…][»”"\')]*$',joined[-1]['text']):
   span['joined_start']=len(joined[-1]['text'])+1;span['joined_end']=span['joined_start']+len(text)
   joined[-1]['text']+=' '+text;joined[-1]['spans'].append(span)
  else:joined.append(dict(book=a.book,chapter=r['chapter'],title=r['title'],text=text,spans=[span]))
 sentences=[]
 for row in joined:
  start=0
  for i,c in enumerate(row['text']):
   if c in '.!?…;' and ends(row['text'],i):
    if row['text'][start:i+1].strip():sentences.append({**row,'text':row['text'][start:i+1],'joined_start':start,'joined_end':i+1})
    start=i+1
  if row['text'][start:].strip():sentences.append({**row,'text':row['text'][start:],'joined_start':start,'joined_end':len(row['text'])})
 out=Path(a.output);out.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in sentences))
 out.with_suffix('.inventory.json').write_text(json.dumps(dict(book=a.book,included_blocks=len(included),excluded_blocks=excluded,logical_paragraphs=len(joined),sentences=len(sentences)),ensure_ascii=False,indent=2)+'\n')
 print(a.book,len(included),'prose blocks;',len(sentences),'sentences; no text printed')
if __name__=='__main__':main()
