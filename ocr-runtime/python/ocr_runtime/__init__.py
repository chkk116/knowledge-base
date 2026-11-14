"""KB Unified OCR Runtime —— 跨端统一离线 OCR 运行时（Python 核心）。"""
from .types import OcrLine, OcrResult
from .engine import OcrEngine

__all__ = ["OcrEngine", "OcrResult", "OcrLine"]
__version__ = "0.1.0"
