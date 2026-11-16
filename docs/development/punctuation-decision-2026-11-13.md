# Punctuation experiment: rejected for automatic use

Updated after the user's requirement: punctuation rules must be correct 100% before use.

## Latest follow-up

The user subsequently authorized testing a specialized punctuation model on all 200 BTC sources. That experiment is complete; see `docs/development/punctuation-btc200-results-2026-11-13.md` for the five-way comparison. Full neural punctuation is not selected, and the existing automatic rule remains disabled.

## Decision

**Do not enable `DecisionConfig.restoreViPunctuation`.** It remains false by default. The heuristic and its tests are experimental code, not a completed or accepted production feature. Do not integrate it into Android or advance it to QDC on the strength of the BTC result.

The rule cannot guarantee semantic correctness from text with missing punctuation. The same words can represent a question, a statement, or a quoted/reported question. Adding more keyword exceptions can fix individual examples but does not establish a universal guarantee. If zero incorrect edits is mandatory, the viable automatic baseline is preserving existing punctuation and abstaining from inferred punctuation changes. This guarantees no new edits from this stage; it does not guarantee the incoming ASR punctuation is correct.

## Evidence

The frozen v3 rule passes 150 exposed synthetic regression inputs: 55 questions, 95 nonquestions, zero lexical changes ignoring case, and idempotence on all 150. These inputs include cases used to develop the rules. This is not 100% correctness on arbitrary or real ASR input.

A subsequent adversarial audit finds **8 failures out of 10 deliberately selected examples**, including:

| Intended input | Incorrect rule output |
|---|---|
| Tôi nói không. | Tôi nói không? |
| Tôi chọn không. | Tôi chọn không? |
| Mấy anh đang sửa máy. | Mấy anh đang sửa máy? |
| Ai nấy đều phải đội mũ. | Ai nấy đều phải đội mũ? |
| Anh ấy hỏi gì? (question mark removed for input) | Anh ấy hỏi gì. |

This audit establishes counterexamples; its failure rate is not an estimate of everyday usage accuracy. Raw audit: `output/phase3/punctuation/strict-rule-audit.jsonl`.

## Model experiment already completed

600 actual FP32 VPS inference outputs were saved: 150 sources × before/after × EnViT5/VinAI, beam2, four CPU threads per model. 130 pairs were run under v2; v3 produces byte-identical inputs for those 130 pairs (verified). The remaining 20 pairs were run under v3. No missing, failed or token-cap outputs.

| Group | N sources | EnViT5 chrF before → after | VinAI chrF before → after |
|---|---:|---:|---:|
| BTC VI sources | 100 | 54.74 → 57.56 | 54.67 → 60.07 |
| Initial construction controls | 30 | 46.36 → 47.47 | 45.19 → 44.20 |
| Additional controls | 20 | 44.38 → 45.47 | 53.96 → 58.57 |

References are AI drafts, without human verification. Inputs were synthetically lowercased and question marks replaced with periods. EN source input is unchanged and was not rerun. Scores measure reference similarity, not percentage of semantically correct translations. Both improvements and regressions remain in raw outputs.

On the 100 BTC sources, the English outputs ending in question marks increased from 20/35 to 35/35 for EnViT5 and 23/35 to 35/35 for VinAI. This is only a surface punctuation count, not semantic correctness. EnViT5 still produces a question mark on one nonquestion source after restoration.

Warm JVM rule timing on VPS (13,000 calls): p50 0.007113 ms, p95 0.013660 ms. Excludes startup and translation; not Snapdragon performance.

149/149 host tests passed at v2 and 150/150 at v3; 91/91 Python phase3 tests passed. Passing these tests does not override the failed adversarial audit. The v2 runner's post-inference scorer crashed on an empty original-reference group; outputs were preserved and scored separately. The runner now reports null for that group, verified by regression test. No inference rerun was required for recovery.

## Artifacts and next boundary

- `output/phase3/punctuation/summary.json`: 130-pair score summary.
- `output/phase3/punctuation/extra-summary.json`: additional 20-pair summary.
- `output/phase3/punctuation/punctuation-v2-{envit5,vinai}/paired-details.jsonl`: per-source outputs, references, gains and regressions.
- `output/phase3/punctuation/punctuation-v3-extra-{envit5,vinai}/`: additional outputs.
- `output/phase3/punctuation/v3/`: frozen inputs and input-reuse verification.
- `output/phase3/punctuation/verification.json`: content-token and idempotence checks.
- `output/phase3/punctuation/strict-rule-audit.jsonl`: counterexamples disproving the strict requirement.

Do not continue patching individual words and describe the result as universally safe. A future proposal must explicitly distinguish abstention, user confirmation, and probabilistic inference. User review or audio evidence can supply missing intent; a text-only keyword rule cannot prove it.
