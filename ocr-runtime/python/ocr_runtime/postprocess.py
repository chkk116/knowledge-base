"""数字后处理：修复中英文+数字混排时常见误读。

通用 OCR 在电话 / 金额 / 日期等数字串上容易把字母误认为数字（或反之），
例如把 0 读成 O、1 读成 l、5 读成 S。这里只在「明确应为数字」的
正则片段内做保守字符校正，绝不触碰普通中文/英文文本，避免误伤。
"""
import re

_CONFUSABLE = {
    "O": "0", "o": "0", "D": "0", "Q": "0",
    "l": "1", "I": "1", "i": "1", "|": "1",
    "Z": "2", "z": "2",
    "S": "5", "s": "5",
    "B": "8", "g": "8",
}

_PATTERNS = [
    re.compile(r"1[3-9]\d{1,2}[-\s]?\d{4}[-\s]?\d{4}"),
    re.compile(r"¥?\s?\d{1,3}(,\d{3})*(\.\d{1,2})?"),
    re.compile(r"\d{4}[-/年.]\d{1,2}[-/月.]\d{1,2}"),
    re.compile(r"(?<![A-Za-z])\d{6,}(?![A-Za-z])"),
    re.compile(r"\d{3,4}-\d{3,4}-\d{3,4}"),
]


def _fix_segment(seg):
    return "".join(_CONFUSABLE.get(ch, ch) for ch in seg)


def fix_numbers(text):
    if not text:
        return text
    out = text
    for pat in _PATTERNS:
        out = pat.sub(lambda m: _fix_segment(m.group(0)), out)
    return out
