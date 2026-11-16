import json, re, sys, torch
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
torch.set_num_threads(4)
G = [l.rstrip("\n").split("\t") for l in open("glossary.tsv", encoding="utf-8") if l.strip()]
G.sort(key=lambda p: -len(p[0]))
PATS = [(re.compile(r"(?<![\w])" + re.escape(a) + r"(?![\w])", re.I), b) for a, b in G]
def inject(t):
    for p, b in PATS: t = p.sub(b, t)
    return t
name, rev = "VietAI/envit5-translation", "840bc88104d5a4277af740eaedb024df8c3093e7"
tok = AutoTokenizer.from_pretrained(name, revision=rev); model = AutoModelForSeq2SeqLM.from_pretrained(name, revision=rev).eval()
def tr(text):
    inputs = tok(f"vi: {text}", return_tensors="pt")
    with torch.inference_mode(): ids = model.generate(**inputs, num_beams=2, do_sample=False, max_new_tokens=160)
    return re.sub(r"^\s*(?:en|vi)\s*:\s*", "", tok.decode(ids[0], skip_special_tokens=True), flags=re.I).strip()
mode = sys.argv[1]
if mode == "vocab":
    for l in open("construction_vocab.jsonl"):
        r = json.loads(l)
        if r["src"] != "vi": continue
        inj = inject(r["text"]); hyp = tr(inj); low = hyp.lower()
        ok = all(any(t in low for t in g) for g in r["terms"])
        print(("OK " if ok else "XX ") + r["id"] + " | " + inj + " => " + hyp, flush=True)
else:
    refs = [json.loads(l) for l in open(sys.argv[2])]
    changed = 0
    for r in refs:
        src = r.get("source_text") or r.get("reference_source") or r.get("source") or ""
        if r.get("source_lang", "vi") != "vi" or not src: continue
        inj = inject(src)
        if inj != src:
            changed += 1
            print("CHANGED", r.get("id"), "|", src, "=>", inj, "\n  base:", tr(src), "\n  inj :", tr(inj), flush=True)
    print("vi sources:", sum(1 for r in refs if r.get("source_lang","vi")=="vi"), "changed:", changed)
