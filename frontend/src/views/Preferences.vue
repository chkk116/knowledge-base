<template>
  <div class="pref-page">
    <div class="pf-hd">
      <div>
        <h1>偏好设置</h1>
        <p class="pf-sub">自定义界面偏好，改完即时生效，保存在本机浏览器</p>
      </div>
      <el-button text type="danger" @click="resetAll">恢复默认</el-button>
    </div>

    <el-tabs v-model="tab" class="pf-tabs">
      <!-- ============ 外观 ============ -->
      <el-tab-pane label="外观" name="look">
        <section class="pf-sec">
          <div class="sec-title">主题</div>
          <div class="theme-row">
            <button v-for="t in THEMES" :key="t.key" type="button" class="theme-card"
                    :class="{ on: p.theme === t.key }" @click="set({ theme: t.key })">
              <span class="theme-preview" :class="'tp-' + t.key" />
              <span class="theme-name">{{ t.name }}</span>
            </button>
          </div>
          <p class="sec-tip">跟随系统时，系统切换深浅色会自动跟着变。</p>
        </section>

        <section class="pf-sec">
          <div class="sec-title">强调色</div>
          <div class="accent-row">
            <button v-for="a in ACCENTS" :key="a.key" type="button" class="accent"
                    :class="{ on: p.accent === a.key }" :title="a.name"
                    :style="{ '--c': a.color }" @click="set({ accent: a.key })">
              <span class="accent-dot" />
              <span class="accent-name">{{ a.name }}</span>
            </button>
          </div>
          <p class="sec-tip">用于按钮、选中态、链接等主色，不影响各页面自己的强调配色。</p>
        </section>
      </el-tab-pane>

      <!-- ============ 布局 ============ -->
      <el-tab-pane label="布局" name="layout">
        <section class="pf-sec">
          <div class="sec-title">导航位置</div>
          <div class="layout-row">
            <button v-for="m in NAV_MODES" :key="m.key" type="button" class="layout-card"
                    :class="{ on: p.navMode === m.key }" @click="set({ navMode: m.key })">
              <span class="mini" :class="'mini-' + m.key">
                <i class="m-side" /><i class="m-top" /><i class="m-body" />
              </span>
              <span class="layout-name">{{ m.name }}</span>
            </button>
          </div>
          <p class="sec-tip">侧边导航：菜单全在左侧；混合双列：顶部放一级、左侧只放当前一级的二级项（图标 + 竖排文字）；顶部导航：全部横排。</p>
        </section>

        <section class="pf-sec">
          <div class="sec-title">内容宽度</div>
          <div class="layout-row">
            <button v-for="w in WIDTHS" :key="w.key" type="button" class="layout-card"
                    :class="{ on: p.contentWidth === w.key }" @click="set({ contentWidth: w.key })">
              <span class="mini" :class="'mini-w-' + w.key">
                <i class="m-top" /><i class="m-body" />
              </span>
              <span class="layout-name">{{ w.name }}</span>
            </button>
          </div>
          <p class="sec-tip">定宽会让内容居中留白，长文阅读更舒服；流式铺满整屏，表格更宽。</p>
        </section>

        <section class="pf-sec">
          <div class="sec-title">侧边栏</div>
          <div class="switch-row">
            <div class="sw-txt"><b>显示侧边栏</b><span>关掉后只留顶栏，需要时点左上角按钮临时唤出</span></div>
            <el-switch :model-value="p.sidebarVisible" :disabled="p.navMode === 'top'"
                       @change="(v) => set({ sidebarVisible: v })" />
          </div>
          <div class="switch-row">
            <div class="sw-txt"><b>深色菜单</b><span>侧边栏换成深色底，浅色页面里对比更明显</span></div>
            <el-switch :model-value="p.sidebarDark" :disabled="p.navMode === 'top'"
                       @change="(v) => set({ sidebarDark: v })" />
          </div>
          <div class="switch-row">
            <div class="sw-txt"><b>鼠标悬停展开</b><span>菜单收起后，鼠标移到侧边栏上临时展开，移开自动收起</span></div>
            <el-switch :model-value="p.hoverExpand" :disabled="p.navMode === 'top'"
                       @change="(v) => set({ hoverExpand: v })" />
          </div>
          <div class="switch-row">
            <div class="sw-txt"><b>内容密度</b><span>紧凑模式缩小页面内边距，一屏能看更多内容</span></div>
            <el-radio-group :model-value="p.density" size="small" @change="(v) => set({ density: v })">
              <el-radio-button value="normal">标准</el-radio-button>
              <el-radio-button value="compact">紧凑</el-radio-button>
            </el-radio-group>
          </div>
          <div class="switch-row">
            <div class="sw-txt"><b>菜单图标</b><span>双色几何（默认）或细线线框；线框覆盖高频项，其余沿用双色</span></div>
            <el-radio-group :model-value="p.menuIconStyle" size="small" @change="(v) => set({ menuIconStyle: v })">
              <el-radio-button value="duo">双色</el-radio-button>
              <el-radio-button value="line">线框</el-radio-button>
            </el-radio-group>
          </div>
        </section>

        <section class="pf-sec">
          <div class="sec-title">菜单排序</div>
          <div class="switch-row">
            <div class="sw-txt">
              <b>拖拽调整菜单顺序</b>
              <span>在侧边栏（或双列左栏）按住菜单项上下拖动，松手即保存；分组展开后里面的子项也能拖。只影响你自己的界面，新上线的菜单会自动排在最后</span>
            </div>
            <el-button size="small" plain :disabled="!hasCustomOrder" @click="resetMenuOrder">恢复默认顺序</el-button>
          </div>
        </section>
      </el-tab-pane>

      <!-- ============ 快捷键 ============ -->
      <el-tab-pane label="快捷键" name="keys">
        <section class="pf-sec">
          <div class="sec-title">全局快捷键</div>
          <div class="key-list">
            <div v-for="k in SHORTCUTS" :key="k.desc" class="key-row">
              <span class="key-desc">{{ k.desc }}</span>
              <span class="keys"><kbd v-for="c in k.keys" :key="c">{{ c }}</kbd></span>
            </div>
          </div>
          <p class="sec-tip">快捷键暂不支持自定义；有冲突或想加的场景可以直接说。</p>
        </section>
      </el-tab-pane>

      <!-- ============ 通用 ============ -->
      <el-tab-pane label="通用" name="general">
        <section class="pf-sec">
          <div class="sec-title">启动</div>
          <div class="switch-row">
            <div class="sw-txt"><b>登录后打开</b><span>每次进来先看哪一页</span></div>
            <el-radio-group :model-value="p.startPage" size="small" @change="(v) => set({ startPage: v })">
              <el-radio-button value="last">上次访问</el-radio-button>
              <el-radio-button value="home">首页</el-radio-button>
            </el-radio-group>
          </div>
        </section>
        <section class="pf-sec">
          <div class="sec-title">其他</div>
          <div class="switch-row">
            <div class="sw-txt"><b>恢复默认偏好</b><span>把布局、外观、启动页全部还原为初始状态（不影响账号与内容）</span></div>
            <el-button size="small" plain @click="resetAll">恢复默认</el-button>
          </div>
        </section>
      </el-tab-pane>

      <!-- ============ 开放接口 ============ -->
      <el-tab-pane label="开放接口" name="api">
        <section class="pf-sec">
          <div class="sec-title-row">
            <div class="sec-title">API 密钥</div>
            <el-button size="small" type="primary" plain @click="openKeyDialog">新建密钥</el-button>
          </div>
          <p class="sec-tip">
            给脚本或外部系统调用，按你账号的权限执行。调用时带请求头
            <code>X-API-Key: &lt;密钥&gt;</code>，例如：
          </p>
          <pre class="key-example">curl -H "X-API-Key: kb_xxx" {{ siteOrigin }}/api/docs/1</pre>
          <p class="sec-tip">
            密钥只显示一次，丢了只能重建；不要写进 URL。
            完整接口说明见
            <el-button link type="primary" size="small" @click="router.push('/developer')">API 接入指南 →</el-button>
          </p>

          <ul v-if="apiKeys.length" class="key-list">
            <li v-for="k in apiKeys" :key="k.id" :class="{ dead: k.revoked }">
              <div class="key-name">
                {{ k.name }}
                <el-tag v-if="k.revoked" size="small" type="info">已吊销</el-tag>
                <el-tag v-else-if="isExpired(k.expiresAt)" size="small" type="warning">已过期</el-tag>
              </div>
              <code class="key-prefix">{{ k.prefix }}…</code>
              <div class="key-meta">
                <span v-if="k.scopes">作用域：{{ k.scopes.split(',').map(permLabel).join('、') }}</span>
                <span v-else>作用域：与本账号权限相同</span>
                <span>最后使用：{{ k.lastUsedAt || '从未' }}</span>
                <span v-if="k.allowedIps">来源 IP：{{ k.allowedIps }}</span>
                <span v-else>来源 IP：不限</span>
                <span v-if="k.expiresAt">到期：{{ k.expiresAt }}</span>
              </div>
              <el-button v-if="!k.revoked" link type="danger" size="small" @click="doRevokeKey(k)">吊销</el-button>
            </li>
          </ul>
          <p v-else-if="!keyLoading" class="key-empty">还没有密钥。需要脚本定时拉数据时再建，平时不用开。</p>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="keyDialog" title="新建 API 密钥" width="520px" @closed="newKey = ''">
      <div v-if="!newKey">
        <el-form label-position="top">
          <el-form-item label="名称（用于区分用途，例如「每日数据同步」）">
            <el-input v-model="keyName" maxlength="40" show-word-limit placeholder="每日数据同步" />
          </el-form-item>
          <el-form-item label="有效期">
            <el-select v-model="keyDays" style="width: 100%">
              <el-option :value="7" label="7 天" />
              <el-option :value="30" label="30 天" />
              <el-option :value="90" label="90 天" />
              <el-option :value="365" label="1 年" />
            </el-select>
          </el-form-item>
          <el-form-item label="作用域（不选 = 与你账号权限相同）">
            <el-select v-model="keyScopes" multiple collapse-tags filterable style="width: 100%"
                       placeholder="全选等于不加限制">
              <el-option v-for="o in scopeOptions" :key="o.code" :value="o.code" :label="o.label" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-form-item label="来源 IP 白名单（可选，留空 = 不限）">
          <el-input v-model="keyIps" type="textarea" :rows="2"
                    placeholder="203.0.113.7, 10.0.0.0/8（支持 IPv4 网段，多个用逗号或空格分隔）" />
        </el-form-item>
        <p class="key-warn">权限只会比你的账号更小：勾了作用域后，密钥不能再做未勾选的写操作。
          填了 IP 白名单后，其他来源调用会直接 403。</p>
      </div>
      <div v-else>
        <p class="key-warn">这串密钥只显示这一次，关掉就找不回来了：</p>
        <div class="key-new">
          <code>{{ newKey }}</code>
          <el-button size="small" type="primary" @click="copyNewKey">复制</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="keyDialog = false">{{ newKey ? '完成' : '取消' }}</el-button>
        <el-button v-if="!newKey" type="primary" :loading="keyBusy" @click="submitKey">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 偏好设置（2026-10-22）
 * 外观 / 布局 / 快捷键 / 通用 / 开放接口五个分页；界面偏好写 store/prefs.js，watch 里即时 apply 到 DOM。
 * 「开放接口」（2026-11-28）：API 密钥管理，从个人资料页迁入——账号级开发配置不和个人成就混在一起。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePrefsStore, ACCENTS, DEFAULTS } from '../store/prefs'
