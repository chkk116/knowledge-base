<template>
  <el-dialog
    :model-value="visible"
    title="结业证书"
    width="680px"
    append-to-body
    @update:model-value="$emit('update:visible', $event)"
  >
    <div v-if="cert" class="cert-stage">
      <canvas ref="canvasEl" class="cert-canvas" :style="{ width: '100%', height: 'auto' }" />
      <p class="cert-tip">证书已生成，可点击下方「下载证书」保存为图片（PNG）。</p>
    </div>
    <template #footer>
      <el-button @click="$emit('update:visible', false)">关闭</el-button>
      <el-button type="primary" @click="download">下载证书</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'

const props = defineProps({
  visible: { type: Boolean, default: false },
  cert: { type: Object, default: null },
})
const emit = defineEmits(['update:visible'])

const auth = useAuthStore()
const canvasEl = ref(null)
const name = () => auth.user?.displayName || auth.user?.username || '学员'

const fmtDate = (s) => {
  if (!s) return '—'
  const d = new Date(String(s).replace('T', ' ').replace(/Z$/, ''))
  if (isNaN(d.getTime())) return String(s)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}年${p(d.getMonth() + 1)}月${p(d.getDate())}日`
}

// 折行绘制：超出画布宽度时逐级缩小字号
function drawWrapped(ctx, text, x, y, maxWidth, size, color, align = 'center') {
  ctx.fillStyle = color
  ctx.textAlign = align
  let s = size
  while (s > 18 && ctx.measureText(text).width > maxWidth) s -= 2
  ctx.font = `normal ${s}px "SimSun","KaiTi",serif`
  ctx.fillText(text, x, y)
  return s
}

function drawCert() {
  const canvas = canvasEl.value
  if (!canvas || !props.cert) return
  const ctx = canvas.getContext('2d')
  const W = 1200
  const H = 850
  canvas.width = W
  canvas.height = H
  const c = props.cert

  // 背景：米黄渐变
  const g = ctx.createLinearGradient(0, 0, 0, H)
  g.addColorStop(0, '#fdf9ee')
  g.addColorStop(1, '#f6ecd2')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, W, H)

  // 双线边框（金）
  ctx.strokeStyle = '#b08d3e'
  ctx.lineWidth = 10
  ctx.strokeRect(34, 34, W - 68, H - 68)
  ctx.lineWidth = 2
  ctx.strokeRect(52, 52, W - 104, H - 104)

  // 顶部标题
  ctx.fillStyle = '#8a6a1d'
  ctx.font = 'bold 92px "KaiTi","STKaiti","SimSun",serif'
  ctx.textAlign = 'center'
  ctx.fillText('结 业 证 书', W / 2, 150)

  // 分隔线
  ctx.strokeStyle = '#c9a94e'
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.moveTo(W / 2 - 180, 188)
  ctx.lineTo(W / 2 + 180, 188)
  ctx.stroke()

  // 正文
  ctx.textAlign = 'center'
  ctx.fillStyle = '#4a3a24'
  ctx.font = '30px "SimSun","KaiTi",serif'
  ctx.fillText('兹 证 明', W / 2, 258)

  // 学员姓名（大字）
  ctx.fillStyle = '#2d2018'
  ctx.font = 'bold 46px "KaiTi","SimSun",serif'
  ctx.fillText(name(), W / 2, 340)

  // 已完成课程
  drawWrapped(ctx, `已完成课程《${c.courseTitle}》全部章节学习，并通过结业考试`,
    W / 2, 420, W - 300, 30, '#4a3a24')

  // 成绩
  ctx.fillStyle = '#8a1f1f'
  ctx.font = 'bold 34px "SimSun","KaiTi",serif'
  ctx.fillText(`成绩 ${c.score} 分，成绩合格，特发此证。`, W / 2, 490)

  // 左：编号 / 日期
  ctx.textAlign = 'left'
  ctx.fillStyle = '#6b5a38'
  ctx.font = '26px "SimSun","KaiTi",serif'
  ctx.fillText(`证书编号：${c.certNo || '—'}`, 120, H - 140)
  ctx.fillText(`颁发日期：${fmtDate(c.issuedAt)}`, 120, H - 96)

  // 右：落款 + 印章
  ctx.textAlign = 'right'
  ctx.font = '28px "KaiTi","SimSun",serif'
  ctx.fillText('知识库平台', W - 130, H - 96)

  // 印章：红色双环 + 五角星 + 顶部弧形单位名
  // 修正：原实现把文字 rotate(-π/2) 后画在 (0,-r+18)，导致文字被旋转 90° 卡在印章左侧、压在圈内；
  // 现改为沿顶部弧线逐字排布（单位名），符合公章样式。
  const cx = W - 130
  const cy = H - 175
  const r = 64
  ctx.save()
  ctx.strokeStyle = 'rgba(190, 40, 40, .85)'
  ctx.fillStyle = 'rgba(190, 40, 40, .9)'
  ctx.lineWidth = 5
  ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke()
  ctx.lineWidth = 1.5
  ctx.beginPath(); ctx.arc(cx, cy, r - 11, 0, Math.PI * 2); ctx.stroke()

  // 顶部弧形文字（沿上弧排布，以正上方为中心向两侧展开）
  ctx.font = 'bold 19px "SimSun","KaiTi",serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  const sealText = '知识库平台'
  const sn = sealText.length
  const span = Math.PI * 0.8          // 弧线总跨度（约 144°）
  const start = -Math.PI / 2 - span / 2
  for (let i = 0; i < sn; i++) {
    const ang = start + (span / (sn - 1)) * i
    ctx.save()
    ctx.translate(cx, cy)
    ctx.rotate(ang)
    ctx.fillText(sealText[i], 0, -(r - 22))
    ctx.restore()
  }

  // 五角星（中心）
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = '34px sans-serif'
  ctx.fillText('★', cx, cy + 1)
  ctx.restore()
}

function download() {
  const canvas = canvasEl.value
  if (!canvas) return
  try {
    const url = canvas.toDataURL('image/png')
    const a = document.createElement('a')
    a.href = url
    a.download = 'cert-' + (props.cert?.certNo || 'certificate') + '.png'
    document.body.appendChild(a)
    a.click()
    a.remove()
    ElMessage.success('证书已下载')
  } catch (e) {
    ElMessage.error('下载失败，请重试')
  }
}

watch(() => props.visible, (v) => {
  if (v) nextTick(drawCert)
})
watch(() => props.cert, () => {
  if (props.visible) nextTick(drawCert)
})
</script>

<style scoped>
.cert-stage { padding: 4px 0 2px; }
.cert-canvas { display: block; border-radius: 8px; box-shadow: 0 2px 14px rgba(139, 105, 20, .18); }
.cert-tip { margin: 10px 0 0; font-size: 12.5px; color: #94a3b8; }

/* 暗色模式适配 */
html.dark .cert-tip { color: var(--el-text-color-secondary); }
html.dark .cert-canvas { box-shadow: 0 2px 14px rgba(0, 0, 0, .45); }
</style>
