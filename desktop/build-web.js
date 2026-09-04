/**
 * 构建前端产物到 desktop/web（供 Electron 套壳加载）。
 * 直接复用前端已有的 Vite 构建，前端代码零改动；产物输出到本目录的 web/ 下。
 * 注意：outDir 在 frontend 根目录之外，Vite 出于安全默认不会清空它，
 * 因此不会触发任何删除操作（也规避了构建清旧目录被安全钩子拦截的问题）。
 */
const { spawn } = require('child_process')
const path = require('path')

const frontendDir = path.join(__dirname, '..', 'frontend')
const viteBin = path.join(frontendDir, 'node_modules', 'vite', 'bin', 'vite.js')
const outDir = path.join(__dirname, 'web')

const child = spawn(process.platform === 'win32' ? 'node.exe' : 'node',
  [viteBin, 'build', '--outDir', outDir],
  { cwd: frontendDir, stdio: 'inherit' }
)

child.on('exit', (code) => process.exit(code || 0))
