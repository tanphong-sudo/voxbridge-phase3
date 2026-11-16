"""Assemble the VoxBridge demo video from the QDC screen recordings, the input clips and the
phone's saved speech output. Usage: python3 assemble.py <run dir> <clips dir> <out.mp4>"""
import json, re, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

RUN, CLIPS, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
WORK = RUN / "build"; WORK.mkdir(exist_ok=True)
W, H = 1920, 1080
BG, INK, MUTED, ACCENT, FRAME = "#F3F4F1", "#16202A", "#5B6670", "#C2410C", "#16202A"
F = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
FB = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
def font(size, bold=False): return ImageFont.truetype(FB if bold else F, size)
PHONE_H = 1000; PHONE_W = round(PHONE_H * 1080 / 2400 / 2) * 2; PX, PY = 170, 40
TITLE_S, END_S = 48.0, 9.0
INTRO_VOICE = Path(__file__).with_name("intro-voice.m4a")  # spoken introduction over the title card

TURNS = {
    "01-vi": ("Turn 1", "Vietnamese → English", "Chào anh, hôm nay em giao ca cho anh ở tầng năm.", "Correct: \"I'm giving you your shift on the fifth floor.\""),
    "02-en": ("Turn 2", "English → Vietnamese", "Thanks. What work is left on level five?", "Correct: \"Cảm ơn, còn việc gì ở tầng 5?\""),
    "03-vi": ("Turn 3", "Vietnamese → English", "Còn đổ bê tông sàn từ trục B đến trục D, khoảng mười hai khối.", "Correct: twelve cubic meters of concrete, grid line B to grid line D"),
    "04-en": ("Turn 4", "English → Vietnamese", "Twelve cubic meters. When does the concrete truck arrive?", "Correct, both sentences spoken: \"Mười hai mét khối. Khi nào xe chở bê tông đến?\""),
    "05-vi": ("Turn 5", "Vietnamese → English", "Xe bồn bê tông đến lúc hai giờ chiều.", "Correct term and time: \"The concrete mixer truck … at two o'clock in the afternoon.\""),
    "06-vi": ("Turn 6", "Vietnamese → English", "Không được tháo cốp pha tầng bốn vì bê tông chưa đủ ngày.", "Correct: \"Formwork on the fourth floor must not be removed because the concrete is not ready…\""),
    "07-en": ("Turn 7", "English → Vietnamese", "Understood. Do not remove the formwork until Friday.", "Correct, shown as a safety warning: \"không được tháo ván khuôn cho đến thứ sáu\""),
    "08-vi": ("Turn 8", "Vietnamese → English", "Khi lên giàn giáo phải móc dây an toàn vào lan can.", "Correct: \"hook the safety harness to the railing\""),
    "09-en": ("Turn 9", "English → Vietnamese", "Is the tower crane working today?", "Correct, shown as a safety warning: \"Cần cẩu tháp có hoạt động hôm nay không?\""),
    "10-vi": ("Turn 10", "Vietnamese → English", "Cẩu tháp đang nâng cốt thép lên tầng sáu.", "Correct: \"The tower crane is raising the rebar to the sixth floor.\""),
    "11a-vi-noisy": ("Turn 11a", "Vietnamese → English, heavy street noise (≈0 dB SNR)", "Cẩu tháp đang nâng cốt thép lên tầng sáu.", "Correct despite the noise: \"The tower crane is raising the rebar to the sixth floor.\""),
    "11b-vi": ("Turn 11b", "Vietnamese → English, repeated clean", "Cẩu tháp đang nâng cốt thép lên tầng sáu.", "Clean repeat: same correct result, \"sixth floor\""),
    "quick": ("Turn 12", "One-tap site phrases", "Dừng lại! · Gọi cấp cứu ngay!", "Played at once in English: \"Stop!\", \"Call an ambulance!\""),
    "13-en": ("Turn 13", "English → Vietnamese", "Please check the rebar on column C3 before pouring.", "Correct, C3 kept: \"Xin hãy kiểm tra cốt thép của cột C3 trước khi đổ.\""),
    "14-vi": ("Turn 14", "Vietnamese → English", "Dạ, em kiểm tra xong sẽ báo anh.", "Correct: \"I'll let you know when I check.\""),
}
NOTE = ("Speech input: pre-recorded clips (synthetic macOS voices) fed to the app as its microphone, "
        "because this Qualcomm Device Cloud phone sits in a remote rack. Everything after the button "
        "release runs on the phone, offline. Sound: the input clip, then the phone's own spoken output.")

