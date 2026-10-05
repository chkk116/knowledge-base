/**
 * 站点基础信息（随代码走、与部署环境无关的部分）
 *
 * ⚠️ 备案号 / 开源仓库 / 版权**不在这里** —— 它们属于「某个部署环境」的信息，
 *    由后台「页脚设置」维护，见 store/siteInfo.js。这样仓库里不会残留任何
 *    特定环境的备案号，本地开发也天然是空的。
 */

/** 产品名 */
export const APP_NAME = '知识库'

/** 版本号：构建期由 vite define 注入（来源 package.json 的 version） */
export const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev'