import { useAuthStore, permLabel } from '../store/auth'
import { listApiKeys, createApiKey, revokeApiKey } from '../api/auth'
import { PERM_OPTIONS } from '../utils/permCodes'
import { errMsg } from '../utils/errMsg'

const prefs = usePrefsStore()
const p = computed(() => prefs.state)
const route = useRoute()
// 支持 /preferences?tab=api 直达「开放接口」（个人资料页的「去管理」链接用）
const tab = ref(route.query.tab === 'api' ? 'api' : 'layout')
const router = useRouter()
const auth = useAuthStore()
const hasCustomOrder = computed(() => (prefs.state.menuOrder || []).length > 0)
function resetMenuOrder() {
  prefs.set({ menuOrder: [] })
  ElMessage.success('已恢复默认菜单顺序')
}

// ---- API 密钥：列表只露前缀，明文只在创建那一刻显示一次 ----
// 2026-11-28 从「个人资料」迁到这里：密钥是账号级开发配置，和头像/简介混在一起不合语义。
const apiKeys = ref([])
const keyDialog = ref(false)
const keyName = ref('')
const keyDays = ref(90)
const keyScopes = ref([])
const keyIps = ref('')
const newKey = ref('')
const keyBusy = ref(false)
const keyLoading = ref(false)
const siteOrigin = computed(() => (typeof window === 'undefined' ? '' : window.location.origin))
// 可授予的作用域 = 当前账号已有的权限码（不给管理员「全权限」这种一步登天的选项）
const scopeOptions = computed(() => {
  const raw = auth.user?.permissions || ''
  // 管理员账号的 permissions 列被清空（权限靠角色隐式获得），这里给全量清单，
  // 否则管理员永远勾不出作用域——而「给管理员发一把只读密钥」正是这个功能的典型用法
  if (auth.isAdmin || raw === '*') {
    return PERM_OPTIONS.map((o) => ({ code: o.value, label: o.label }))
  }
  return raw.split(',').map((x) => x.trim()).filter(Boolean).map((code) => ({ code, label: permLabel(code) }))
})

