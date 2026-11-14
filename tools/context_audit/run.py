#!/usr/bin/env python3
"""Local resumable extraction/evaluation. No network, no Android SDK.
Examples:
  python tools/context_audit/build.py --label current --tests
  python tools/context_audit/run.py extract /absolute/book.epub .work/context-audit/blocks.jsonl
  python tools/context_audit/run.py evaluate .work/context-audit/gold.jsonl .work/context-audit/after.jsonl
  python tools/context_audit/run.py evaluate tools/context_audit/synthetic.jsonl .work/context-audit/synthetic-after.jsonl
Use --label baseline --assets .work/context-audit/baseline-assets for a baseline build.
"""
import argparse,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('mode',choices=['extract','evaluate']);p.add_argument('input');p.add_argument('output');p.add_argument('--label',default='current');p.add_argument('--assets',default=str(root/'app/src/main/assets/es'));a=p.parse_args()
cp=(root/f'.work/context-audit/classpath-{a.label}').read_text()
args=['java','-Xmx1g','-cp',cp,'audit.MainKt',a.mode]
if a.mode=='evaluate':args.append(str(Path(a.assets).resolve()))
args += [str(Path(a.input).resolve()),str(Path(a.output).resolve())]
subprocess.run(args,cwd=root,check=True)
