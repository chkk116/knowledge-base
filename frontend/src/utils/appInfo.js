/**
 * 站点对外信息单一来源（页脚展示用）
 *
 * 备案、仓库、版权这类信息散落在多个页面很容易改漏，
 * 这里集中一处，各页脚组件统一引用。
 *
 * 关于备案（重要）：
 *   `湘ICP备2024087310号-2` 对应的备案网站域名就是 chkk116.cloud 本身
 *   （主办单位：周顺，审核通过日期 2026-02-27），并非"复用其他系统的备案"。
 *   公安备案 `湘公网安备 43040002000325` 同属该主体。
 *   ⚠️ 备案号与域名一一对应，**不可挪用到其它域名**。
 */

/** 产品名 */
export const APP_NAME = '知识库'

/** 版本号：构建期由 vite define 注入（来源 package.json 的 version） */
export const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev'

/** 版权方 */
export const COPYRIGHT = '© 2025 浅光雅致'

/** 工信部 ICP 备案（链接到工信部备案查询系统，官方要求必须可点击跳转） */
export const ICP_BEIAN = {
  no: '湘ICP备2024087310号-2',
  url: 'https://beian.miit.gov.cn/'
}

/** 公安联网备案（链接到公安部备案查询系统） */
export const POLICE_BEIAN = {
  no: '湘公网安备 43040002000325',
  code: '43040002000325',
  url: 'https://beian.mps.gov.cn/#/query/webSearch?code=43040002000325',
  icon: '/police.png'
}

/** 开源仓库 */
export const GITEE_URL = 'https://gitee.com/chkk116/knowledge-base'
