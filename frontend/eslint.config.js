// ESLint 9 flat config：Vue 官方 essential 规则集，存量代码全部降级为 warn（不阻塞构建）。
// 新代码逐步收紧：把对应规则从 'warn' 升回 'error' 即可。
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/** 把一组规则全部降为 warn（保留规则参数） */
const warnOnly = (config) => ({
  ...config,
  rules: Object.fromEntries(
    Object.entries(config.rules || {}).map(([k, v]) => [
      k,
      Array.isArray(v) ? ['warn', ...v.slice(1)] : 'warn',
    ])
  ),
})

export default [
  { ignores: ['dist/**', 'node_modules/**', 'dist-electron/**', 'web-app/**'] },
  ...pluginVue.configs['flat/essential'].map(warnOnly),
  {
    languageOptions: {
      globals: { ...globals.browser },
    },
    rules: {
      // 常见噪音先 warn 化，不阻塞
      'no-unused-vars': 'warn',
      'no-console': 'off',
    },
  },
]
