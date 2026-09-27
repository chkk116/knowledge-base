/**
 * 局部加载 ECharts：仅统计页按需注入本地 vendor 脚本，不进主包、不依赖运行时 CDN。
 * Dashboard.vue 内有同款实现；此模块供其他页面复用（AI 用量统计等）。
 */
export function loadEcharts() {
  return new Promise((resolve, reject) => {
    if (window.echarts) return resolve(window.echarts)
    const s = document.createElement('script')
    s.src = import.meta.env.BASE_URL + 'vendor/echarts.min.js'
    s.async = true
    s.onload = () => (window.echarts ? resolve(window.echarts) : reject(new Error('echarts 未就绪')))
    s.onerror = () => reject(new Error('echarts 本地文件加载失败'))
    document.head.appendChild(s)
  })
}

/** 深浅主题下的通用文字 / 浮层配色 */
export const chartTxt = (dark) => ({ color: dark ? '#a0a4b2' : '#64748b' })
export const chartTip = (dark) => ({
  backgroundColor: dark ? '#23232e' : '#fff',
  borderColor: dark ? '#2a2a35' : '#e2e8f0',
  textStyle: { color: dark ? '#e7e7ee' : '#334155' },
})
