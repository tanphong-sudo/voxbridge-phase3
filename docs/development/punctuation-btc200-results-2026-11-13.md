# Punctuation before MT: all 200 BTC sources

## Result and recommendation

Do not add the full Horizon punctuation/truecasing model to the phone pipeline on the basis of this run. It is worse than simple initial capitalization on both MT backends, and it introduces extra sentence breaks or changes response structure. The conservative terminal-question variant helps relative to degraded input but adds only 0.49 chrF for EnViT5 and 0.40 for VinAI over initial capitalization applied to both languages, while requiring an additional model. It remains experimental and disabled.

The next lightweight candidate is initial capitalization of Vietnamese MT input, preserving the transcript for safety/memory. Do not infer question marks with the rejected broad rules. English capitalization should not be enabled indiscriminately: it improves EnViT5 on this set but lowers VinAI's score. No new production normalization has been enabled in this run. These are diagnostic choices on exposed data, not verified universal correctness.

## Full 200-source comparison

All 200 sources were used, 100 VI→EN and 100 EN→VI. There is no dev/test selection in this experiment. All generation uses the same retained pinned checkpoints, beam2, max_new_tokens=192, FP32 CPU. References are the existing frozen AI drafts with no human verification. The inference runners never receive reference text.

| Input treatment | EnViT5 chrF, all 200 | VinAI chrF, all 200 |
|---|---:|---:|
| Degraded input unchanged | 50.69 | 51.90 |
| Initial capitalization only | 52.18 | 52.30 |
| Old rules (VI only; EN unchanged) | 51.65 | 53.72 |
| Horizon full punctuation + casing | 51.13 | 51.54 |
| Horizon terminal question + conservative filter | 52.67 | 52.70 |
| Original BTC source (context baseline) | 52.32 | 53.81 |

chrF measures reference similarity, not the percentage of correctly translated sentences. A higher/lower sentence chrF is not an adjudicated semantic improvement/regression.

All experimental inputs are source text lowercased and question marks replaced with periods. Other punctuation is initially retained. This is a synthetic punctuation degradation test; it does not claim to run all 200 WAVs or use all 200 actual ASR transcripts. English BTC sources often already lack capitalization and punctuation, so the original-source baseline is not a perfectly punctuated gold input.

The old-rule row scores well on this exposed BTC subset, but earlier counterexamples prove the broad heuristic can change statements into questions. It is not selected for production, and no guarantee of 100% correctness is made.

## Direction diagnostics

These are the same 200 sources, with results displayed by direction to explain the aggregate; no new split was made.

| Treatment | EnViT5 VI→EN | EnViT5 EN→VI | VinAI VI→EN | VinAI EN→VI |
|---|---:|---:|---:|---:|
| Unchanged degraded input | 54.74 | 48.64 | 54.67 | 50.48 |
| Initial capitalization | 57.19 | 49.63 | 58.97 | 48.89 |
| Old rule | 57.56 | 48.64 | 60.07 | 50.48 |
| Full neural punctuation | 52.95 | 50.20 | 56.37 | 49.05 |
| Terminal question + filter | 57.21 | 50.36 | 59.49 | 49.22 |

Post-hoc composition of measured outputs (capitalization VI only, English unchanged) scores 51.53 for EnViT5 and 53.35 for VinAI across all 200. This is an exploratory derived policy, not a new inference run or independent acceptance result. For VinAI it beats the extra neural model with filtering on the aggregate.

## Concrete output review

- `VE-7452`, “Được rồi, tôi vào được rồi.” Full punctuation produces “được rồi. tôi vào. được rồi.” Both MT backends then produce “All right. I'm in. All right.” The unchanged/casing input does not introduce the extra standalone final response.
- `VE-0247`, “Bao lâu thì có đồ uống?” EnViT5 unchanged yields “as long as there's a drink.” Initial capitalization yields “How long till the drinks.” Full neural restoration yields the original bad interpretation again. The question-marked clean source yields “How long till the drinks?”
- `EV-6960`, “am i going to need to bring an umbrella to work today”: EnViT5 unchanged yields a statement; neural punctuation yields “Hôm nay tôi có cần mang ô đến chỗ làm không?” This is a useful improvement, but does not establish that unrestricted neural edits are safe elsewhere.
- `EV-5482`, “Right, OK. Do you remember what you were doing at the time?” Full restoration makes the discourse markers into questions, leading EnViT5 to “Đúng không? Được rồi? Bạn có nhớ bạn đã làm gì vào thời điểm đó không?” The casing-only output keeps the ordinary introductory acknowledgment.
- `VE-1814`, “Tôi cần hàng trước trưa mai.” The punctuation model capitalizes “Mai”, but both MT backends in this run still translate it as tomorrow. This is a suspicious input edit, not an observed translation error in this example.
- `VE-8558`, “Buổi tối mở đến mấy giờ?” remains mistranslated as “What time is the night open?” after full neural restoration. Correct punctuation alone does not resolve every MT error.

