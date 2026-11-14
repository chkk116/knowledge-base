// 浏览器演示：加载两个引擎配置（全开 / 裸识别），上传或内置示例图，展示文本与检测框。
import { createWebOcrEngine, decodeWeb } from "../src/web.js";
import type { OcrResult } from "../src/types.js";

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const fileInput = $<HTMLInputElement>("file");
const runBtn = $<HTMLButtonElement>("run");
const sampleBtn = $<HTMLButtonElement>("sample");
const modeSel = $<HTMLSelectElement>("mode");
const status = $("status");
const bar = $("bar");
const barFill = $("barFill");
const out = $("out");
const preview = $<HTMLImageElement>("preview");
const overlay = $<HTMLCanvasElement>("overlay");
const ph = $("ph");
const imgbox = $("imgbox");

let fullEngine: Awaited<ReturnType<typeof createWebOcrEngine>> | null = null;
let bareEngine: Awaited<ReturnType<typeof createWebOcrEngine>> | null = null;
let currentBitmap: ImageBitmap | null = null;
let lastResult: OcrResult | null = null;

function setStatus(s: string, isErr = false) {
  status.textContent = s;
  status.className = isErr ? "err" : "";
}
function progress(stage: string, pct: number) {
  bar.style.display = "block";
  barFill.style.width = pct + "%";
  setStatus(stage === "就绪" ? "模型加载完成，可以开始识别" : `加载中：${stage}（${pct}%）`);
}

async function init() {
  try {
    const common = { modelBaseUrl: "/models/", wasmPaths: "/wasm/", onProgress: progress };
    fullEngine = await createWebOcrEngine({ ...common });
    bareEngine = await createWebOcrEngine({ ...common, columnAware: false, postprocess: false });
    runBtn.disabled = false;
    bar.style.display = "none";
    setStatus("模型加载完成，可以开始识别");
  } catch (e) {
    setStatus("加载失败：" + (e as Error).message, true);
    console.error(e);
  }
}

const stage = $("stage");

/** 让 overlay 始终与图片实际显示尺寸一致（响应式缩放后仍贴合）。 */
function syncOverlay() {
  if (!currentBitmap || stage.style.display === "none") return;
  const w = preview.clientWidth, h = preview.clientHeight;
  if (!w || !h) return;
  overlay.width = w;
  overlay.height = h;
  overlay.style.width = w + "px";
  overlay.style.height = h + "px";
  if (lastResult) drawBoxes(lastResult);
}

function showImage(bitmap: ImageBitmap) {
  currentBitmap = bitmap;
  lastResult = null;
  ph.style.display = "none";
  stage.style.display = "block";
  // 用 canvas 生成 dataURL 供 <img> 显示（createImageBitmap 不可直接作为 img.src）
  const c = document.createElement("canvas");
  c.width = bitmap.width; c.height = bitmap.height;
  c.getContext("2d")!.drawImage(bitmap, 0, 0);
  preview.src = c.toDataURL("image/png");
  preview.onload = syncOverlay;
  const ctx = overlay.getContext("2d");
  if (ctx) ctx.clearRect(0, 0, overlay.width, overlay.height);
}

window.addEventListener("resize", syncOverlay);

function drawBoxes(res: OcrResult) {
  if (!currentBitmap || !overlay.width) return;
  const ctx = overlay.getContext("2d")!;
  ctx.clearRect(0, 0, overlay.width, overlay.height);
  const sx = overlay.width / currentBitmap.width;
  const sy = overlay.height / currentBitmap.height;
  ctx.strokeStyle = "#1677ff";
  ctx.lineWidth = 2;
  ctx.font = "12px sans-serif";
  res.lines.forEach((l) => {
    if (!l.box || l.box.length < 4) return;
    ctx.beginPath();
    l.box.forEach((p, i) => i === 0 ? ctx.moveTo(p[0] * sx, p[1] * sy) : ctx.lineTo(p[0] * sx, p[1] * sy));
    ctx.closePath();
    ctx.stroke();
    ctx.fillStyle = "rgba(22,119,255,.85)";
    ctx.fillText(String(l.line_no), l.box[0][0] * sx + 2, l.box[0][1] * sy - 3);
  });
}

async function run() {
  if (!currentBitmap || !fullEngine || !bareEngine) return;
  runBtn.disabled = true;
  setStatus("识别中…");
  try {
    // 复用同一张图；decodeWeb 接受 ImageBitmap
    const eng = modeSel.value === "full" ? fullEngine : bareEngine;
    const res = await eng.recognize(currentBitmap);
    lastResult = res;
    out.textContent = res.text || "（未检测到文字）";
    $("mLines").textContent = String(res.lines.length);
    $("mMs").textContent = res.elapsed_ms.toFixed(1) + " ms";
    $("mEngine").textContent = res.engine;
    syncOverlay(); // 同时按当前显示尺寸绘制检测框
    setStatus(res.warnings.length ? res.warnings.join("；") : "识别完成");
  } catch (e) {
    setStatus("识别失败：" + (e as Error).message, true);
    console.error(e);
  } finally {
    runBtn.disabled = false;
  }
}

fileInput.addEventListener("change", async () => {
  const f = fileInput.files?.[0];
  if (!f) return;
  const bmp = await createImageBitmap(f);
  showImage(bmp);
  out.textContent = "";
  setStatus("已选择图片，点击「开始识别」");
});

sampleBtn.addEventListener("click", async () => {
  // 用 canvas 现场生成一张中文测试图（离线，不依赖外部资源）
  const c = document.createElement("canvas");
  c.width = 640; c.height = 200;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#ffffff"; ctx.fillRect(0, 0, c.width, c.height);
  ctx.fillStyle = "#1d2129";
  ctx.font = "bold 30px 'PingFang SC','Microsoft YaHei',sans-serif";
  ctx.fillText("春眠不觉晓，处处闻啼鸟。", 30, 70);
  ctx.fillText("订单编号 2026-11-14 金额 ¥1,299.00", 30, 130);
  const bmp = await createImageBitmap(c);
  showImage(bmp);
  out.textContent = "";
  setStatus("已生成内置示例，点击「开始识别」");
});

runBtn.addEventListener("click", run);
init();
