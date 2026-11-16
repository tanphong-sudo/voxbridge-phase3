# Safety logic hardening — 2026-11-14

## Scope

This pass hardens the pure Kotlin safety path before another QDC session. It does not claim human semantic acceptance of the 400 clean-source translation rows; those rows are used only to detect regressions and accidental over-blocking.

## Changes

- A safety-active source now requires its operative action to survive translation, including generic actions when the source is active. `open the door` → `the door` asks for a repeat.
- Action inversion, hazard substitution, polarity changes, object substitution, object-ID loss, urgency loss, and quantity changes remain fail-closed. Hazard/action/quantity changes are detect-only and cannot be repaired by insertion.
- A translation cannot invent a new critical action or hazard for an inactive ordinary source. Incidental prose objects and possessives such as `door's`, `professional one`, and address lines are guarded against false equipment IDs.
- Memory replay runs the same collapse and invented-safety checks. Feedback is re-extracted from the exact corrected source/target text; stale supplied frames cannot approve contradictory learning. A changed source/target starts approval accumulation again. Collapsed targets are blocked at write time.
- Measurements retain decimal values, comparison direction, and multiple values in order. Legacy single-value frames continue to compare as before.
- The repeated phrase collapse gate now handles long repeated clauses up to 64 tokens, which catches the recorded EnViT5 case `EV-9462`.

## Verification

- Host suite: **196/196 tests passed**.
- Synthetic safety benchmark: **51/51 decision matches, 0/38 unsafe passes, 0 false repairs**.
- Recorded QDC replay: 400 clean-source MT outputs; after hardening, 399 remain unchanged and the one repeated EnViT5 output is blocked. This is a logic replay, not fresh model/audio inference.
- Android app core integration compile passed on the VPS after the safety changes.

The safety layer is a fail-closed verifier and confirmation gate. It cannot prove arbitrary translation correctness outside the guarded lexicon, and the 400-row replay is not a human safety acceptance set.
