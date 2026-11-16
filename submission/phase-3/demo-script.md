# VoxBridge Phase 3 demo: construction shift handover

Device: Snapdragon 8 Gen 3 (SM8650) reference phone on Qualcomm Device Cloud, Android 14, airplane mode.
Speakers: **Thợ (VI)**, a Vietnamese site worker, and **Engineer (EN)**, an English-speaking site engineer.
Recorded length: 3:55, one uncut take (video: https://github.com/tanphong-sudo/voxbridge-phase3/releases/download/p3-final/voxbridge-p3-demo.mp4).

## Input note (say this in the video and in the submission)

The QDC phone sits in a remote rack, so nobody can speak into its microphone. Each turn below is a synthetic voice clip (macOS voices Linh for Vietnamese, Reed for English) fed to the app as microphone input by the demo clip feed (`DemoFeed.kt`). Everything after that runs on the phone, offline, and is the same as a live press: VAD, ASR, glossary, MT, safety check and TTS. The QDC screen stream has no sound, so the phone saves each spoken output and the video soundtrack is rebuilt from those files (`tools/demo/assemble.py`).

## Turns

| # | Speaker | Line | What it shows |
|---|---|---|---|
| 1 | VI | Chào anh, hôm nay em giao ca cho anh ở tầng năm. | First voice-to-voice exchange, VI→EN |
| 2 | EN | Thanks. What work is left on level five? | Swap direction, EN→VI, Vietnamese voice |
| 3 | VI | Còn đổ bê tông sàn từ trục B đến trục D, khoảng mười hai khối. | Numbers and grid locations kept |
| 4 | EN | Twelve cubic meters. When does the concrete truck arrive? | Numbers EN→VI |
| 5 | VI | Xe bồn bê tông đến lúc hai giờ chiều. | Time; glossary: xe bồn bê tông → concrete mixer truck |
| 6 | VI | Không được tháo cốp pha tầng bốn vì bê tông chưa đủ ngày. | Negation + glossary: cốp pha → formwork |
| 7 | EN | Understood. Do not remove the formwork until Friday. | Safety negation EN→VI |
| 8 | VI | Khi lên giàn giáo phải móc dây an toàn vào lan can. | Glossary: scaffolding, safety harness, guardrail |
| 9 | EN | Is the tower crane working today? | Trade term EN→VI |
| 10 | VI | Cẩu tháp đang nâng cốt thép lên tầng sáu. | Glossary: tower crane, rebar |
| 11a | VI | Turn 10 mixed with heavy site noise (0 dB) | Translated correctly despite the noise |
| 11b | VI | Turn 10 again, clean | Same result when repeated clean |
| 12 | — | Tap the emergency phrases "Dừng lại!" and "Gọi cấp cứu!" | Instant one-tap phrases, no ASR or MT |
| 13 | EN | Please check the rebar on column C3 before pouring. | Grid reference EN→VI |
| 14 | VI | Dạ, em kiểm tra xong sẽ báo anh. | Close-out |

Recording tips: one sentence per file, 16 kHz mono WAV or any phone recording (converted later), about 30 cm from the mic, normal speaking pace, half a second of silence before and after.

## Shot list

| Time | Shot |
|---|---|
| 0:00 | Title card: VoxBridge, offline VI↔EN push-to-talk for construction sites |
| 0:09 | Airplane mode on, VoxBridge ready screen |
| 0:17 | Turns 1–10, with the translated audio on the soundtrack and on-screen text visible |
| 2:51 | Turn 11a (heavy noise), 3:01 turn 11b (clean) |
| 3:11 | Turn 12: emergency phrases |
| 3:20 | Turns 13–14 |
| 3:46 | End card: on-device numbers |
