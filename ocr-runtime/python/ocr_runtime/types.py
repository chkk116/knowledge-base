"""统一 OCR 结果类型定义（跨端契约的基础）。"""
from dataclasses import dataclass, field
from typing import List, Tuple


@dataclass
class OcrLine:
    """单行识别结果。"""
    text: str                                   # 该行文本
    box: List[List[float]] = field(default_factory=list)  # 4 角点 [[x1,y1],...]（图像像素坐标）
    score: float = 0.0                          # 识别置信度 0~1
    line_no: int = 0                            # 阅读顺序编号（重排后）


@dataclass
class OcrResult:
    """一次识别的完整结果。"""
    text: str                                   # 已按正确阅读顺序拼接的全文
    lines: List[OcrLine] = field(default_factory=list)
    lang: str = "ch"
    elapsed_ms: float = 0.0
    engine: str = "rapidocr-onnx"
    warnings: List[str] = field(default_factory=list)
