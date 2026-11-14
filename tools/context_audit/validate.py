#!/usr/bin/env python3
"""Check saved audit integrity without network or book dependencies in public CI."""
import json,hashlib,collections
from pathlib import Path
root=Path(__file__).resolve().parents[2];w=root/'.work/context-audit'
gold=list(map(json.loads,(w/'gold.jsonl').read_text().splitlines()))
assert len(gold)==len({r['id'] for r in gold})==310
assert collections.Counter(r['book'] for r in gold)=={'narco':250,'guard':60}
assert collections.Counter(r['split'] for r in gold)=={'development':200,'holdout':50,'control':60}
assert hashlib.sha256((w/'gold.jsonl').read_bytes()).hexdigest()==(w/'gold.sha256').read_text().split()[0]
blocks={book:{(r['chapter'],r['block']):r for r in map(json.loads,(w/f'{book}-blocks.jsonl').read_text().splitlines())} for book in ['narco','guard']}
for g in gold:
 assert blocks[g['book']][g['chapter'],g['block']]['text']==g['text'],g['id']
 assert g['text'][g['start']:g['end']]==g['target'],g['id']
for stage in ['baseline','after']:
 rows=list(map(json.loads,(w/f'{stage}.jsonl').read_text().splitlines()))
 assert {r['id'] for r in rows}=={g['id'] for g in gold}
 assert not any(r.get('error') for r in rows)
 assert all(r['ai'] is False for r in rows)
pairs=json.loads((root/'tools/context_audit/contrast_pairs.json').read_text());assert len(pairs)==40
print('OK: 310 frozen real contexts, exact reader text and offsets, 40 contrast pairs, complete local predictions.')
