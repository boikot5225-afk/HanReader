# English contextual audit (JVM only)

Results and limits: [REPORT_ru.md](REPORT_ru.md), machine-readable [results.json](results.json).
Next iteration: [COMFORT_ru.md](COMFORT_ru.md), [comfort-results.json](comfort-results.json).
For everyday regression, reuse the verified JVM compilation:

```sh
python3 tools/en_context_audit/check.py
```

The first run compiles the JVM tests; unchanged runs reuse the jar. Source/assets/test/library
changes invalidate the cache. An optional `--gold /absolute/private.jsonl` runs a small local audit
after tests succeed. This does not trigger Gradle, Android, APK, CI or cloud requests.

This harness exercises production English dictionary, morphology, phrase/card and EPUB code.
It does not launch Android, Gradle, CI, cloud inference or an APK build. Android host APIs are
small adapters; Compose rendering, installation and phone performance remain untested.

Run from the repository root with a JDK supporting the existing Kotlin compiler:

```sh
# Only if missing: downloads compiler/JVM libraries, never books; reuses valid cache.
python3 tools/context_audit/setup.py
python3 tools/en_context_audit/build.py --label current --tests
python3 -m unittest discover -s tools/en_context_audit -p 'test_*.py' -v
python3 -m unittest discover -s tools -p 'test_*en_dict*.py' -v
python3 tools/en_context_audit/run.py evaluate tools/en_context_audit/synthetic.jsonl .work/en-context-audit/synthetic-current.jsonl
python3 tools/en_context_audit/score.py tools/en_context_audit/synthetic.jsonl .work/en-context-audit/synthetic-current.jsonl .work/en-context-audit/synthetic-current-metrics.json
```

All `.work` files are ignored. The public synthetic examples are original diagnostic sentences,
not book excerpts. The full 370 real references and extracted paragraphs are deliberately private:
`.work/en-context-audit/{gold,followup-gold,contrast-gold}.jsonl`. They contain chapter/path/block,
UTF-16 and Python offsets, provisional lemma/POS/semantic families, confidence and rationale.
Their immutable hashes are in results.json. Retain these files locally to reproduce this exact
assessment. A fresh checkout can reproduce the public regression tests and synthetic subset;
it cannot reproduce the private literary sample without the private references and books.
Never copy complete books, paragraphs or private reference files into Git or CI artifacts.

Repeat against the retained private sample:

```sh
python3 tools/en_context_audit/run.py evaluate .work/en-context-audit/gold.jsonl .work/en-context-audit/new.jsonl
python3 tools/en_context_audit/score.py .work/en-context-audit/gold.jsonl .work/en-context-audit/new.jsonl .work/en-context-audit/new-metrics.json
python3 tools/en_context_audit/validate.py
```

Baseline is compiled from actual git source/assets, not simulated by switching off a few rules:

```sh
python3 tools/en_context_audit/build.py --label baseline-repro --revision 652764f31f0b15cc2ad498cfe6ed069f639bb292
python3 tools/en_context_audit/run.py evaluate .work/en-context-audit/gold.jsonl .work/en-context-audit/baseline-repro.jsonl --label baseline-repro --assets .work/en-context-audit/assets-652764f31f0b15cc2ad498cfe6ed069f639bb292
```

The default dictionary route reads the same TSV records directly. To verify the production mmap
loader on the current assets, prefix `run.py` with `ENAUDIT_MMAP=1`. Its Android host adapter has
update timestamp zero, so remove **only** `.work/en-context-audit/android/en-0` if dictionary assets
have changed since the previous audit; this is not an Android cache-upgrade test. Alternate baseline
assets require the default read route. `book` mode accepts extracted blocks JSONL and checks all
token offsets/reconstruction plus aggregates; it does not judge sense accuracy. `tokens` records
real token boundaries for local reference preparation.

Extraction used production `EpubBook`, through the existing `tools/context_audit/run.py extract`
adapter and JVM build. Preserve its blocks JSONL rather than extracting repeatedly. The first
attachment was unencrypted MOBI/KF8: converted **locally** with KindleUnpack commit
`bf0ca6ece4e73494625e7950be3e259b6260774c` before EpubBook extraction. This does not add MOBI
support to the app. Paragraph filters, provisional annotations, selection/freezing scripts and
short error contexts remain in the private `.work/en-context-audit` workspace. Hashes make edits
detectable; selection is targeted, not a random population estimate.

`score.py` uses Russian semantic regex families, not literal string equality; correct lemma and
correct meaning are separate. All UNRESOLVED remain in overall denominators. `top3` is within the
selected analysis's Russian senses, not all possible POS analyses. `wrong_russian_primary` is an
observable error proxy, not calibrated user confidence. `contrasts.py` uses `pairs.json` and a private
combined real/synthetic gold/prediction file; shared anchors mean 40 pairs have 60 unique examples.
Use `results.json` to distinguish first blind measurements from subsequent diagnostic retesting.

Local reader overlays can be synced without downloading or regenerating the main dictionary:

```sh
python3 tools/build_en_dict.py --sync-reader-data
```

This command updates only manual.tsv, context.tsv and their manifest metadata. Do not invoke the
full source-dictionary build for this small synchronization. No book content is used as dictionary data.
