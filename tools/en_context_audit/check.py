#!/usr/bin/env python3
"""Fast offline regression: reuse a verified JVM compilation; no Gradle/Android/cloud.
Pass --gold PRIVATE.jsonl for an optional targeted audit after the regression succeeds.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.work/en-context-audit'
LABEL = 'comfort-fast'
TESTS = ['EnDictionaryTest', 'EnFormsTest', 'EnEngineTest', 'EnBookContextAuditTest', 'EnReadingComfortTest']


def fingerprint():
    # Conservative: shared source changes invalidate the compilation too. No books or SDK involved.
    files = list((ROOT / 'app/src/main/java').rglob('*.kt'))
    files += [ROOT / f'app/src/test/java/com/hanreader/app/en/{name}.kt' for name in TESTS]
    files += [ROOT / 'tools/en_context_audit/build.py', ROOT / 'tools/en_context_audit/Main.kt']
    files += list((ROOT / 'app/src/main/assets/en').glob('*'))
    digest = hashlib.sha256()
    for path in sorted(p for p in files if p.is_file()):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    # Replacing a toolchain also invalidates an old jar; jars are never downloaded implicitly.
    toolchain = ROOT / '.work/context-audit/toolchain'
    for path in sorted(toolchain.rglob('*.jar')):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(f'{path.stat().st_size}:{path.stat().st_mtime_ns}'.encode())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gold', type=Path, help='Optional local JSONL reference set; never uploaded')
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    cache = WORK / f'cache-{LABEL}.json'
    jar = WORK / f'audit-{LABEL}.jar'
    cp = WORK / f'classpath-{LABEL}'
    digest = fingerprint()
    try:
        prior = json.loads(cache.read_text())
    except (OSError, ValueError):
        prior = {}
    verified = (prior.get('source') == digest and jar.exists() and cp.exists() and
                prior.get('jar') == hashlib.sha256(jar.read_bytes()).hexdigest() and
                prior.get('classpath') == hashlib.sha256(cp.read_bytes()).hexdigest())
    started = time.monotonic()
    if verified:
        print('Reusing verified JVM compilation', flush=True)
        subprocess.run(['java', '-Xmx1g', '-cp', cp.read_text(), 'org.junit.runner.JUnitCore',
                        *['com.hanreader.app.en.' + name for name in TESTS]], cwd=ROOT / 'app', check=True)
    else:
        print('Source/assets changed or cache absent: compiling the JVM regression once', flush=True)
        subprocess.run([sys.executable, str(ROOT / 'tools/en_context_audit/build.py'), '--label', LABEL, '--tests'], cwd=ROOT, check=True)
        if digest != fingerprint():
            raise RuntimeError('Sources changed during compilation; cache was not saved')
        cache.write_text(json.dumps(dict(source=digest, jar=hashlib.sha256(jar.read_bytes()).hexdigest(),
                                        classpath=hashlib.sha256(cp.read_bytes()).hexdigest())) + '\n')
    print(f'Regression completed in {time.monotonic() - started:.2f}s; Android/APK not invoked', flush=True)
    if args.gold:
        if not args.gold.is_file():
            parser.error('Private reference file does not exist')
        output = WORK / 'fast-targeted.jsonl'
        subprocess.run([sys.executable, str(ROOT / 'tools/en_context_audit/run.py'), 'evaluate',
                        str(args.gold.resolve()), str(output), '--label', LABEL], cwd=ROOT, check=True)
        subprocess.run([sys.executable, str(ROOT / 'tools/en_context_audit/score.py'), str(args.gold.resolve()),
                        str(output), str(WORK / 'fast-targeted-metrics.json')], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
