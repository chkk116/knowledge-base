// 几何工具：检测后处理与透视裁剪所需（纯 TS，无外部依赖）。
// 复刻 RapidOCR(PaddleOCR) 的检测几何：凸包 + 旋转卡壳求最小外接矩形、
// 多边形面积/周长、点在多边形内、透视变换与双线性采样。

export type Pt = [number, number];

function cross(o: Pt, a: Pt, b: Pt): number {
  return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
}

/** Andrew monotone-chain 凸包（逆时针）。 */
export function convexHull(pts: Pt[]): Pt[] {
  const p = pts.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (p.length < 3) return p;
  const lower: Pt[] = [];
  for (const q of p) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], q) <= 0) lower.pop();
    lower.push(q);
  }
  const upper: Pt[] = [];
  for (let i = p.length - 1; i >= 0; i--) {
    const q = p[i];
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], q) <= 0) upper.pop();
    upper.push(q);
  }
  lower.pop();
  upper.pop();
  return lower.concat(upper);
}

export interface MinRect {
  cx: number;
  cy: number;
  w: number;
  h: number;
  corners: Pt[]; // [tl, tr, br, bl]
}

/** 旋转卡壳求点集最小面积外接矩形（等价于 cv2.minAreaRect + boxPoints 后按 x 排序）。 */
export function minAreaRect(pts: Pt[]): MinRect {
  const hull = convexHull(pts);
  const n = hull.length;
  if (n === 0) return { cx: 0, cy: 0, w: 0, h: 0, corners: [] };
  if (n === 1) return { cx: hull[0][0], cy: hull[0][1], w: 0, h: 0, corners: [hull[0], hull[0], hull[0], hull[0]] };
  if (n === 2) {
    const [a, b] = hull;
    const cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
    return { cx, cy, w: Math.hypot(b[0] - a[0], b[1] - a[1]), h: 0, corners: [a, b, b, a] };
  }
  let minArea = Infinity;
  let best: { u: Pt; v: Pt; minU: number; maxU: number; minV: number; maxV: number } | null = null;
  for (let i = 0; i < n; i++) {
    const p1 = hull[i], p2 = hull[(i + 1) % n];
    const dx = p2[0] - p1[0], dy = p2[1] - p1[1];
    const len = Math.hypot(dx, dy);
    if (len === 0) continue;
    const ux = dx / len, uy = dy / len; // 边方向
    const vx = -uy, vy = ux; // 垂直方向
    let minU = Infinity, maxU = -Infinity, minV = Infinity, maxV = -Infinity;
    for (const p of hull) {
      const pu = p[0] * ux + p[1] * uy;
      const pv = p[0] * vx + p[1] * vy;
      if (pu < minU) minU = pu; if (pu > maxU) maxU = pu;
      if (pv < minV) minV = pv; if (pv > maxV) maxV = pv;
    }
    const area = (maxU - minU) * (maxV - minV);
    if (area < minArea) {
      minArea = area;
      best = { u: [ux, uy], v: [vx, vy], minU, maxU, minV, maxV };
    }
  }
  if (!best) return { cx: 0, cy: 0, w: 0, h: 0, corners: [] };
  const centerU = (best.minU + best.maxU) / 2;
  const centerV = (best.minV + best.maxV) / 2;
  const cx = centerU * best.u[0] + centerV * best.v[0];
  const cy = centerU * best.u[1] + centerV * best.v[1];
  const hw = (best.maxU - best.minU) / 2;
  const hh = (best.maxV - best.minV) / 2;
  const corners: Pt[] = [
    [cx - hw * best.u[0] - hh * best.v[0], cy - hw * best.u[1] - hh * best.v[1]],
    [cx + hw * best.u[0] - hh * best.v[0], cy + hw * best.u[1] - hh * best.v[1]],
    [cx + hw * best.u[0] + hh * best.v[0], cy + hw * best.u[1] + hh * best.v[1]],
    [cx - hw * best.u[0] + hh * best.v[0], cy - hw * best.u[1] + hh * best.v[1]],
  ];
  return { cx, cy, w: best.maxU - best.minU, h: best.maxV - best.minV, corners };
}

