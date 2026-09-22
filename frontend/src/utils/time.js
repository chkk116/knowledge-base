// 归一化输入：兼容 LocalDateTime 字符串 / Date 对象 / 毫秒时间戳（如浏览历史的 Date.now()），统一转成字符串
function toStr(v) {
  if (v === null || v === undefined || v === '') return ''
  if (v instanceof Date) v = v.getTime()
  if (typeof v === 'number') {
    const d = new Date(v)
    if (isNaN(d.getTime())) return ''
    const p = n => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return String(v)
}

// 把后端 LocalDateTime 字符串（如 2026-08-15T09:57:24.051307）格式化成友好的相对时间/日期
// 不再精确到秒和毫秒，避免卡片上出现一长串时间戳。
export function fromNow(iso) {
  iso = toStr(iso)
  if (!iso) return ''
  // 取前 19 位 yyyy-MM-ddTHH:mm:ss 作为本地时间解析（LocalDateTime 无时区，按本地处理即可）
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/.exec(iso)
  let date
  if (m) {
    date = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6])
  } else {
    date = new Date(iso)
  }
  if (isNaN(date.getTime())) return iso
  const diff = (Date.now() - date.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前'
  if (diff < 86400 * 30) return Math.floor(diff / 86400) + ' 天前'
  // 超过 30 天直接显示日期
  return iso.slice(0, 10)
}

// 格式化成「YYYY-MM-DD HH:mm」（去掉秒/毫秒），用于同时展示具体日期时间
export function fmtDateTime(iso) {
  iso = toStr(iso)
  if (!iso) return ''
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (m) return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}`
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

