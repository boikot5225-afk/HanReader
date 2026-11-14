# Local Spanish contextual audit

The production implementation is compiled directly from `app/src/main/java`; no Python translation engine or imitation dictionary lookup is used. Android Context, package metadata, appearance hosting, coroutine loaders and the unused Chinese loader are hosted by explicit JVM stubs. The real Spanish engine, common language contracts, grammar, binary TSV reader and EPUB reader are compiled unchanged. The shared RToken declaration and the test dictionary loader are extracted from their original source files.

`EsBookAuditTest` remains the coverage audit. This folder adds occurrence-level word-card traces, a frozen independent reference, contrast pairs, and before/after scoring. The corpus files are private and intentionally absent from Git. The synthetic fixtures and mandatory JUnit tests require no user book.

## Lightweight checks

From the repository root, with Java available:

```sh
python tools/context_audit/setup.py
python tools/context_audit/build.py --label current --tests
python tools/context_audit/run.py evaluate tools/context_audit/synthetic.jsonl .work/context-audit/synthetic-after.jsonl
```

Setup downloads only pinned compiler/library archives into `.work/context-audit/toolchain`; it never reads or sends books. Network approval/configuration belongs to the environment. Subsequent checks reuse these files and run without network. Kotlin 2.2.21 matches the app. Run normal Android Gradle tests separately to verify Compose, Room and Chinese integration; the JVM host does not establish UI/device correctness.

## Reproduce this audit

Keep the private `gold.jsonl`, `gold.sha256`, metadata and original EPUB attachments. The report archive includes the saved reference and traces, not the full books or extracted corpus. Restore it under `.work/context-audit`. Gold is tied to the original parser's paragraph offsets; do not regenerate annotations after seeing results.

```sh
python tools/context_audit/build.py --revision d6eca59 --label baseline
python tools/context_audit/run.py evaluate .work/context-audit/gold.jsonl .work/context-audit/baseline.jsonl --label baseline --assets .work/context-audit/baseline-assets
python tools/context_audit/run.py evaluate .work/context-audit/gold.jsonl .work/context-audit/after.jsonl
python tools/context_audit/score.py .work/context-audit/gold.jsonl .work/context-audit/baseline.jsonl .work/context-audit/baseline-metrics.json
python tools/context_audit/score.py .work/context-audit/gold.jsonl .work/context-audit/after.jsonl .work/context-audit/after-metrics.json
python tools/context_audit/contrasts.py
python tools/context_audit/report.py
```

Baseline assets are the four files `dict.tsv`, `forms.tsv`, `grammar.tsv`, `context.tsv` from `git show d6eca59:app/src/main/assets/es/<name>`. `build.py --revision` materializes only Kotlin source into the ignored work directory; it does not switch branches or overwrite edits. Source provenance is recorded, and original baseline outputs are saved independently.

Extract each EPUB with the **real app parser**, then prepare its annotation corpus:

```sh
python tools/context_audit/run.py extract '/absolute/book.epub' .work/context-audit/narco-blocks.jsonl
python tools/context_audit/corpus.py narco .work/context-audit/narco-blocks.jsonl .work/context-audit/narco-sentences.jsonl
```

Use `guard` and a separate file for the control book. `validate.py` additionally checks the reference against these local blocks, exact substrings, the SHA-256 freeze and complete 310-row results. Only `corpus.py` applies book-specific exclusions. Translation rules never see book identity.

`make_contrasts.py` documents original pair construction. The committed `contrast_pairs.json` and `synthetic.jsonl` are the frozen output; do not rebuild them to select easier examples. Before running `contrasts.py`, evaluate `synthetic.jsonl` against both baseline and current assets into `synthetic-baseline.jsonl` and `synthetic-after.jsonl`.

## What is measured

- First visible Russian sense in the tapped word's primary card article; the hint and saved translation are separate trace fields.
- All alternate analyses, primary first-three senses, lemma, form description, detected phrase, source code and fallback state.
- Raw corpus and reference positions stay local. No cloud translator exists in this path; all `ai` flags are false.
- The reference is independent **model annotation**, with explicitly unresolved cases. Regex synonym families make scoring deterministic but do not constitute an expert semantic oracle. See [REPORT_ru.md](REPORT_ru.md) for limits and complete denominators.
- Accuracy on development examples is separate from the frozen 50-context holdout. The control story had been used in earlier development, so it is an independent check for this iteration, not a new unseen project corpus.
- True model-confidence calibration is unavailable. Wrong-primary-answer rate is reported as a proxy, not invented confidence.

No generated corpus, user EPUB, `.work`, toolchain, cache or APK should be added to Git or CI artifacts. Keep necessary short reference excerpts in private local review data. Public regressions contain only original synthetic examples.