/** 复刻 RapidOCR 的 get_mini_boxes：minAreaRect 后按 x 排序得到 [tl, tr, br, bl]。 */
export function getMiniBoxes(points: Pt[]): { box: Pt[]; sside: number } {
  const r = minAreaRect(points);
  const cps = r.corners;
  const sorted = cps.slice().sort((a, b) => a[0] - b[0]); // 按 x
  let i1: number, i4: number;
  if (sorted[1][1] > sorted[0][1]) { i1 = 0; i4 = 1; } else { i1 = 1; i4 = 0; }
  let i2: number, i3: number;
  if (sorted[3][1] > sorted[2][1]) { i2 = 2; i3 = 3; } else { i2 = 3; i3 = 2; }
  const box = [sorted[i1], sorted[i2], sorted[i3], sorted[i4]];
  return { box, sside: Math.min(r.w, r.h) };
}

/** 多边形面积（shoelace）。 */
export function polyArea(poly: Pt[]): number {
  let s = 0;
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length];
    s += a[0] * b[1] - b[0] * a[1];
  }
  return Math.abs(s) / 2;
}

/** 多边形周长。 */
export function polyPerimeter(poly: Pt[]): number {
  let s = 0;
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length];
    s += Math.hypot(b[0] - a[0], b[1] - a[1]);
  }
  return s;
}

/** 射线法判断点是否在多边形内。 */
export function pointInPoly(x: number, y: number, poly: Pt[]): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const xi = poly[i][0], yi = poly[i][1];
    const xj = poly[j][0], yj = poly[j][1];
    const intersect = (yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi;
    if (intersect) inside = !inside;
  }
  return inside;
}

/** 复刻 RapidOCR 的 order_points_clockwise：保证返回 [tl, tr, br, bl]。 */
export function orderPointsClockwise(box: Pt[]): Pt[] {
  const xs = box.slice().sort((a, b) => a[0] - b[0]);
  const left = xs.slice(0, 2).sort((a, b) => a[1] - b[1]);
  const right = xs.slice(2, 4).sort((a, b) => a[1] - b[1]);
  return [left[0], right[0], right[1], left[1]];
}

/** 解 4 点透视变换 H（src -> dst），返回 3x3 行主序矩阵。 */
export function getPerspectiveTransform(src: Pt[], dst: Pt[]): number[] {
  // DLT：8 个方程求解 H（齐次，h22=1）
  const A: number[][] = [];
  const b: number[] = [];
  for (let i = 0; i < 4; i++) {
    const [x, y] = src[i];
    const [u, v] = dst[i];
    A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]);
    b.push(u);
    A.push([0, 0, 0, x, y, 1, -v * x, -v * y]);
    b.push(v);
  }
  const h = solve8x8(A, b);
  return [h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1];
}

function solve8x8(A: number[][], b: number[]): number[] {
  // 高斯消元（部分主元）
  const n = 8;
  const M = A.map((row, i) => [...row, b[i]]);
  for (let col = 0; col < n; col++) {
    let piv = col;
    for (let r = col + 1; r < n; r++) if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
    [M[col], M[piv]] = [M[piv], M[col]];
    const d = M[col][col];
    if (Math.abs(d) < 1e-12) continue;
    for (let c = col; c <= n; c++) M[col][c] /= d;
    for (let r = 0; r < n; r++) {
      if (r === col) continue;
      const f = M[r][col];
      if (f === 0) continue;
      for (let c = col; c <= n; c++) M[r][c] -= f * M[col][c];
    }
  }
  return M.map((row) => row[n]);
}

