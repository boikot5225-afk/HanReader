#!/usr/bin/env python3
"""Local resumable extraction/evaluation. No network, no Android SDK.
Examples:
  python tools/en_context_audit/build.py --label current --tests
  python tools/en_context_audit/run.py evaluate .work/en-context-audit/gold.jsonl .work/en-context-audit/after.jsonl
  python tools/en_context_audit/run.py evaluate tools/en_context_audit/synthetic.jsonl .work/en-context-audit/synthetic-after.jsonl
Use --label baseline --assets .work/en-context-audit/baseline-assets for a baseline build.
"""
import argparse,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('mode',choices=['evaluate','tokens','book']);p.add_argument('input');p.add_argument('output');p.add_argument('--label',default='current');p.add_argument('--assets',default=str(root/'app/src/main/assets/en'));a=p.parse_args()
cp=(root/f'.work/en-context-audit/classpath-{a.label}').read_text()
args=['java','-Xmx1g','-cp',cp,'enaudit.MainKt',a.mode]
args.append(str(Path(a.assets).resolve()))
args += [str(Path(a.input).resolve()),str(Path(a.output).resolve())]
subprocess.run(args,cwd=root,check=True)