## Punctuation labels and preservation

Literal terminal question marks in original BTC text are weak labels: several clearly interrogative English sources have no punctuation. The original expected-question field is preserved. A separate AI review labels 74 terminal questions, 124 nonquestions and two ambiguous cases; it was performed after viewing punctuation proposals, so it is exploratory and not an independent human review.

On the 198 AI-reviewed unambiguous rows, full neural restoration recovers 62/74 terminal question marks and adds a question to one nonquestion. The conservative terminal policy recovers 35/74 and adds none to the reviewed nonquestions. These counts describe the input punctuation stage, not final translation correctness or a universal guarantee. Internal questions within multi-sentence utterances are not counted by terminal-only metrics.

All 1,000 experimental input candidates (200 × five treatments) preserve the lexical token sequence ignoring case. A preprocessing issue that would merge `a.m.` into `am` was caught before MT and repaired with regression coverage; internal alphanumeric punctuation such as abbreviation dots and `sixteen:forty` is preserved. The failed 89-row proposal attempt remains saved separately on the VPS. The final full run has 200 proposals.

## Cost and run completeness

Punctuation model: `Horizon-Labs/multilingual-punctuation-truecase-small`, revision `b18abbd2d30e8167e154df444b37faa4d6f5c2ea`, FP32 ONNX 563,125,422 bytes. No Qualcomm job or new quantization run was performed.

Measured on the new VPS, four CPU threads, 199 warm calls after excluding the first, with concurrent MT processes running:

- Tokenization + ONNX classifier + rendering: p50 17.28 ms, p95 28.77 ms.
- ONNX session.run alone: p50 16.53 ms, p95 28.12 ms.
- Standalone Python process peak RSS: 1,187.51 MiB (about 1.16 GiB), including the tokenizer, ORT and Python environment. This is not incremental Android RAM or Snapdragon performance.

Each MT backend evaluates 627 unique language-aware inputs covering the five treatments and the original-source context baseline. 301 identical inputs per backend reuse prior valid FP32 beam2 output from the same pinned checkpoint; 326 new inputs per backend were inferred on the new VPS. All 652 newly inferred outputs are present, nonempty, uncapped and failure-free. The report maps all 200 sources to all five treatments plus the original-source baseline for both models. Inference time from cached and new outputs is not mixed into a purported Snapdragon or comparable end-to-end latency figure.

103 Python phase3 tests passed after the final tooling changes. Existing Kotlin experimental normalization remains off by default; this task does not change ASR or select a final MT backend.

## Evidence

Local ignored root: `output/phase3/punctuation-btc200/`.
VPS root: `/home/opc/voxbridge-mt3-20261113/runs/punctuation-btc200/` on `<vps-host>`.

- `comparison.html`: searchable 400-row source/translation comparison, 200 rows per model.
- `{envit5,vinai}/summary.json`: whole-set scores plus directional diagnostics.
- `{envit5,vinai}/paired-details.jsonl`: every transformed input, output and unchanged reference.
- `{envit5,vinai}/{missing,reused}.jsonl`, `new/results.jsonl`, `cache-plan.json`: provenance and exact-input reuse.
- `FREEZE.json`, `sources.jsonl`, `variants.jsonl`: frozen all-200 inputs/settings/hashes.
- `question-review.jsonl`: separate exploratory question-intent annotations.
- `lexical-verification.json`: preservation check over all 1,000 candidates.
- `horizon-timing/`: separately timed inference with identical proposals verified against the frozen run.
