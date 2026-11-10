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
    </el-tabs>
  </div>
</template>

<script setup>
/**
 * 偏好设置（2026-10-22）
 * 布局 / 外观 / 快捷键 / 通用四个分页；所有改动写 store/prefs.js，watch 里即时 apply 到 DOM。
 */
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePrefsStore, ACCENTS, DEFAULTS } from '../store/prefs'

const prefs = usePrefsStore()
const p = computed(() => prefs.state)
const tab = ref('layout')
const hasCustomOrder = computed(() => (prefs.state.menuOrder || []).length > 0)
function resetMenuOrder() {
  prefs.set({ menuOrder: [] })
  ElMessage.success('已恢复默认菜单顺序')
}

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
</style>
