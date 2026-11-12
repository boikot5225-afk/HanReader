#!/usr/bin/env python3
"""Measure the segmenter against a reference segmentation (developer tool).

reference.jsonl: lines {"s": sentence, "t": [tokens]} (e.g. made by a model or by hand).
ours.txt:        the output of SegmentDumpTest for the same sentences, in the same order.
Prints word precision / recall / F1 over Han tokens, and the most frequent differences:
words we keep whole where the reference splits them, and the reverse.

Usage: python3 tools/seg_eval.py <reference.jsonl> <ours.txt> [--top 40]
"""
import collections
import json
import re
import sys

HAN = re.compile(r"^[㐀-鿿]+$")


def spans(tokens):
    out, i = set(), 0
    for t in tokens:
        if HAN.match(t):
            out.add((i, i + len(t), t))
        i += len(t)
    return out


def main(ref_path, ours_path, top):
    ref = [json.loads(l) for l in open(ref_path, encoding="utf-8")]
    ours = open(ours_path, encoding="utf-8").read().split("\n")
    tp = n_ref = n_ours = 0
    glued, split = collections.Counter(), collections.Counter()
    example = {}
    for r, o in zip(ref, ours):
        ot = [t for t in o.split(" ") if t]
        if "".join(ot) != r["s"].replace(" ", ""):
            continue
        a, b = spans(r["t"]), spans(ot)
        tp += len(a & b); n_ref += len(a); n_ours += len(b)
        for s in b - a:
            if len(s[2]) > 1:
                glued[s[2]] += 1; example.setdefault(("g", s[2]), r["s"])
        for s in a - b:
            if len(s[2]) > 1:
                split[s[2]] += 1; example.setdefault(("s", s[2]), r["s"])
    p, rc = tp / n_ours, tp / n_ref
    print(f"precision {p:.4f}  recall {rc:.4f}  F1 {2 * p * rc / (p + rc):.4f}  ({n_ref} reference words)")
    print("== kept whole, reference splits:")
    for w, c in glued.most_common(top):
        print(c, w, "|", example[("g", w)][:44])
    print("== split, reference keeps whole:")
    for w, c in split.most_common(top):
        print(c, w, "|", example[("s", w)][:44])


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], int(sys.argv[4]) if len(sys.argv) > 4 else 40)
