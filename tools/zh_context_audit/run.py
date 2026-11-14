#!/usr/bin/env python3
"""Execute the compiled real reader locally, using saved toolchains and no network."""
from pathlib import Path
import argparse,subprocess
root=Path(__file__).resolve().parents[2];w=root/'.work/zh-context-audit'
p=argparse.ArgumentParser();p.add_argument('mode',choices=['extract','evaluate','names','book']);p.add_argument('input');p.add_argument('output');p.add_argument('--label',default='current');p.add_argument('--assets',default=str(root/'app/src/main/assets/zh'));p.add_argument('--names',default=str(w/'empty-names.json'));a=p.parse_args()
cmd=['java','-Xmx1200m','-cp',(w/f'classpath-{a.label}').read_text(),'zhaudit.MainKt',a.mode]
if a.mode!='extract':cmd.append(str(Path(a.assets).resolve()))
cmd += [str(Path(a.input).resolve()),str(Path(a.output).resolve())]
if a.mode=='evaluate':cmd.append(str(Path(a.names).resolve()))
subprocess.run(cmd,cwd=root,check=True)
