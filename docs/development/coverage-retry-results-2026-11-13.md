# Coverage/repetition and sentence retry — VPS experiment

## Integration follow-up

The user subsequently authorized integrating only the collapse gate. That integration and a fresh all-200-per-model regression are complete; see `collapse-integration-2026-11-13.md`. Missing-action checks and retry remain diagnostic-only. The remainder below records the pre-integration experiment.

## Decision

Keep the experimental phrase-loop detector for further integration review. Keep missing-action diagnostics and sentence retry **diagnostic-only**, outside `DecisionEngine`. No production gate, retry, ASR, punctuation, safety repair or TTS behavior is enabled/changed by this experiment. No additional ML model is required by these diagnostics.

The lightweight detector improves obvious-collapse coverage but does not understand arbitrary missing meaning. Sentence retry can restore missing material, but can also lose context and introduce errors. Improved aggregate chrF is not sufficient to accept an automatic retry result.

## Evaluation data

- **240 controlled source/target pairs**: 40 directional parents from 20 bilingual semantic pairs; each has full target, compact paraphrase, deleted second proposition, deleted proposition padded with fluent filler, phrase repetition, and blank output. 80 good / 160 bad; the missing-proposition subgroup contains 80 cases. References/labels were authored before MT inference. Controls are synthetic, AI-authored and not human-reviewed; lexical fixes were tested on exposed controls, so this is regression evidence rather than a blind acceptance set.
- **400 existing BTC outputs**, 200 from each retained beam2 model, using original BTC source text; punctuation restoration stays off. No corpus split selection.
- Source-output AI review: deterministic ten multi-sentence outputs per model plus flagged examples, 24 outputs total; one ambiguous case excluded, leaving **23 reviewed outputs (21 without coverage loss, two missing meaningful material)**. This is not human verification or a review of all 400 outputs. Other mistranslations can remain in rows labeled free of coverage loss.
- **240 new MT calls** for 40 two-sentence sources × full/segment1/segment2 × two backends, plus **eight oracle calls** for two manually discovered BTC omissions on both models. All 248 new outputs complete, nonempty, uncapped, failure-free.

## Detector results

| Detector | Bad controls detected / 160 | Good controls falsely flagged / 80 |
|---|---:|---:|
| Existing blank / single token repeated >=6 | 40 | 0 |
| Experimental blank / repeated phrase | 80 | 0 |
| Above + missing action-cue suspicion | 128 | 6 |

The experimental hard-failure detector detects all 40 blank and all 40 repeated-phrase controls. This is not a claim to catch every form of repetition. It checks repeated token blocks, protects source-side genuine repetition, and is tested on legitimate repeated commands as well as decimal/ID/abbreviation sentence boundaries.

Missing-action suspicion catches **48/80 missing-proposition controls**, misses 32, and wrongly flags six complete paraphrases. Overall suspect precision on controls is 128/134 (95.52%), but that includes the easy blank/loop cases and must not be presented as semantic omission detection accuracy.

On each 200-output actual BTC set: zero hard failures, one lexical suspicion, **zero automatic retry candidates**. The final suspicions are false alarms on `EV-2748`: the noun “phone” is mistaken for a call action. In the AI-reviewed actual subset the detector misses both discovered omissions and has two false alarms (0 TP, 2 FP, 2 FN, 19 TN). It therefore fails the readiness criterion for an automatic meaning-loss gate.

Earlier diagnostics also exposed inflection and lexical ambiguity: `stopped` was not recognized as `stop`, `transferred` as transfer, “đội cứu hộ” was confused with wearing equipment. Regression fixes were added and the original v1 diagnostics preserved under `output/phase3/coverage/diagnostics-v1/`. Remaining lexical ambiguity is a reason to keep the omission stage diagnostic-only, not to infer that a longer keyword list proves semantic preservation.

## Full segment-vs-paragraph comparison

Same 40 directional sources and frozen references for each model. Both are FP32 CPU, beam2. The row counts are all-pair ablations; not all 40 are automatically selected by the detector.

| Model | Whole-source chrF | Split/translate/join chrF | Higher sentence chrF | Lower | Equal |
|---|---:|---:|---:|---:|---:|
| EnViT5 | 59.59 | 64.10 | 26 | 9 | 5 |
| VinAI v2 | 64.57 | 64.61 | 17 | 13 | 10 |

These are reference-similarity scores, not counts of semantically correct repairs. After lexical regression fixes, the detector proposes two eligible retries per backend on these 40 sources; all four have higher chrF, but that does not establish complete repair or permission to speak.

