"""OCR 运行时引擎：统一 API 封装 RapidOCR(ONNX) + 版面重排 + 数字后处理。"""
import time
from typing import Union

import numpy as np

from .types import OcrLine, OcrResult
from .layout import column_aware_reorder
from .postprocess import fix_numbers


def _to_bgr(image):
    """把 路径/字节/数组 统一成 ONNX 推理用的 BGR numpy 数组。"""
    if isinstance(image, (bytes, bytearray)):
        import io
        from PIL import Image
        img = Image.open(io.BytesIO(image)).convert("RGB")
        return np.asarray(img)[:, :, ::-1].copy()
    arr = np.asarray(image)
    if arr.ndim == 3 and arr.shape[2] == 3:
        return arr[:, :, ::-1].copy()  # RGB -> BGR
    return arr


class OcrEngine:
    """跨端统一的离线 OCR 引擎。

    参数:
        lang:          识别语言（默认 ch 中文）
        device:        cpu / cuda
        use_angle_cls: 是否做文字方向分类
        column_aware:  是否按栏聚类重排阅读顺序（默认开，解决多栏错乱）
        postprocess:   是否做数字正则后处理（默认开）
        det_model/rec_model/cls_model: 自定义 ONNX 模型路径（默认用内置）
    """

    def __init__(self, lang="ch", device="cpu", use_angle_cls=True,
                 column_aware=True, postprocess=True, det_model=None,
                 rec_model=None, cls_model=None):
        self.lang = lang
        self.device = device
        self.use_angle_cls = use_angle_cls
        self.column_aware = column_aware
        self.postprocess = postprocess
        self._det, self._rec, self._cls = det_model, rec_model, cls_model
        self._engine = None

    def _lazy_load(self):
        if self._engine is not None:
            return
        from rapidocr_onnxruntime import RapidOCR
        kwargs = {}
        if self._det:
            kwargs["det_model_path"] = self._det
        if self._rec:
            kwargs["rec_model_path"] = self._rec
        if self._cls:
            kwargs["cls_model_path"] = self._cls
        # CPU 用默认即可；仅 cuda 时显式开启（带 model_path 才允许设 use_cuda）
        if self.device == "cuda":
            kwargs["det_use_cuda"] = True
            kwargs["rec_use_cuda"] = True
            kwargs["cls_use_cuda"] = True
        self._engine = RapidOCR(**kwargs)

    def recognize(self, image):
        """识别图像，返回统一 OcrResult。image 可为路径 / 字节 / numpy 数组。"""
        self._lazy_load()
        t0 = time.perf_counter()
        if isinstance(image, str):
            result, _ = self._engine(image)
        else:
            result, _ = self._engine(_to_bgr(image))
        elapsed = (time.perf_counter() - t0) * 1000

        items = [(box, text, score) for box, text, score in result] if result else []
        if self.column_aware:
            items = column_aware_reorder(items)

        lines = []
        text_parts = []
        for i, (box, text, score) in enumerate(items):
            if self.postprocess:
                text = fix_numbers(text)
            lines.append(OcrLine(text=text, box=box, score=float(score), line_no=i))
            text_parts.append(text)

        warnings = []
        if not lines:
            warnings.append("未检测到文字")

        return OcrResult(
            text="\n".join(text_parts),
            lines=lines,
            lang=self.lang,
            elapsed_ms=round(elapsed, 1),
            engine="rapidocr-onnx",
            warnings=warnings,
        )
