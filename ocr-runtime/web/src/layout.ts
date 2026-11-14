// 版面阅读顺序修正（与 python/ocr_runtime/layout.py 行为一致）。
// 用 x 轴覆盖图找竖向间隙把文本框聚成栏，再按栏、栏内按 y 重排。
import type { RawLine } from "./shared.js";

function yCenter(box: number[][]): number {
  let s = 0;
  for (const p of box) s += p[1];
  return s / box.length;
}

export function columnAwareReorder(items: RawLine[]): RawLine[] {
  if (items.length < 2) return items;

  let maxX = 0;
  for (const it of items) for (const p of it.box) if (p[0] > maxX) maxX = p[0];
  if (maxX <= 0) return [...items].sort((a, b) => yCenter(a.box) - yCenter(b.box));

  const cov = new Uint8Array(Math.floor(maxX) + 1);
  for (const it of items) {
    const xs = it.box.map((p) => p[0]);
    const x0 = Math.max(0, Math.floor(Math.min(...xs)));
    const x1 = Math.min(cov.length - 1, Math.floor(Math.max(...xs)));
    for (let x = x0; x <= x1; x++) cov[x] = 1;
  }

  // 找出被覆盖的连续 x 段（各栏的 x 范围）
  const segs: [number, number][] = [];
  let inSeg = false, start = 0;
  for (let x = 0; x < cov.length; x++) {
    if (cov[x] && !inSeg) { inSeg = true; start = x; }
    else if (!cov[x] && inSeg) { inSeg = false; segs.push([start, x - 1]); }
  }
  if (inSeg) segs.push([start, cov.length - 1]);
  if (segs.length === 0) return [...items].sort((a, b) => yCenter(a.box) - yCenter(b.box));

  // 合并间隙过窄的相邻段（避免单栏内的小空隙被切成多栏）
  const minGap = Math.max(30, Math.floor(maxX * 0.03));
  const merged: [number, number][] = [segs[0].slice() as [number, number]];
  for (let i = 1; i < segs.length; i++) {
    const [a, b] = segs[i];
    if (a - merged[merged.length - 1][1] <= minGap) merged[merged.length - 1][1] = b;
    else merged.push([a, b]);
  }

  // 单栏（无显著竖向空隙）：保持原始检测顺序，避免打乱本就正确的单栏阅读顺序
  if (merged.length <= 1) return items;

  const colOf = (box: number[][]): number => {
    const xc = box.reduce((s, p) => s + p[0], 0) / box.length;
    for (let i = 0; i < merged.length; i++) if (xc >= merged[i][0] && xc <= merged[i][1]) return i;
    let best = 0, bd = Infinity;
    for (let i = 0; i < merged.length; i++) {
      const m = (merged[i][0] + merged[i][1]) / 2;
      const d = Math.abs(m - xc);
      if (d < bd) { bd = d; best = i; }
    }
    return best;
  };

  const grouped: RawLine[][] = merged.map(() => []);
  for (const it of items) grouped[colOf(it.box)].push(it);

  const out: RawLine[] = [];
  for (let i = 0; i < merged.length; i++) {
    out.push(...grouped[i].sort((a, b) => yCenter(a.box) - yCenter(b.box)));
  }
  return out;
}
