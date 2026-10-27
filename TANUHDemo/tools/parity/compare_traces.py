#!/usr/bin/env python3
"""
Compare two parity traces (format v1, see ParityTrace.kt) stage by stage.

    python3 compare_traces.py avni-20261027-101500.txt sdk-20261027-102000.txt
    python3 compare_traces.py avni.txt sdk.txt --md parity-report.md
    python3 compare_traces.py avni.txt sdk.txt --pair-by content   # avni re-saved the photos (different files)
    python3 compare_traces.py avni.txt sdk.txt --pair-by order     # pair strictly in run order

Pairing:
  * images are paired by INPUT sha256 (the exact source file bytes). When the apps received different
    files of the same photo (e.g. avni's camera/attachment flow re-saved it smaller), use
    --pair-by content: each image is matched to the one with the closest CANVAS colour means. INPUT,
    DECODE and later stages are then expected to differ, and the report shows by how much.
    --pair-by order pairs strictly in the order the images appear;
  * models are paired by MODEL sha256 (SHA-256 of the plaintext model file; avni's modelKey).

avni writes one record per model call (3 calls per image for the 3-fold ensemble); the script merges
them per image and checks the repeated stage lines agree with each other. TIME lines are reported as a
timing summary but never compared. Exit code 0 = everything matched, 1 = at least one difference.
Standard library only.
"""
import argparse
import math
import sys
from collections import OrderedDict, defaultdict

STAGES = ["DECODE", "CANVAS", "GRAYWORLD", "TENSOR"]


def parse_line(line):
    parts = line.strip().split(" ")
    kind, kv = parts[0], OrderedDict()
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            kv[k] = v
    return kind, kv


def read_trace(path):
    header, records, cur = None, [], None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            if line.startswith("#"):
                header = header or line
                continue
            kind, kv = parse_line(line)
            if kind == "BEGIN":
                cur = {"BEGIN": kv, "lines": []}
            elif kind == "END":
                if cur is not None:
                    records.append(cur)
                cur = None
            elif cur is not None:
                cur["lines"].append((kind, kv))
    return header, records


class Image:
    def __init__(self, key):
        self.key = key
        self.name = None
        self.input = None
        self.stages = {}
        self.stage_conflicts = []
        self.models = OrderedDict()
        self.combined = None
        self.errors = []
        self.times = defaultdict(list)
        self.calls = 0


def aggregate(records):
    images, order = {}, []
    for rec in records:
        first = {}
        for kind, kv in rec["lines"]:
            first.setdefault(kind, kv)
        inp = first.get("INPUT", {})
        key = inp.get("sha256") or "no-input-%d" % len(order)
        img = images.get(key)
        if img is None:
            img = images[key] = Image(key)
            order.append(key)
        img.calls += 1
        img.name = img.name or first.get("IMAGE", {}).get("name")
        img.input = img.input or inp
        seen_pipeline = False
        for kind, kv in rec["lines"]:
            if kind == "PIPELINE":
                if seen_pipeline:        # only the first pipeline's stages are compared
                    break
                seen_pipeline = True
            if kind in STAGES:
                prev = img.stages.get(kind)
                if prev is None:
                    img.stages[kind] = kv
                elif prev != kv:
                    img.stage_conflicts.append((kind, prev, kv))
            elif kind == "MODEL":
                img.models[kv.get("sha256", "?")] = kv
            elif kind == "COMBINED":
                img.combined = kv
            elif kind == "ERROR":
                img.errors.append(kv.get("message", "").replace("_", " "))
            elif kind == "TIME":
                try:
                    img.times[kv.get("stage", "?")].append(float(kv.get("ms", "nan")))
                except ValueError:
                    pass
    for img in images.values():
        # A failed attempt followed by a successful re-run of the same image (same trace file) is history,
        # not a failure: keep it as a note only.
        if img.errors and img.models:
            img.notes = ["earlier attempt failed: %s" % e for e in img.errors]
            img.errors = []
        else:
            img.notes = []
    return [images[k] for k in order]


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def compare_kv(a, b):
    """Returns (all_equal, rows) where rows = [(key, a, b, delta-or-None, equal)]."""
    rows, ok = [], True
    for k in list(a.keys()) + [k for k in b.keys() if k not in a]:
        va, vb = a.get(k), b.get(k)
        eq = va == vb
        ok &= eq
        d = None
        if not eq:
            fa, fb = num(va), num(vb)
            if fa is not None and fb is not None and not (math.isnan(fa) or math.isnan(fb)):
                d = fb - fa
        rows.append((k, va, vb, d, eq))
    return ok, rows


def short(v, n=16):
    if v is None:
        return "—"
    return v if len(v) <= n + 3 else v[:n] + "…"


