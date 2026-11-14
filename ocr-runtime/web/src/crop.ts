// 透视裁剪（复刻 RapidOCR.get_crop_img_list / get_rotate_crop_image）。
import type { BgrImage } from "./types.js";
import { getPerspectiveTransform, warpPerspective, rot90Bgr, type Pt } from "./geometry.js";

function dist(a: Pt, b: Pt): number {
  return Math.hypot(b[0] - a[0], b[1] - a[1]);
}

/** 把检测四边形的文字裁成一块正立矩形，过高/过窄时逆时针旋转 90°。 */
export function getRotateCropImage(src: BgrImage, points: number[][]): BgrImage {
  const pts: Pt[] = points.map((p) => [p[0], p[1]] as Pt);
  const cropW = Math.round(Math.max(dist(pts[0], pts[1]), dist(pts[2], pts[3])));
  const cropH = Math.round(Math.max(dist(pts[0], pts[3]), dist(pts[1], pts[2])));
  if (cropW <= 0 || cropH <= 0) return { bgr: new Uint8ClampedArray(0), width: 0, height: 0 };
  const dst: Pt[] = [[0, 0], [cropW, 0], [cropW, cropH], [0, cropH]];
  const H = getPerspectiveTransform(pts, dst);
  let bgr = warpPerspective(src.bgr, src.width, src.height, H, cropW, cropH);
  let w = cropW, h = cropH;
  if (h / w >= 1.5) {
    const r = rot90Bgr(bgr, w, h);
    bgr = r.bgr;
    w = r.w;
    h = r.h;
  }
  return { bgr, width: w, height: h };
}
