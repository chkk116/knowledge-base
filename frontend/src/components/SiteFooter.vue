<script setup>
/**
 * 站点页脚：备案信息 + 开源仓库 + 版权 + 版本
 *
 * 内容全部来自后台「页脚设置」（store/siteInfo），**默认空**：
 * 本地开发环境未配置时不渲染任何内容，线上由管理员填写后显示。
 * 版本号例外——由构建期从 package.json 注入（__APP_VERSION__），始终随代码走。
 *
 * 合规要点（别删）：
 *   - 工信部要求 ICP 备案号展示在网站底部，且**可点击跳转到 beian.miit.gov.cn**；
 *   - 公安联网备案号需链接到 beian.mps.gov.cn 查询页，并按规范展示公安图标。
 *
 * 视觉：默认（网页版）居中、字号小、弱化；compact 模式给移动端用。
 */
import { APP_NAME, APP_VERSION } from '../utils/appInfo'
import { useSiteInfoStore, ICP_URL, policeUrl } from '../store/siteInfo'

defineProps({
  /** 移动端紧凑样式：字号更小、无上边框 */
  compact: { type: Boolean, default: false }
})

const siteInfo = useSiteInfoStore()
</script>

<template>
  <footer v-if="siteInfo.visible" class="site-footer" :class="{ compact }">
    <div v-if="siteInfo.icpNo || siteInfo.policeNo" class="sf-line">
      <a v-if="siteInfo.icpNo" class="sf-link" :href="ICP_URL" target="_blank" rel="noopener noreferrer">{{ siteInfo.icpNo }}</a>
      <span v-if="siteInfo.icpNo && siteInfo.policeNo" class="sf-sep">|</span>
      <a v-if="siteInfo.policeNo" class="sf-link sf-police"
         :href="policeUrl(siteInfo.policeNo)" target="_blank" rel="noopener noreferrer">
        <img class="sf-police-ico" src="/police.png" alt="公安备案" />
        <span>{{ siteInfo.policeNo }}</span>
      </a>
    </div>

    <div class="sf-line">
      <span>{{ APP_NAME }} v{{ APP_VERSION }}</span>
      <template v-if="siteInfo.giteeUrl">
        <span class="sf-sep">|</span>
        <a class="sf-link" :href="siteInfo.giteeUrl" target="_blank" rel="noopener noreferrer">开源仓库</a>
      </template>
      <template v-if="siteInfo.copyright">
        <span class="sf-sep">|</span>
        <span>{{ siteInfo.copyright }}</span>
      </template>
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

/* 移动端紧凑版：更小字号、无上边框（避免与 TabBar 视觉打架） */
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
