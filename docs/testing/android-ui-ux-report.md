# Android push-to-talk UI — one Operate pass

Scope: existing Compose `MainScreen` and `TalkButton`. Applied the locally installed
`impeccable` skill (v4.4.0), ran `impeccable context` once, and audited source against its
Android and Operate guidance. This was a focused refinement of an existing surface.

Code audit found a P1 repair-flow ambiguity: the pending repaired sentence had a Replay
control even though speech required explicit confirmation. It also found P1 busy-state
ambiguity (the talk button appeared ready during processing), P2 unclear repeat/no-speech
copy for VI speakers, and P2 undersized diagnostics targets. The large PTT target and
VI↔EN direction were already present.

The pass presents bilingual **Listening / Processing / Speaking / Ready** states, disables the PTT
and direction swap while work is in progress, and keeps the 132 dp hold target fixed in place.
A repaired result now shows a single **Confirm and speak** action; Replay appears only after
confirmation. Repeat/no-speech prompts follow the source language. Playback failure and mic
permission messages name the next action. TalkBack gets button actions and labels; secondary
controls are at least 48 dp tall. Core decision, ASR, MT, and safety algorithms were unchanged.

Verification: `:core:test`, `:android:app:testDebugCpuUnitTest`, and
`:android:app:assembleDebugCpu` passed under JDK 17. The Android unit suite initially caught an
old test expectation for the frozen 0.15/0.20 audio gates; the test was corrected to expect
NO_SPEECH below 0.15 and ASK_REPEAT between 0.15 and 0.20. A second bounded visual pass used
the Android 34 arm64 emulator with `debugUi` FakePipeline. Its first result screen hid Replay
below the visible scroll area; tighter header, direction, state, and result spacing brought Replay
onto the screen. System bars now match the dark interface. The final
[emulator result screenshot](android-ui-emulator-result.png) shows the visible action at normal
font scale; a 1.3× font-scale check also kept Replay visible. The same pass rebuilt `debugUi` and
`debugCpu` and reran Android/core tests. The emulator does **not** validate real model inference,
phone mic/speaker, TalkBack gestures, or glove/PPE use. The partner handoff includes those checks.
