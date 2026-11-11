<script setup>
/**
 * 站点页脚：备案信息 + 自定义展示内容 + 版本
 *
 * 内容全部来自后台「页脚设置」（store/siteInfo），**默认空**：
 * 本地开发环境未配置时不渲染任何内容，线上由管理员填写后显示。
 * 备案号是固定项（链接与图标由代码生成，满足合规）；其余内容来自 items ——
 * 管理员可自由增删，填了链接就是可点链接，没填就只显示文字。
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
import { useDownloadStore } from '../store/download'

defineProps({
  /** 移动端紧凑样式：字号更小、无上边框 */
  compact: { type: Boolean, default: false },
  /** 无边框极简变体：登录页等自带分隔线的页面用（显式 prop 优于父级覆盖子组件样式） */
  plain: { type: Boolean, default: false },
  /**
   * 是否展示备案号。默认 true（网站底部必须展示，工信部要求）。
   * App 端传 false —— 那是**网站备案**，不是 App 备案，挂在 App 里既不合规也误导用户。
   */
  filing: { type: Boolean, default: true }
})

const siteInfo = useSiteInfoStore()
const download = useDownloadStore()

/** 链接协议白名单：服务端已校验，这里是渲染端最后一道防线（防历史脏数据 / 直改缓存） */
const safeHref = (u) => /^https?:\/\//i.test(String(u || '').trim())
</script>

<template>
  <footer v-if="siteInfo.visible" class="site-footer" :class="{ compact, plain }">
    <div v-if="filing && (siteInfo.icpNo || siteInfo.policeNo)" class="sf-line">
      <a v-if="siteInfo.icpNo" class="sf-link" :href="ICP_URL" target="_blank" rel="noopener noreferrer">{{ siteInfo.icpNo }}</a>
      <span v-if="siteInfo.icpNo && siteInfo.policeNo" class="sf-sep">|</span>
      <a v-if="siteInfo.policeNo" class="sf-link sf-police"
         :href="policeUrl(siteInfo.policeNo)" target="_blank" rel="noopener noreferrer">
        <img class="sf-police-ico" src="/police.png" alt="公安备案" />
        <span>{{ siteInfo.policeNo }}</span>
      </a>
    </div>

    <div v-if="download.visible" class="sf-line">
      <router-link class="sf-link" to="/download">客户端下载</router-link>
    </div>

    <div class="sf-line">
      <span>{{ APP_NAME }} v{{ APP_VERSION }}</span>
      <template v-for="(it, i) in siteInfo.items" :key="i">
        <span class="sf-sep">|</span>
        <a v-if="it.url && safeHref(it.url)" class="sf-link" :href="it.url" target="_blank" rel="noopener noreferrer">{{ it.name }}</a>
        <span v-else>{{ it.name }}</span>
      </template>
    </div>
  </footer>
</template>

<style scoped>
.site-footer {
  margin-top: 28px;
  padding: 16px 0 8px;
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

/* 无边框极简变体：登录页等自带分隔线的页面 */
.site-footer.plain {
  margin-top: 18px;
  padding: 0;
  border-top: none;
}

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
