// unclip：用 clipper-lib 做多边形外扩（等价于 pyclipper 的 JT_ROUND / ET_CLOSEDPOLYGON）。
// 大坐标缩放以避免浮点精度问题（与常见 JS PaddleOCR 移植一致）。
import * as ClipperNS from "clipper-lib";
import { polyArea, polyPerimeter, type Pt } from "./geometry.js";

// clipper-lib 为 CJS：Node ESM 下命名导出挂在 .default，Vite 预打包下可能直接是命名空间。
const ClipperLib: any = (ClipperNS as any).default ?? ClipperNS;
const SCALE = 1000;

export function unclip(box: Pt[], ratio: number): Pt[] {
  const area = polyArea(box);
  const length = polyPerimeter(box);
  if (area <= 0 || length <= 0) return box;
  const distance = (area * ratio) / length;
  const path = box.map((p) => new ClipperLib.IntPoint(Math.round(p[0] * SCALE), Math.round(p[1] * SCALE)));
  const co = new ClipperLib.ClipperOffset();
  co.AddPath(path, ClipperLib.JoinType.jtRound, ClipperLib.EndType.etClosedPolygon);
  const solution: any = new ClipperLib.Paths();
  co.Execute(solution, Math.round(distance * SCALE));
  if (!solution || solution.length === 0) return box;
  return solution[0].map((p: any) => [p.X / SCALE, p.Y / SCALE] as Pt);
}
