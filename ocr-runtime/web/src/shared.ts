// 中间结构：检测/识别之间的逐行结果（box 为 4 角点像素坐标）。
export interface RawLine {
  box: number[][]; // [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
  text: string;
  score: number;
}
