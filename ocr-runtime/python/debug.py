#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断：打印各配置对每类首图的真实输出。"""
import os, json
from collections import OrderedDict

GT = r"C:/chkk/知识库/outputs/ocr-bench/dataset/ground_truth.json"
with open(GT, encoding="utf-8") as f:
    gt = json.load(f)

from rapidocr_onnxruntime import RapidOCR
from ocr_runtime import OcrEngine

base = RapidOCR()
eng_layout = OcrEngine(column_aware=True, postprocess=False)
eng_post = OcrEngine(column_aware=False, postprocess=True)
eng_full = OcrEngine(column_aware=True, postprocess=True)

seen = set()
for g in gt:
    c = g["category"]
    if c in seen:
        continue
    seen.add(c)
    path = os.path.join(os.path.dirname(GT), g["file"])
    res, _ = base(path)
    b = "".join(t[1] for t in res) if res else ""
    l = eng_layout.recognize(path).text
    p = eng_post.recognize(path).text
    fu = eng_full.recognize(path).text
    print(f"\n===== [{c}] {g['file']} =====")
    print(f"REF : {g['text']}")
    print(f"BASE: {b}")
    print(f"LAY : {l}")
    print(f"POST: {p}")
    print(f"FULL: {fu}")
