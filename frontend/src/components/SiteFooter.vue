<script setup>
/**
 * 站点页脚：备案信息 + 开源仓库 + 版权 + 版本
 *
 * 合规要点（别删）：
 *   - 工信部要求 ICP 备案号必须展示在网站底部，且**可点击跳转到 beian.miit.gov.cn**；
 *   - 公安联网备案号需链接到 beian.mps.gov.cn 的查询页，并按规范展示公安图标。
 *   因此下面的链接与图标不是装饰，改动前先确认合规性。
 *
 * 视觉：默认（桌面）单行、字号小、弱化；compact 模式给移动端用，居中堆叠。
 */
import {
  APP_NAME, APP_VERSION, COPYRIGHT,
  ICP_BEIAN, POLICE_BEIAN, GITEE_URL
} from '../utils/appInfo'

defineProps({
  /** 移动端紧凑样式：居中、两行、字号更小 */
  compact: { type: Boolean, default: false }
})
</script>

<template>
  <footer class="site-footer" :class="{ compact }">
    <div class="sf-line">
      <a class="sf-link" :href="ICP_BEIAN.url" target="_blank" rel="noopener noreferrer">{{ ICP_BEIAN.no }}</a>
      <span class="sf-sep">|</span>
      <a class="sf-link sf-police" :href="POLICE_BEIAN.url" target="_blank" rel="noopener noreferrer">
        <img class="sf-police-ico" :src="POLICE_BEIAN.icon" alt="公安备案" />
        <span>{{ POLICE_BEIAN.no }}</span>
      </a>
    </div>
    <div class="sf-line">
      <span>{{ APP_NAME }} v{{ APP_VERSION }}</span>
      <span class="sf-sep">|</span>
      <a class="sf-link" :href="GITEE_URL" target="_blank" rel="noopener noreferrer">Gitee 开源仓库</a>
      <span class="sf-sep">|</span>
      <span>{{ COPYRIGHT }}</span>
    </div>
  </footer>
</template>

<style scoped>
.site-footer {
  margin-top: 28px;
  padding: 16px 0 20px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--el-text-color-secondary, #909399);
  text-align: center;
}
.sf-line { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 6px; }
.sf-sep { color: var(--el-border-color, #dcdfe6); }
.sf-link {
  color: var(--el-text-color-secondary, #909399);
  text-decoration: none;
  transition: color .15s;
}
.sf-link:hover { color: var(--el-color-primary, #4f46e5); }
.sf-police { display: inline-flex; align-items: center; gap: 4px; }
.sf-police-ico { width: 15px; height: 16px; display: block; }

/* 移动端紧凑版：更小字号、贴底、无上边框（避免与 TabBar 视觉打架） */
.site-footer.compact {
  margin-top: 18px;
  padding: 0 0 4px;
  border-top: none;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--m-text-dim, #98a2b3);
}
.site-footer.compact .sf-link { color: var(--m-text-dim, #98a2b3); }
.site-footer.compact .sf-link:hover { color: var(--m-primary, #4f46e5); }
.site-footer.compact .sf-sep { color: var(--m-border, #eceef3); }
.site-footer.compact .sf-police-ico { width: 13px; height: 14px; }
</style>
