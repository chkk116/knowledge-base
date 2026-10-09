import api from './request'

/**
 * App 版本检查（免登录）。
 *
 * 返回 { versionCode, versionName, url, notes, force }。
 * 后台没配 `app_version` 时 versionCode=0 —— 视为「未启用升级检查」，App 不应打扰用户。
 */
export const getAppVersion = () => api.get('/public/app-version')

/**
 * 前端热更包信息（免登录）。
 *
 * 返回 { version, url, checksum, notes }。
 * 后台没配 `app_bundle` 时 version 为空串 —— 视为「未启用热更」，直接用内置资源。
 */
export const getAppBundle = () => api.get('/public/app-bundle')
