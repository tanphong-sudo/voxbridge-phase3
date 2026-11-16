"""Mix the BTC 200 bench WAVs with DEMAND noise at fixed SNRs for the on-device noise bench.

SNR is measured against the speech's active frames (20 ms frames within 35 dB of the loudest),
so leading/trailing silence does not inflate it. Noise type is chosen per utterance by a stable
hash, and the noise segment offset is seeded, so the corpus is reproducible.
"""
import hashlib, json, sys, wave
from pathlib import Path
import numpy as np

CORPUS = Path(sys.argv[1])            # .../files (contains corpus/manifest200.jsonl)
NOISE = Path(sys.argv[2])             # ~/data/noise
SNRS = [int(x) for x in sys.argv[3].split(",")]
KINDS = ["STRAFFIC", "TMETRO", "DWASHING", "PCAFETER"]

def read(p):
    with wave.open(str(p)) as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, p
        return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float64) / 32768, w.getframerate()

def write(p, x, sr):
    p.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 32767 / 32768) * 32768).astype("<i2").tobytes())

def active_rms(x, sr):
    f = int(sr * 0.02); n = len(x) // f
    e = (x[: n * f].reshape(n, f) ** 2).mean(1)
    keep = e[e > e.max() * 10 ** (-35 / 10)]
    return float(np.sqrt(keep.mean()))

def resample(x, src, dst):
    if src == dst: return x
    t = np.arange(int(len(x) * dst / src)) * src / dst
    return np.interp(t, np.arange(len(x)), x)

noise = {}
for k in KINDS:
    x, sr = read(NOISE / k / "ch01.wav")
    noise[k] = (x, sr)

rows = [json.loads(l) for l in open(CORPUS / "corpus/manifest200.jsonl")]
for snr in SNRS:
    out_rows = []
    for r in rows:
        x, sr = read(CORPUS / r["wav"])
        h = int(hashlib.sha256(r["id"].encode()).hexdigest(), 16)
        kind = KINDS[h % len(KINDS)]
        nx, nsr = noise[kind]
        nx = resample(nx, nsr, sr)
        start = np.random.default_rng(h % 2**32 + snr).integers(0, len(nx) - len(x))
        seg = nx[start: start + len(x)]
        gain = active_rms(x, sr) / (np.sqrt((seg ** 2).mean()) * 10 ** (snr / 20))
        mixed = x + seg * gain
        peak = np.abs(mixed).max()
        if peak > 0.99: mixed *= 0.99 / peak   # keep it unclipped; SNR is unchanged by a common gain
        rel = f"corpus/wav-snr{snr}/{r['id']}.wav"
        write(CORPUS / rel, mixed, sr)
        out_rows.append({**r, "wav": rel, "sha256": hashlib.sha256((CORPUS / rel).read_bytes()).hexdigest(),
                         "noise": kind, "snr_db": snr})
    with open(CORPUS / f"corpus/manifest200-snr{snr}.jsonl", "w") as f:
        for o in out_rows: f.write(json.dumps(o) + "\n")
    print(snr, len(out_rows), {k: sum(o["noise"] == k for o in out_rows) for k in KINDS})