/** 透视变换 + 双线性采样（BORDER_REPLICATE）。src 为 BGR 缓冲。 */
export function warpPerspective(
  src: Uint8ClampedArray, sw: number, sh: number,
  H: number[], dw: number, dh: number,
): Uint8ClampedArray {
  // 计算逆变换 H^{-1}（把目标像素映射回源坐标）
  const inv = invert3x3(H);
  const out = new Uint8ClampedArray(dw * dh * 3);
  for (let y = 0; y < dh; y++) {
    for (let x = 0; x < dw; x++) {
      const w = inv[6] * x + inv[7] * y + inv[8];
      const sx = (inv[0] * x + inv[1] * y + inv[2]) / w;
      const sy = (inv[3] * x + inv[4] * y + inv[5]) / w;
      // 边界复制
      const cx = Math.max(0, Math.min(sw - 1, sx));
      const cy = Math.max(0, Math.min(sh - 1, sy));
      const x0 = Math.floor(cx), y0 = Math.floor(cy);
      const x1 = Math.min(sw - 1, x0 + 1), y1 = Math.min(sh - 1, y0 + 1);
      const fx = cx - x0, fy = cy - y0;
      for (let c = 0; c < 3; c++) {
        const p00 = src[(y0 * sw + x0) * 3 + c];
        const p01 = src[(y0 * sw + x1) * 3 + c];
        const p10 = src[(y1 * sw + x0) * 3 + c];
        const p11 = src[(y1 * sw + x1) * 3 + c];
        const top = p00 + (p01 - p00) * fx;
        const bot = p10 + (p11 - p10) * fx;
        out[(y * dw + x) * 3 + c] = top + (bot - top) * fy;
      }
    }
  }
  return out;
}

function invert3x3(m: number[]): number[] {
  const [a, b, c, d, e, f, g, h, i] = m;
  const A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
  const det = a * A + b * B + c * C;
  const id = 1 / det;
  return [
    A * id, (c * h - b * i) * id, (b * f - c * e) * id,
    B * id, (a * i - c * g) * id, (c * d - a * f) * id,
    C * id, (b * g - a * h) * id, (a * e - b * d) * id,
  ];
}

/**
 * 连通分量标记（8 邻接）。mask 为 W*H 的 0/1 二值图，返回每个连通域的像素点集。
 * 对应 cv2.findContours 的外部轮廓所包围的区域（DB 后处理以连通域为检测框单位）。
 */
export function connectedComponents(mask: Uint8Array, W: number, H: number): Pt[][] {
  const visited = new Uint8Array(W * H);
  const comps: Pt[][] = [];
  const nb: [number, number][] = [
    [1, 0], [-1, 0], [0, 1], [0, -1],
    [1, 1], [1, -1], [-1, 1], [-1, -1],
  ];
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const idx = y * W + x;
      if (!mask[idx] || visited[idx]) continue;
      const comp: Pt[] = [];
      const stack: number[] = [idx];
      visited[idx] = 1;
      while (stack.length) {
        const p = stack.pop()!;
        const px = p % W, py = (p / W) | 0;
        comp.push([px, py]);
        for (const [dx, dy] of nb) {
          const nx = px + dx, ny = py + dy;
          if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
          const ni = ny * W + nx;
          if (mask[ni] && !visited[ni]) { visited[ni] = 1; stack.push(ni); }
        }
      }
      comps.push(comp);
    }
  }
  return comps;
}

/** 逆时针旋转 90°（等价于 numpy.rot90），输入/输出均为 BGR 缓冲，宽高互换。 */
export function rot90Bgr(src: Uint8ClampedArray, w: number, h: number): { bgr: Uint8ClampedArray; w: number; h: number } {
  const out = new Uint8ClampedArray(w * h * 3);
  // 输出形状 (w 行, h 列)：out[y2][x2] = src[x2][w-1-y2]
  for (let y2 = 0; y2 < w; y2++) {
    for (let x2 = 0; x2 < h; x2++) {
      const srcRow = x2, srcCol = w - 1 - y2;
      for (let c = 0; c < 3; c++) {
        out[(y2 * h + x2) * 3 + c] = src[(srcRow * w + srcCol) * 3 + c];
      }
    }
  }
  return { bgr: out, w: h, h: w };
}