Examples of context-loss regressions:

- EnViT5: “Đóng van cấp nước. Mở cửa thoát hiểm.” Whole-source keeps “Open the emergency exit”; segmented result becomes “Open an exit”, losing emergency specificity.
- VinAI: “Đeo kính bảo hộ. Đóng cửa tủ điện.” Whole-source has “Close the electrical cabinets”; segmented result has “Close the power cord”, a new object error.
- EnViT5: “Gọi đội cứu hộ. Chờ ở cổng chính.” Whole-source says “Call the rescue team”; segmented result says “Call a lifeguard”.

Every joined result was replayed through the unchanged Kotlin `DecisionEngine` safety/repair path with the original full source. This is a saved-output replay, not an audio inference run. Across all 40 joined outputs, safety asks repeat in 10 EnViT5 and 11 VinAI cases. For example “Dừng máy” becomes “Stop the engine” on retry; it restores the stop action but does not satisfy the guarded machine-object match, so the full pipeline still rejects it. A lexical-check pass is not treated as a verified rescue.

## Real omission examples, selected by reviewer

- EnViT5 `EV-6602` drops the entire final sentence about giving antibiotics today. Splitting recovers “Vì vậy, tôi sẽ cho bạn một đợt kháng sinh ngày hôm nay.”
- VinAI `EV-8189` drops “the products you're using” from the relationship question. Splitting recovers “Giữa loại sản phẩm anh đang dùng và triệu chứng của anh?”

The previously missing proposition is present after retry in both original failing model/case combinations. Residual translation errors may remain. These are **oracle-triggered** repairs: a reviewer identified the omission; the automatic detector missed it. They do not count as automatic recoveries. The joined versions were also replayed through the full current pipeline; both return `ModelTranslation` (not safety-verified semantic correctness).

## Time, scope and implementation

On the VPS under concurrent unrelated workloads:

- Kotlin evaluator only, 4,000 warm calls over the actual-output set: p50 **0.053111 ms**, p95 **0.170338 ms**. Excludes MT and safety; not Snapdragon.
- Whole-source MT median on controls: EnViT5 1,687 ms, VinAI 2,686 ms.
- Two extra sentence calls median: EnViT5 2,071 ms, VinAI 3,125 ms. These are added after the first attempt. Scheduling changed from nice 15 to normal priority because other work starved these processes; raw times remain saved. They cannot establish controlled speed comparisons or the <2 s phone-pipeline requirement.

Retry proposals require 2–3 explicitly separated source sentences, <=800 characters, and a hard failure or missing-cue suspicion. A single short output or merged sentence cannot trigger retry alone. No recursive retry exists. Sentence boundaries protect decimals, IDs, common titles, initials and e.g./i.e.; this is not a general linguistic sentence parser.

Implementation: `TranslationCoverage.kt`, `TranslationCoverageTest.kt`, `CoverageCli.kt`, `CoverageBenchmark.kt`, `coverage_experiment.py`, `score_coverage_experiment.py`, `coverage_report.py`. 158/158 host tests and 106/106 phase3 Python tests passed. Production `DecisionEngine` is not wired to the experimental module.

## Evidence and next decision

Local: `output/phase3/coverage/`. VPS: `/home/opc/voxbridge-mt3-20261113/coverage` on `<vps-host>`.

- `comparison.html`: all 80 model/parent paragraph-vs-sentence pairs.
- `summary.json`, `controls-diagnostics.jsonl`, `actual-diagnostics.jsonl`: counts and raw diagnostics.
- `actual-review.jsonl`, `actual-review-selection.json`: review provenance and selection.
- `retry-details.jsonl`, `retry-diagnostics.jsonl`: paired MT and complete safety rechecks.
- `oracle-review.jsonl`, `oracle-pipeline-check.jsonl`, `oracle-{envit5,vinai}/`: real omission examples and limitations.
- `FREEZE.json`, `parents.jsonl`, `controls.jsonl`, `retry-manifest.jsonl`: frozen data/provenance.
- `detector-timing.json`, `host-final-v3/`, `diagnostics-v1/`: timing, tests and original failed diagnostics.

Prioritize targeted real-output semantic review and better omission evidence before enabling automatic retry. Do not add blanket sentence splitting, a length-based refusal, or claim general missing-meaning detection from these lexical cues. QDC remains deferred by the user's earlier decision.
