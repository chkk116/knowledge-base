/*
 * 首屏兜底：15 秒还没挂载出应用，就显示「点此刷新」。
 *
 * 为什么是独立文件而不是内联 <script>：
 * nginx 的 CSP 是 script-src 'self'（不含 'unsafe-inline'），内联脚本和
 * onclick="..." 这类内联事件处理器都会被浏览器直接拦掉——兜底就形同虚设
 * （元素永远拿不到 .is-timeout，慢网用户只能一直看着「加载中…」）。
 * 所以逻辑放这里，事件也在这里用 addEventListener 绑，保持 CSP 严格。
 */
(function () {
  var el = document.getElementById('boot-loading')
  if (!el) return
  setTimeout(function () {
    el.classList.add('is-timeout')
  }, 15000)
  el.addEventListener('click', function (e) {
    var t = e.target
    if (t && t.tagName === 'A' && t.closest && t.closest('.boot-fail')) {
      e.preventDefault()
      location.reload()
    }
  })
})()