def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split():
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=fnt) <= width: line = trial
        else: lines.append(line); line = word
    return lines + [line] if line else lines

def frame(path, head, sub, said, shows, step):
    img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    d.rounded_rectangle([PX - 14, PY - 14, PX + PHONE_W + 14, PY + PHONE_H + 14], radius=36, fill=FRAME)
    x, wmax = 760, 1060
    d.text((x, 70), "VoxBridge", font=font(40, True), fill=INK)
    d.text((x, 122), "Offline VI ↔ EN push-to-talk translator · Snapdragon 8 Gen 3 · airplane mode", font=font(24), fill=MUTED)
    if step:
        d.text((x, 250), head, font=font(30, True), fill=ACCENT)
        d.text((x, 296), sub, font=font(30), fill=INK)
        y = 380
        d.text((x, y), "Spoken input", font=font(22), fill=MUTED); y += 36
        for l in wrap(d, said, font(40, True), wmax): d.text((x, y), l, font=font(40, True), fill=INK); y += 54
        y += 26
        d.text((x, y), "Result on the phone", font=font(22), fill=MUTED); y += 36
        for l in wrap(d, shows, font(30), wmax): d.text((x, y), l, font=font(30), fill=INK); y += 42
    else:
        d.text((x, 300), head, font=font(44, True), fill=INK)
        y = 370
        for l in wrap(d, sub, font(30), wmax): d.text((x, y), l, font=font(30), fill=INK); y += 44
    img.save(path)

def card(path, title, lines):
    img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    d.text((160, 300), title, font=font(72, True), fill=INK)
    y = 420
    for l in lines: d.text((160, y), l, font=font(36), fill=INK if not l.startswith("·") else MUTED); y += 62
    img.save(path)

def ff(*args): subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)

# ---- timing (device epoch ms) ----
starts = sorted((int((RUN / f"demo-v{i}.start").read_text().split()[0]), RUN / f"demo-v{i}.mp4")
                for i in (1, 2, 3) if (RUN / f"demo-v{i}.mp4").exists() and (RUN / f"demo-v{i}.mp4").stat().st_size > 0)
T0 = starts[0][0]
marks = [(int(a), b) for a, b in (l.split(" ", 1) for l in (RUN / "timeline.txt").read_text().splitlines())]
end_ms = next(t for t, m in marks if m == "end") + 1500
log = (RUN / "app-log.txt").read_text()
clip_at = {m.group(1)[:-4]: int(m.group(2)) for m in re.finditer(r"clip (\S+) startMs=(\d+)", log)}
# Turns missing from the logcat buffer start a measured offset after their hold mark.
HOLD_OFFSET_MS = int(__import__('os').environ.get('HOLD_OFFSET_MS', '80'))
for t, m in marks:
    if m.startswith("hold "): clip_at.setdefault(m.split()[1], t + HOLD_OFFSET_MS)
outs = sorted((int(p.stem), p) for p in (RUN / "out").glob("*.wav"))

# ---- screen video: segments placed at their own start times ----
parts = []
for i, (s, mp4) in enumerate(starts):
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(mp4)]))
    nxt = starts[i + 1][0] if i + 1 < len(starts) else T0 + 10**9
    keep = min(dur, (nxt - s) / 1000)
    p = WORK / f"seg{i}.mp4"
    ff("-i", str(mp4), "-t", f"{keep:.3f}", "-vf", f"scale={PHONE_W}:{PHONE_H},fps=30,format=yuv420p", "-an", "-c:v", "libx264", "-crf", "18", str(p))
    parts.append(p)
(WORK / "segs.txt").write_text("".join(f"file '{p}'\n" for p in parts))
ff("-f", "concat", "-safe", "0", "-i", str(WORK / "segs.txt"), "-t", f"{(end_ms - T0) / 1000:.3f}", "-c", "copy", str(WORK / "screen.mp4"))

# ---- caption background slideshow ----
events = [(T0, None)]
for t, m in marks:
    if m.startswith("hold "):
        key = m.split()[1] if m.startswith("hold ") else None
        if key is None:  # a swap belongs to the next hold
            nxt = next(mm for tt, mm in marks if tt > t and mm.startswith("hold "))
            key = nxt.split()[1]
        if events[-1][1] != key: events.append((t, key))
    if m == "quick stop": events.append((t, "quick"))
lines, shown = [], set()
for i, (t, key) in enumerate(events):
    t_next = events[i + 1][0] if i + 1 < len(events) else end_ms
    png = WORK / f"cap{i}.png"
    if key is None: frame(png, "Going offline", "Airplane mode is switched on, then VoxBridge is used without any network.", "", "", False)
    else:
        head, sub, said, shows = TURNS[key]; frame(png, head, sub, said, shows, True)
    lines.append(f"file '{png}'\nduration {(t_next - t) / 1000:.3f}\n")
