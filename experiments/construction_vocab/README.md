# Construction vocabulary check (EnViT5)

The set has 30 short site sentences: 15 VI→EN and 15 EN→VI. Each sentence carries a trade term: giàn giáo, cốp pha, cẩu tháp, dây an toàn, cốt thép, mũ bảo hộ, hố móng, hàn, cột, and so on.

**How it was run (2026-11-16):**

- Model: `VietAI/envit5-translation` at revision `840bc881`.
- Runtime: HuggingFace FP32 on CPU, beam 2, same prompt format as the app (`vi: …` / `en: …`).
- This is a host proxy. It is **not** the on-device QNN build, and quantization can change individual words.
- `term_hits` is a substring check against accepted target terms. The verdicts below were then reviewed by hand, because the substring check marks some correct synonyms as misses (for example *cần cẩu* for tower crane, or *xà* for beam).

Re-run:

```bash
HF_HUB_CACHE=<hf cache> python run_vocab.py construction_vocab.jsonl results.json
```

## Result

| Direction | Automatic term match | Meaning kept (manual review) |
|---|---|---|
| EN→VI | 11/15 | 14/15 |
| VI→EN | 9/15 | 9/15 usable, 2 partial, 4 wrong |

EN→VI handles site terms well: *giàn giáo, ván khuôn, dây đai, lan can, mũ cứng, máy trộn bê tông, lưới an toàn, búa khoan, kỹ sư kết cấu*. The one loss is CE-10, where *foundation pit* became just *hố*.

VI→EN is where trade vocabulary breaks:

| ID | Source | EnViT5 | Problem |
|---|---|---|---|
| CV-02 | Chưa được tháo **cốp pha**, … | "Not yet **unpacked**, …" | Term lost and the instruction is unclear. Safety-relevant. |
| CV-04 | … móc **dây an toàn** vào lan can | "hook your **seat belt** to the railing" | Wrong equipment. |
| CV-13 | **Hàn** xong nhớ đeo kính … | "Put on your glasses …" | "After welding" dropped. |
| CV-14 | **Cột** trục B bị nứt … | "B-axis's cracked …" | "Column" dropped. |
| CV-03 | **Cẩu tháp** đang nâng hàng | "Tower **derricks** are loading the cargo" | Understandable but off. |
| CV-05 | **Cốt thép** dầm này thiếu hai thanh | "This **steel beam** lacks two bars" | Partly right. |

## What the app does about it today

- Numbers, units and hazard words in these sentences are still protected by the safety-slot check. That check repairs the translation or asks the worker to repeat when one of them is lost.
- Pure terminology swaps such as *seat belt* for *harness* are **not** caught, because the slot check does not cover trade nouns.

## Roadmap

1. Add a small VI→EN construction glossary (about 50 terms) as a slot type, so a dropped or swapped trade term triggers repair or ask-repeat, the same way numbers do.
2. Fine-tune EnViT5 with LoRA on a few thousand construction sentence pairs, then re-quantize through AI Hub. Measure with this set and the BTC 200 so general quality does not regress.
3. Re-run this set on the device QNN build to confirm the host-proxy findings.

## Fix shipped: pinning trade terms before VI→EN translation

`ConstructionGlossary` (98 Vietnamese surfaces, listed in `domain-packs/construction_vi_en/glossary.csv`) writes the English term into the Vietnamese MT input. For example, "tháo cốp pha" becomes "tháo formwork", and EnViT5 carries the English word through. This applies to VI→EN only. Same host-proxy setup as above.

| Set | Without pinning | With pinning |
|---|---|---|
| VI→EN site sentences above (also used to choose terms) | 9/15 | 14/15 |
| 8 new VI sentences written afterwards (`heldout_vi.jsonl`) | 4/8 | 7/8 |
| BTC 200, VI sources whose text changes | — | 0/100 (score unaffected) |

Held-out examples:
- "vận thăng": "chopper" → "construction hoist"
- "đầm dùi": "stick" → "poker vibrator"
- "cột chống": "support column" → "shoring prop"

Still wrong: "xà gồ" (purlin). The held-out run stopped after 8 of 12 sentences because the shared VPS was saturated.

Not applied EN→VI. There the model already gets 14/15 right, and pinning made it worse ("cốp pha" became "cốp xe").

Excluded on purpose:
- ambiguous single syllables: "hàn" (weld or Korea), "cột" (column or to tie), "dầm" (beam or to be soaked);
- words the model already translates correctly: "bê tông", "thang".

## Fix shipped: grid lines, volumes and recognizer misspellings (16 Nov 2026)

The first demo recording showed three gaps in VI→EN. They were found with synthetic northern-accent voice clips:

- The recognizer wrote "trục B đến trục D" as "chục bờ đến chục giờ" (tr/ch merge, letter names), and EnViT5 translated that as "ten banks to ten hours". Even the correct text came out as "shaft B to shaft D".
- "mười hai khối" became "twelve blocks"; on site it means cubic meters.
- The recognizer wrote "bê tông" as "bơ tông" or "bơ công", and "cẩu tháp" as "cẩu thác", so the glossary never matched.

`ConstructionGlossary` now corrects these three misspellings first. It also writes "grid line X" for "trục X" and for the range "từ trục/chục X đến trục/chục Y", and "N cubic meters" for a number followed by "khối" before a material or the end of the sentence. `pin.py` mirrors the Kotlin rules for host checks (`hostcheck.py`).

| Demo sentence (as recognized) | Before | After (host proxy) |
|---|---|---|
| còn đổ bơ tông sàn từ chục bờ đến chục giờ khoảng mười hai khối | from ten banks to ten hours, about twelve blocks | from grid line B to grid line D about twelve cubic meters |
| xe bồn bơ tông đến lúc hai giờ chiều | The concrete tanker arrived at two o'clock | The concrete mixer truck arrived at 2:00 p.m. |
| cẩu thác đang nâng cốt thép lên tầng sáu | Extraction's raising the rebar | The tower crane is raising the rebar to the sixth floor |

Regression: no change in the MT input for any of the 200 BTC reference sources, nor for any of the 100 on-device VI ASR transcripts from the benchmark run. The VI→EN benchmark numbers therefore still hold.
