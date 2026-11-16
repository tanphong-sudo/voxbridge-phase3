# PaperBanana input for the Section 3.2 architecture diagram

Settings: Pipeline Mode `demo_full`, Output Type `diagram`, Retrieval `auto`, Candidates 3, Aspect Ratio `21:9`, Max Critic Rounds 2.

## Method Content / Plot Data

```
## VoxBridge: on-device VI↔EN speech translation pipeline

All components sit inside one phone (Snapdragon 8 Gen 3). Components are grouped by the processor they run on: CPU or Hexagon NPU.

Main path, left to right:
1. Hold-to-talk microphone (with live audio check)
2. Silero VAD (CPU)
3. ASR (NPU): PhoWhisper-small for Vietnamese, Parakeet-v2 for English
4. Text clean-up (CPU): stutter collapse, construction glossary
5. MT (NPU): EnViT5
6. Safety check (CPU): numbers, units, negation, hazards
7. Decision gate
8. TTS: Piper frontend (CPU) + vocoder (NPU)
9. Loudness limiter → speaker

Decision gate outputs:
- Verified → TTS → speaker.
- Repaired → "Tap to confirm" → TTS → speaker.
- Rejected → Recovery gate.

Recovery gate (tries to still reach the best possible output):
- Best-effort recovery: alternate ASR text, second MT model (EnViT5-stable), shorter segments → draft shown → user confirms → TTS → speaker.
- No usable draft → Ask to repeat with a reason hint (closer / softer / slower) → user speaks again → loops back to the microphone.

Side path: one-tap site phrases (pre-recorded) → speaker directly, bypassing ASR and MT.

Outside the phone, offline only: Qualcomm AI Hub compiles the NPU models before install (dashed arrow into the NPU group, labelled "ahead-of-time compile").
```

## Figure Caption / Visual Intent

```
Figure 1: VoxBridge architecture. A clean left-to-right flow inside a rounded phone boundary labelled "On-device, offline". Two soft background lanes mark where each block runs: CPU (light grey) and Hexagon NPU (light orange). After the decision gate, three clearly separated branches: verified (straight to speech), repaired (confirm, then speech), rejected (recovery gate). The recovery gate has two exits: a best-effort draft that the user confirms before speech, and an "ask to repeat" loop that curves back to the microphone. A small side path shows one-tap site phrases going straight to the speaker. Qualcomm AI Hub sits outside the phone with a dashed arrow. Style: flat vector, white background, thin consistent strokes, rounded rectangles, short labels only (model names, no sentences), simple line icons for microphone and speaker, at most three accent colours, generous spacing, no 3D, no gradients, no drop shadows, no clutter.
```
