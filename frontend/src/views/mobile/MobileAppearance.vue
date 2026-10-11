<script setup>
/**
 * 移动端「外观」设置
 *
 * 主题色（5 套）+ 正文字号（4 档）+ 深色模式。
 * 实现见 utils/mobileTheme.js（写 <html> 内联 CSS 变量，只影响移动端）。
 * 深色复用 utils/theme.js 的 applyTheme（与「我的」页的日/夜切换同一套）。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { THEMES, FONTS, getMobileTheme, setMobileTheme, resetMobileTheme } from '../../utils/mobileTheme'
import { applyTheme } from '../../utils/theme'

const router = useRouter()
const cur = ref(getMobileTheme())
const dark = ref(document.documentElement.classList.contains('dark'))

const fontPx = computed(() => (FONTS.find((f) => f.key === cur.value.font) || FONTS[1]).px)

function pickTheme(k) { cur.value = setMobileTheme({ theme: k }) }
function pickFont(k) { cur.value = setMobileTheme({ font: k }) }

function toggleDark() {
  dark.value = !dark.value
  applyTheme(dark.value)
}

function onReset() {
  resetMobileTheme()
  cur.value = getMobileTheme()
  window.__kbMsg?.success('已恢复默认外观')
}
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">外观</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <!-- 主题色 -->
      <div class="m-card sec">
        <div class="m-sec-title">主题色</div>
        <div class="themes">
          <button v-for="t in THEMES" :key="t.key" class="theme" type="button" @click="pickTheme(t.key)">
            <span class="swatch" :style="{ background: t.primary }">
              <el-icon v-if="cur.theme === t.key" :size="16"><Check /></el-icon>
            </span>
            <span class="t-name">{{ t.name }}</span>
          </button>
        </div>
      </div>

      <!-- 深色模式 -->
      <div class="m-card sec">
        <div class="m-sec-title">深色模式</div>
        <button class="m-row" type="button" @click="toggleDark">
          <span class="m-ico gray"><el-icon><component :is="dark ? 'Moon' : 'Sunny'" /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ dark ? '夜间' : '日间' }}</span>
            <span class="m-row-desc">与桌面端共用同一份设置</span>
          </span>
          <span class="sw" :class="{ on: dark }"><i></i></span>
        </button>
      </div>

      <!-- 正文字号 -->
      <div class="m-card sec">
        <div class="m-sec-title">正文字号</div>
        <div class="fonts">
          <button v-for="f in FONTS" :key="f.key" class="font" :class="{ on: cur.font === f.key }"
                  type="button" @click="pickFont(f.key)">{{ f.name }}</button>
        </div>
        <div class="preview" :style="{ fontSize: fontPx + 'px' }">
          知识库 · 正文预览。这是文档正文在阅读页的实际字号，可随时调整。
        </div>
      </div>

      <button class="reset" @click="onReset">恢复默认外观</button>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }
.sec { padding: 4px 14px 12px; }
.sec .m-sec-title { padding-left: 0; }

.themes { display: flex; gap: 6px; padding: 4px 0 2px; }
.theme { flex: 1 1 0; border: none; background: transparent; cursor: pointer; padding: 0; }
.swatch {
  display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%;
  margin: 0 auto; color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .12);
}
.t-name { display: block; margin-top: 6px; font-size: 11.5px; color: var(--m-text-dim); }

/* 开关 */
.sw {
  flex: none; width: 46px; height: 27px; border-radius: 14px; background: var(--m-border);
  position: relative; transition: background .2s;
}
.sw.on { background: var(--m-primary); }
.sw i {
  position: absolute; top: 3px; left: 3px; width: 21px; height: 21px; border-radius: 50%;
  background: #fff; transition: transform .2s; box-shadow: 0 1px 3px rgba(0, 0, 0, .2);
}
.sw.on i { transform: translateX(19px); }

.fonts { display: flex; gap: 8px; padding: 4px 0 10px; }
.font {
  flex: 1 1 0; height: 38px; border-radius: 10px; cursor: pointer;
  border: 1px solid var(--m-border); background: var(--m-card-2);
  color: var(--m-text-sub); font-size: 14px;
}
.font.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); font-weight: 600; }

.preview {
  line-height: 1.8; color: var(--m-text); background: var(--m-card-2);
  border-radius: 10px; padding: 12px 14px;
}

.reset {
  width: 100%; margin-top: 16px; height: 44px; border-radius: 12px;
  border: 1px solid var(--m-border); background: var(--m-card);
  color: var(--m-text-sub); font-size: 15px; cursor: pointer;
}
</style>