def describe_unpaired(P, img):
    """Shows what an unpaired image's own trace contains, so errors and sizes are not hidden."""
    inp, dec = img.input or {}, img.stages.get("DECODE", {})
    P("- input: %s bytes, sha256 %s; decoded %sx%s" % (inp.get("bytes", "—"), short(inp.get("sha256")), dec.get("w", "?"), dec.get("h", "?")))
    for sha, m in img.models.items():
        P("- model %s: logit %s, %s" % (m.get("name") or short(sha, 12), m.get("logit"), m.get("label")))
    for e in img.errors:
        P("- **ERROR:** %s" % e)
    for n in img.notes:
        P("- note: %s" % n)
    if not img.models and not img.errors:
        P("- no model results")
    P("")


def derived_combined(img, positive_label):
    labels = [m.get("label") for m in img.models.values()]
    if len(labels) < 2:
        return None
    return positive_label if all(l == positive_label for l in labels) else "Negative"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("trace_a", help="first trace (e.g. avni-*.txt)")
    ap.add_argument("trace_b", help="second trace (e.g. sdk-*.txt)")
    ap.add_argument("--pair-by", choices=["hash", "content", "order"], default="hash")
    ap.add_argument("--positive-label", default="Positive")
    ap.add_argument("--md", help="also write a Markdown report to this file")
    args = ap.parse_args()

    ha, ra = read_trace(args.trace_a)
    hb, rb = read_trace(args.trace_b)
    ia, ib = aggregate(ra), aggregate(rb)
    name_a = (ra[0]["BEGIN"].get("app") if ra else None) or "A"
    name_b = (rb[0]["BEGIN"].get("app") if rb else None) or "B"

    if args.pair_by == "hash":
        by_b = {i.key: i for i in ib}
        pairs = [(a, by_b.get(a.key)) for a in ia]
        only_b = [b for b in ib if b.key not in {a.key for a in ia}]
    elif args.pair_by == "content":
        def means(img):
            c = img.stages.get("CANVAS") or img.stages.get("GRAYWORLD") or {}
            v = [num(c.get(k)) for k in ("mean_b", "mean_g", "mean_r")]
            return None if None in v else v
        cand = [(sum((x - y) ** 2 for x, y in zip(means(a), means(b))) ** 0.5, i, j)
                for i, a in enumerate(ia) for j, b in enumerate(ib) if means(a) and means(b)]
        used_a, used_b, match = set(), set(), {}
        for d, i, j in sorted(cand):              # greedy closest-first
            if i in used_a or j in used_b:
                continue
            used_a.add(i); used_b.add(j); match[i] = (j, d)
        pairs = [(a, ib[match[i][0]] if i in match else None) for i, a in enumerate(ia)]
        pair_dist = {id(a): match[i][1] for i, a in enumerate(ia) if i in match}
        only_b = [b for j, b in enumerate(ib) if j not in used_b]
    else:
        pairs = [(ia[i] if i < len(ia) else None, ib[i] if i < len(ib) else None) for i in range(max(len(ia), len(ib)))]
        only_b = []
    pair_dist = locals().get("pair_dist", {})

    out = []
    P = out.append
    P("# Parity report: %s vs %s" % (name_a, name_b))
    P("")
    P("- %s: `%s`  (%s)" % (name_a, args.trace_a, ha or "no header"))
    P("- %s: `%s`  (%s)" % (name_b, args.trace_b, hb or "no header"))
    P("- images: %d in %s, %d in %s; paired by %s" % (len(ia), name_a, len(ib), name_b, args.pair_by))
    for nm, recs in ((name_a, ra), (name_b, rb)):
        params = next((kv for r in recs for k, kv in r["lines"] if k == "PARAMS"), None)
        if params:
            P("- %s preprocessing in effect: `%s` `%s`" % (nm, params.get("preprocessor"), params.get("json")))
    P("")

    total_ok = True
    summary = []
    for a, b in pairs:
        title = (a.name if a else None) or (b.name if b else None) or "?"
        if a is None or b is None:
            total_ok = False
            side, img = (name_b, b) if a is None else (name_a, a)
            P("## %s: only in %s" % (title, side))
            P("")
            describe_unpaired(P, img)
            summary.append((title, "UNPAIRED", "", ""))
            continue
        P("## %s  (%s: %s · %s: %s)" % (title, name_a, a.name, name_b, b.name))
        P("")
        if id(a) in pair_dist:
            P("_Paired by content (CANVAS colour means %.2f apart), not by identical files._" % pair_dist[id(a)])
            P("")
        img_ok = True
        P("| stage | result | detail |")
        P("|---|---|---|")
        ok_in, rows = compare_kv(a.input or {}, b.input or {})
        img_ok &= ok_in
        P("| INPUT | %s | %s bytes, sha256 %s |" % ("MATCH" if ok_in else "**DIFF**", (a.input or {}).get("bytes"), short((a.input or {}).get("sha256"))))
        for st in STAGES:
            sa, sb = a.stages.get(st), b.stages.get(st)
            if sa is None and sb is None:
                continue
            if sa is None or sb is None:
                img_ok = False
                P("| %s | **MISSING** | only in %s |" % (st, name_a if sb is None else name_b))
                continue
            ok, rows = compare_kv(sa, sb)
            img_ok &= ok
            if ok:
                hashes = [v for k, v in sa.items() if k.endswith("sha256")]
                P("| %s | MATCH | %s |" % (st, ("hash " + short(hashes[0])) if hashes else "all values identical"))
            else:
                diffs = "; ".join("%s: %s → %s%s" % (k, short(va), short(vb), "" if d is None else " (Δ %+.3g)" % d)
                                  for k, va, vb, d, eq in rows if not eq)
                P("| %s | **DIFF** | %s |" % (st, diffs))
        for side, img in ((name_a, a), (name_b, b)):
            for kind, x, y in img.stage_conflicts:
                img_ok = False
                P("| %s (%s, repeated calls) | **INCONSISTENT** | the same image gave different %s lines |" % (kind, side, kind))
            for e in img.errors:
                img_ok = False
                P("| ERROR (%s) | **ERROR** | %s |" % (side, e))
        P("")

        P("| model (name / sha256) | %s logit | %s logit | Δ | %s label | %s label | result |" % (name_a, name_b, name_a, name_b))
        P("|---|---|---|---|---|---|---|")
        for sha in list(a.models.keys()) + [s for s in b.models.keys() if s not in a.models]:
            ma, mb = a.models.get(sha), b.models.get(sha)
            mname = (ma or {}).get("name") or (mb or {}).get("name")
            label_sha = ("%s / %s" % (mname, short(sha, 8))) if mname else short(sha, 12)
            if ma is None or mb is None:
                img_ok = False
                P("| %s | %s | %s | | %s | %s | **only in %s** |" % (label_sha, (ma or {}).get("logit", "—"), (mb or {}).get("logit", "—"),
                                                                  (ma or {}).get("label", "—"), (mb or {}).get("label", "—"),
                                                                  name_a if mb is None else name_b))
                continue
            fa, fb = num(ma.get("logit")), num(mb.get("logit"))
            same = ma.get("logit") == mb.get("logit") and ma.get("label") == mb.get("label")
            img_ok &= same
            delta = "0" if ma.get("logit") == mb.get("logit") else ("%+.2e" % (fb - fa) if fa is not None and fb is not None else "?")
            P("| %s | %s | %s | %s | %s | %s | %s |" % (label_sha, ma.get("logit"), mb.get("logit"), delta,
                                                     ma.get("label"), mb.get("label"), "MATCH" if same else "**DIFF**"))
        ca = (a.combined or {}).get("label") or derived_combined(a, args.positive_label)
        cb = (b.combined or {}).get("label") or derived_combined(b, args.positive_label)
        if ca or cb:
            img_ok &= ca == cb
            P("")
            P("Combined verdict (unanimous-and): %s = **%s**, %s = **%s** → %s" % (name_a, ca, name_b, cb, "MATCH" if ca == cb else "**DIFF**"))
        P("")
        total_ok &= img_ok
        summary.append((title, "MATCH" if img_ok else "DIFF", ca or "", cb or ""))

    for b in only_b:
        total_ok = False
        P("## %s: only in %s" % (b.name, name_b))
        P("")
        describe_unpaired(P, b)
        summary.append((b.name, "UNPAIRED", "", ""))

    P("## Summary")
    P("")
    P("| image | result | %s verdict | %s verdict |" % (name_a, name_b))
    P("|---|---|---|---|")
    for t, r, ca, cb in summary:
        P("| %s | %s | %s | %s |" % (t, r if r == "MATCH" else "**%s**" % r, ca, cb))
    P("")
    P("**Overall: %s**" % ("ALL STAGES MATCH" if total_ok else "DIFFERENCES FOUND"))
    P("")

    P("## Timing (informational, not compared)")
    P("")
    P("| stage | %s mean ms | %s mean ms |" % (name_a, name_b))
    P("|---|---|---|")
    ta, tb = defaultdict(list), defaultdict(list)
    for i in ia:
        for k, v in i.times.items():
            ta[k] += v
    for i in ib:
        for k, v in i.times.items():
            tb[k] += v
    for k in list(ta.keys()) + [k for k in tb.keys() if k not in ta]:
        fa = "%.1f" % (sum(ta[k]) / len(ta[k])) if ta.get(k) else "—"
        fb = "%.1f" % (sum(tb[k]) / len(tb[k])) if tb.get(k) else "—"
        P("| %s | %s | %s |" % (k, fa, fb))
    P("")

    text = "\n".join(out)
    print(text)
    if args.md:
        with open(args.md, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    sys.exit(0 if total_ok else 1)


if __name__ == "__main__":
    main()
