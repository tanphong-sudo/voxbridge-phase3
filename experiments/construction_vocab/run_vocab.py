import json, re, sys, time, torch
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
torch.set_num_threads(4)
name, rev = "VietAI/envit5-translation", "840bc88104d5a4277af740eaedb024df8c3093e7"
tok = AutoTokenizer.from_pretrained(name, revision=rev)
model = AutoModelForSeq2SeqLM.from_pretrained(name, revision=rev).eval()
rows = [json.loads(l) for l in open(sys.argv[1])]
out = []
for r in rows:
    inputs = tok(f"{r['src']}: {r['text']}", return_tensors="pt")
    with torch.inference_mode():
        ids = model.generate(**inputs, num_beams=2, do_sample=False, max_new_tokens=128)
    hyp = re.sub(r"^\s*(?:en|vi)\s*:\s*", "", tok.decode(ids[0], skip_special_tokens=True), flags=re.I).strip()
    low = hyp.lower()
    hits = [any(t in low for t in group) for group in r["terms"]]
    out.append({**r, "hyp": hyp, "term_hits": hits, "all_terms": all(hits)})
    print(json.dumps(out[-1], ensure_ascii=False), flush=True)
json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