lines.append(f"file '{png}'\n")
(WORK / "caps.txt").write_text("".join(lines))
ff("-f", "concat", "-safe", "0", "-i", str(WORK / "caps.txt"), "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-crf", "18", str(WORK / "caps.mp4"))
ff("-i", str(WORK / "caps.mp4"), "-i", str(WORK / "screen.mp4"), "-filter_complex", f"[0][1]overlay={PX}:{PY}:shortest=1",
   "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", str(WORK / "body.mp4"))

# ---- title and end cards ----
card(WORK / "title.png", "VoxBridge", ["Offline Vietnamese ↔ English speech translation for construction sites",
     "Team T&P · OneVoice AI Challenge 2026 · Phase 3 prototype",
     "· Snapdragon 8 Gen 3 (SM8650) phone on Qualcomm Device Cloud, Android 14",
     "· Construction shift handover: 14 spoken turns and two one-tap phrases, one uncut take",
     "· We have no Snapdragon 8 Gen 3 phone, so this is a remote Qualcomm Device Cloud phone.",
     "· Speech input: synthetic voice clips fed as the microphone; the rest runs on the phone, offline."])
card(WORK / "end.png", "Measured on this device", ["200 BTC voice clips: all 198 spoken translations started within 2 s",
     "Median latency 766 ms (VI→EN) and 588 ms (EN→VI), release to first audio",
     "chrF 49.7 / 51.1 · ASR WER 3.7% (VI) / 6.8% (EN) · peak app memory 582 MiB",
     "ASR, translation and vocoder on the Hexagon NPU · models 2.66 GB",
     "· github.com/tanphong-sudo/voxbridge-phase3"])
for name, secs in (("title", TITLE_S), ("end", END_S)):
    ff("-loop", "1", "-i", str(WORK / f"{name}.png"), "-t", str(secs), "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-crf", "18", str(WORK / f"{name}.mp4"))
(WORK / "all.txt").write_text("".join(f"file '{WORK / n}.mp4'\n" for n in ("title", "body", "end")))
ff("-f", "concat", "-safe", "0", "-i", str(WORK / "all.txt"), "-c", "copy", str(WORK / "video.mp4"))

# ---- soundtrack: input clips + phone output, offset by the title card ----
inputs, filters = [], []
ACTIONS = sorted(t for t, m in marks if m.split()[0] in ("hold", "swap", "quick", "end"))
def place(path, t_ms, gain, cut=False):
    k = len(inputs) // 2; inputs.extend(["-i", str(path)])
    delay = int(TITLE_S * 1000 + (t_ms - T0))
    # A tap on the phone stops playback, so speech is cut at the next action, as on the device.
    stop = next((a for a in ACTIONS if a > t_ms + 50), None) if cut else None
    trim = f"atrim=0:{(stop - t_ms) / 1000:.3f}," if stop else ""
    filters.append(f"[{k}]{trim}aresample=48000,aformat=channel_layouts=stereo,volume={gain},adelay={delay}|{delay}[a{k}]")
if INTRO_VOICE.exists():
    k = len(inputs) // 2; inputs.extend(["-i", str(INTRO_VOICE)])
    filters.append(f"[{k}]atrim=start=0.4,asetpts=PTS-STARTPTS,atempo=1.15,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo,adelay=300|300[a{k}]")
for name, t in clip_at.items(): place(CLIPS / f"{name}.wav", t, 0.8)
for t, p in outs: place(p, t, 1.0, cut=True)
total = TITLE_S + (end_ms - T0) / 1000 + END_S
mix = "".join(f"[a{k}]" for k in range(len(filters)))
fc = ";".join(filters) + f";{mix}amix=inputs={len(filters)}:normalize=0:duration=longest,apad,atrim=0:{total:.3f},alimiter=limit=0.95[out]"
ff(*inputs, "-filter_complex", fc, "-map", "[out]", "-c:a", "aac", "-b:a", "192k", str(WORK / "audio.m4a"))
ff("-i", str(WORK / "video.mp4"), "-i", str(WORK / "audio.m4a"), "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "copy", "-shortest", str(OUT))
print(json.dumps({"out": str(OUT), "seconds": round(total, 1), "clips": len(clip_at), "outputs": len(outs),
                  "turn_times_s": {k: round(TITLE_S + (t - T0) / 1000, 1) for t, k in events if k}}, ensure_ascii=False))
