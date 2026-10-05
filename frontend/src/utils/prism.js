/**
 * Prism 语法高亮的**唯一**入口（其它地方不要再 `import Prism from 'prismjs'`）
 *
 * 为什么需要这一层包装：
 *   prismjs 是 CJS 包，主文件末尾有 `if (typeof global !== 'undefined') global.Prism = Prism`。
 *   浏览器里没有 `global`，Vite 构建后这句不生效 —— 而各个语言组件
 *   （prism-javascript / prism-python …）内部是**直接读写全局 Prism** 的：
 *       Prism.languages.javascript = ...
 *   于是运行时抛 `ReferenceError: Prism is not defined`（报错落在 markdown chunk 里）。
 *
 * 解法：在本模块的**模块体**里把 Prism 挂到 window。
 *   ESM 的依赖是按 import 顺序求值的，所以调用方只要
 *       import Prism from '../utils/prism'          ← 先求值：挂好 window.Prism
 *       import 'prismjs/components/prism-javascript' ← 后求值：能拿到 window.Prism
 *   顺序就对了。反过来在同一个模块里写 `window.Prism = Prism` 是没用的：
 *   模块体在所有 import 求值完之后才执行，那时语言组件已经炸过了。
 */
import Prism from 'prismjs'

if (typeof window !== 'undefined') {
  window.Prism = Prism
} else if (typeof globalThis !== 'undefined') {
  globalThis.Prism = Prism
}

export default Prism
