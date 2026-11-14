#!/usr/bin/env python3
"""Explicit one-time JVM tool setup in the ignored durable work directory.
Downloads compiler/libraries only. Does not read or upload books. Reuses valid files.
"""
from pathlib import Path
from urllib.request import urlopen
import zipfile
root=Path(__file__).resolve().parents[2]/'.work/context-audit/toolchain';root.mkdir(parents=True,exist_ok=True)
base='https://maven-central.storage-download.googleapis.com/maven2/'
urls={
 'kotlin-2.2.21.zip':'https://github.com/JetBrains/kotlin/releases/download/v2.2.21/kotlin-compiler-2.2.21.zip',
 'jsoup.jar':base+'org/jsoup/jsoup/1.18.1/jsoup-1.18.1.jar',
 'json.jar':base+'org/json/json/20240303/json-20240303.jar',
 'junit.jar':base+'junit/junit/4.13.2/junit-4.13.2.jar',
 'hamcrest.jar':base+'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
}
for name,url in urls.items():
 target=root/name
 if not target.exists() or not zipfile.is_zipfile(target):
  tmp=target.with_suffix('.download')
  with urlopen(url,timeout=60) as response,tmp.open('wb') as out:
   while chunk:=response.read(1024*1024):out.write(chunk)
  if not zipfile.is_zipfile(tmp):raise ValueError('Invalid archive: '+name)
  tmp.replace(target)
 print(name,target.stat().st_size)
if not (root/'kotlinc/build.txt').exists() or '2.2.21' not in (root/'kotlinc/build.txt').read_text():
 with zipfile.ZipFile(root/'kotlin-2.2.21.zip') as z:z.extractall(root)
