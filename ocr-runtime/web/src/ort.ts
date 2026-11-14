// ONNX Runtime 会话的最小类型抽象（onnxruntime-web 在 Node 与浏览器下共用同一 API）。
import { Tensor } from "onnxruntime-web";

export type OrtTensor = Tensor;
export interface OrtSession {
  run(feeds: Record<string, Tensor>): Promise<Record<string, OrtTensor>>;
  inputNames: string[];
  outputNames: string[];
}
