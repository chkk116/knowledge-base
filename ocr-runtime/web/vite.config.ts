// Vite 配置：把 web/models 与 onnxruntime-web 的 .wasm 通过中间件/dev 与构建期拷贝对外提供，
// 避免在仓库里重复存放 13MB 模型 + 33MB wasm。纯静态、可离线运行。
import { defineConfig } from "vite";
import fs from "node:fs";
import path from "node:path";

const root = __dirname;
const assetDirs: Record<string, string> = {
  "/models/": path.join(root, "models"),
  "/wasm/": path.join(root, "node_modules", "onnxruntime-web", "dist"),
};

function guessType(file: string): string {
  if (file.endsWith(".wasm")) return "application/wasm";
  if (file.endsWith(".onnx")) return "application/octet-stream";
  if (file.endsWith(".txt")) return "text/plain; charset=utf-8";
  return "application/octet-stream";
}

function copyAssets(outDir: string) {
  for (const [prefix, dir] of Object.entries(assetDirs)) {
    const dest = path.join(outDir, prefix.slice(1));
    fs.mkdirSync(dest, { recursive: true });
    // 只拷贝实际需要的资源：模型 + 字符表 + wasm 二进制。
    // onnxruntime-web 的 .js/.mjs 打包器会自动打进 bundle，不需要（也不应）重复分发。
    const allow = (n: string) => n.endsWith(".onnx") || n.endsWith(".txt") || n.endsWith(".wasm");
    for (const name of fs.readdirSync(dir)) {
      const src = path.join(dir, name);
      if (!fs.statSync(src).isFile() || !allow(name)) continue;
      fs.copyFileSync(src, path.join(dest, name));
    }
  }
}

export default defineConfig({
  plugins: [
    {
      name: "kb-ocr-assets",
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          const url = (req.url || "").split("?")[0];
          for (const [prefix, dir] of Object.entries(assetDirs)) {
            if (url.startsWith(prefix)) {
              const file = path.join(dir, url.slice(prefix.length));
              if (fs.existsSync(file) && fs.statSync(file).isFile()) {
                res.setHeader("Content-Type", guessType(file));
                fs.createReadStream(file).pipe(res);
                return;
              }
            }
          }
          next();
        });
      },
      closeBundle() {
        copyAssets(path.join(root, "dist-demo"));
      },
    },
  ],
  build: { outDir: "dist-demo", emptyOutDir: true },
  server: { fs: { strict: false } },
});