async function loadKeys() {
  keyLoading.value = true
  try {
    const { data } = await listApiKeys()
    apiKeys.value = data || []
  } catch (e) {
    // 非关键区块：不打断偏好页，但要说一声——否则「接口挂了」会被读成「你还没有密钥」
    if (!apiKeys.value.length) ElMessage.warning(errMsg(e, '密钥列表加载失败'))
  } finally { keyLoading.value = false }
}

function openKeyDialog() {
  keyName.value = ''
  keyDays.value = 90
  keyScopes.value = []
  keyIps.value = ''      // 白名单也要清：否则上一把的规则会静默套到新密钥上
  newKey.value = ''
  keyDialog.value = true
}

async function submitKey() {
  if (!keyName.value.trim()) { ElMessage.warning('给密钥起个名字，方便日后分辨用途'); return }
  const ips = keyIps.value.split(/[\s,;，；、]+/).map((x) => x.trim()).filter(Boolean)
  if (ips.length > 20) { ElMessage.warning('IP 白名单最多 20 条'); return }
  keyBusy.value = true
  try {
    const { data } = await createApiKey({
      name: keyName.value.trim(),
      scopes: keyScopes.value,
      allowedIps: ips,
      expiresInDays: keyDays.value,
    })
    if (data && data.ok === false) { ElMessage.error(data.error || '创建失败'); return }
    newKey.value = data.key
    await loadKeys()
  } catch (e) {
    ElMessage.error(errMsg(e, '创建失败'))
  } finally { keyBusy.value = false }
}

/** 后端时间是 6 位微秒，直接 new Date() 在部分浏览器解析失败 → 「已过期」标签永远不显示 */
const isExpired = (t) => {
  if (!t) return false
  const m = String(t).match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/)
  if (!m) return false
  const d = new Date(`${m[1]}T${m[2]}`)
  return !Number.isNaN(d.getTime()) && d.getTime() < Date.now()
}

async function copyNewKey() {
  try {
    await navigator.clipboard.writeText(newKey.value)
    ElMessage.success('已复制')
  } catch { ElMessage.warning('复制失败，请手动选中复制') }
}

async function doRevokeKey(k) {
  try {
    await ElMessageBox.confirm(`吊销「${k.name}」后，用它调用的程序会立刻失效，且无法恢复。`, '吊销密钥', {
      type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    const { data } = await revokeApiKey(k.id)
    if (data && data.ok === false) { ElMessage.error(data.error || '吊销失败'); return }
    ElMessage.success('已吊销')
    await loadKeys()
  } catch (e) { ElMessage.error(errMsg(e, '吊销失败')) }
}

onMounted(loadKeys)

const THEMES = [
  { key: 'auto', name: '跟随系统' },
  { key: 'light', name: '浅色' },
  { key: 'dark', name: '深色' },
]
const NAV_MODES = [
  { key: 'side', name: '侧边导航' },
  { key: 'dual', name: '混合双列' },
  { key: 'top', name: '顶部导航' },
]
const WIDTHS = [
  { key: 'fluid', name: '流式' },
  { key: 'fixed', name: '定宽' },
]
const SHORTCUTS = [
  { desc: '打开命令面板（快速跳转页面 / 文档）', keys: ['Ctrl', 'K'] },
  { desc: '关闭弹窗 / 命令面板', keys: ['Esc'] },
  { desc: '全局搜索（顶栏输入框回车）', keys: ['Enter'] },
  { desc: '切换深色 / 浅色主题', keys: ['顶栏', '太阳/月亮'] },
]

function set(patch) {
  prefs.set(patch)
}

async function resetAll() {
  const ok = await ElMessageBox.confirm('把界面偏好恢复为默认？账号与内容不受影响。', '恢复默认', {
    type: 'warning', confirmButtonText: '恢复',
  }).then(() => true).catch(() => false)
  if (!ok) return
  prefs.reset()
  ElMessage.success('已恢复默认偏好')
}

// 供模板判断默认值（避免未使用告警）
void DEFAULTS
</script>

<style scoped>
.pref-page { max-width: 900px; }
.pf-hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.pf-hd h1 { margin: 0; font-size: 20px; color: var(--el-text-color-primary); }
.pf-sub { margin: 6px 0 0; font-size: 12.5px; color: var(--el-text-color-secondary); }
.pf-tabs { margin-top: 8px; }
.pf-sec { padding: 14px 0 18px; border-bottom: 1px solid var(--el-border-color-lighter); }
.pf-sec:last-child { border-bottom: none; }
.sec-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 12px; }
.sec-tip { margin: 10px 0 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }

/* 布局示意图 */
.layout-row { display: flex; gap: 12px; flex-wrap: wrap; }
.layout-card { width: 132px; padding: 8px 8px 6px; border: 1px solid var(--el-border-color); border-radius: 10px;
  background: var(--el-bg-color); cursor: pointer; font-family: inherit; transition: border-color .15s, box-shadow .15s; }
.layout-card:hover { border-color: var(--el-color-primary-light-5); }
.layout-card.on { border-color: var(--el-color-primary); box-shadow: 0 0 0 2px var(--el-color-primary-light-8); }
.mini { position: relative; display: block; height: 62px; border-radius: 6px; background: var(--el-fill-color-light); overflow: hidden; }
.mini i { position: absolute; display: block; border-radius: 2px; }
.mini .m-side { left: 0; top: 0; bottom: 0; width: 18px; background: var(--el-color-primary); opacity: .85; }
.mini .m-top { left: 0; right: 0; top: 0; height: 12px; background: var(--el-color-primary); opacity: .85; }
.mini .m-body { left: 26px; right: 8px; top: 20px; height: 30px; background: var(--el-fill-color-dark); }
.mini-side .m-top { display: none; }
/* 混合双列：顶部一级条 + 左侧图标条 + 二级列 */
.mini-dual .m-top { height: 9px; }
.mini-dual .m-side { width: 17px; top: 9px; }
.mini-dual .m-body { left: 24px; right: 6px; top: 15px; height: 32px; }
.mini-side .m-body { left: 26px; top: 12px; }
.mini-top .m-side { display: none; }
.mini-top .m-body { left: 8px; right: 8px; top: 20px; }
.mini-w-fixed .m-body { left: 28px; right: 28px; }
.mini-w-fluid .m-body { left: 6px; right: 6px; }
.layout-name { display: block; margin-top: 6px; font-size: 12px; color: var(--el-text-color-regular); text-align: center; }
.layout-card.on .layout-name { color: var(--el-color-primary); }

/* 主题卡 */
.theme-row { display: flex; gap: 12px; flex-wrap: wrap; }
.theme-card { width: 132px; padding: 8px 8px 6px; border: 1px solid var(--el-border-color); border-radius: 10px;
  background: var(--el-bg-color); cursor: pointer; font-family: inherit; }
.theme-card.on { border-color: var(--el-color-primary); box-shadow: 0 0 0 2px var(--el-color-primary-light-8); }
.theme-preview { display: block; height: 52px; border-radius: 6px; border: 1px solid var(--el-border-color-lighter); }
.tp-light { background: #ffffff; }
.tp-dark { background: #1b1b2e; }
.tp-auto { background: linear-gradient(90deg, #ffffff 0 50%, #1b1b2e 50% 100%); }
.theme-name { display: block; margin-top: 6px; font-size: 12px; color: var(--el-text-color-regular); text-align: center; }
.theme-card.on .theme-name { color: var(--el-color-primary); }

/* 强调色 */
.accent-row { display: flex; gap: 10px; flex-wrap: wrap; }
.accent { display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 8px 10px; border-radius: 10px;
  border: 1px solid transparent; background: transparent; cursor: pointer; font-family: inherit; }
.accent:hover { background: var(--el-fill-color-light); }
.accent.on { border-color: var(--el-border-color); background: var(--el-fill-color-light); }
.accent-dot { width: 26px; height: 26px; border-radius: 50%; background: var(--c); box-shadow: 0 1px 4px rgba(15, 23, 42, .18); }
.accent-name { font-size: 11.5px; color: var(--el-text-color-secondary); }

/* 开关行 */
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 9px 0; }
.sw-txt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.sw-txt b { font-size: 13.5px; font-weight: 500; color: var(--el-text-color-primary); }
.sw-txt span { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.5; }

/* 快捷键 */
.key-list { display: flex; flex-direction: column; }
.key-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter); font-size: 13px; color: var(--el-text-color-regular); }
.key-row:last-child { border-bottom: none; }
.keys { display: flex; gap: 4px; flex: none; }
kbd { min-width: 26px; padding: 2px 7px; border: 1px solid var(--el-border-color); border-bottom-width: 2px;
  border-radius: 6px; background: var(--el-fill-color-light); font-size: 11.5px; font-family: inherit;
  color: var(--el-text-color-regular); text-align: center; }

/* ---- 开放接口 / API 密钥 ---- */
.sec-title-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.sec-title-row .sec-title { margin-bottom: 0; }
.key-example {
  margin: 8px 0 10px; padding: 9px 12px; border-radius: 8px;
  background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter);
  font-size: 12.5px; color: var(--el-text-color-regular);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  overflow-x: auto;
}
.sec-tip code { background: var(--el-fill-color-light); padding: 1px 5px; border-radius: 4px;
  font-size: 11.5px; color: var(--el-text-color-regular); word-break: break-all; }
.key-list { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.key-list li { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 10px 12px;
  display: grid; grid-template-columns: 1fr auto; gap: 4px 10px; align-items: center; }
.key-list li.dead { opacity: .55; }
.key-name { font-size: 13.5px; font-weight: 600; color: var(--el-text-color-primary); display: flex; gap: 6px; align-items: center; }
.key-prefix { font-size: 12px; color: var(--el-text-color-regular); background: var(--el-fill-color-light);
  padding: 1px 6px; border-radius: 5px; justify-self: start; }
.key-meta { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 11.5px; color: var(--el-text-color-secondary); }
.key-empty { margin: 12px 0 0; font-size: 12.5px; color: var(--el-text-color-secondary); }
.key-new { display: flex; gap: 8px; align-items: center; background: var(--el-fill-color-light);
  border: 1px dashed var(--el-border-color); border-radius: 8px; padding: 10px 12px; }
.key-new code { flex: 1; word-break: break-all; font-size: 12.5px; color: var(--el-text-color-primary); }
.key-warn { margin: 0 0 8px; font-size: 12.5px; color: var(--el-color-warning-dark-2); }
</style>
