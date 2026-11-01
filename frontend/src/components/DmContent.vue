<script setup>
import { computed } from 'vue'

/**
 * 私信正文渲染：把 [[e:name]] / [[t:code]] 令牌还原成表情图，其余按纯文本显示。
 * 不用 v-html：令牌白名单校验，避免历史消息里的 HTML 被当作标签执行。
 */
const props = defineProps({ text: { type: String, default: '' } })

const parts = computed(() => {
  const s = props.text || ''
  const re = /\[\[(e|t):([A-Za-z0-9_-]+)\]\]/g
  const out = []
  let last = 0
  let m
  while ((m = re.exec(s)) !== null) {
    if (m.index > last) out.push({ type: 'text', v: s.slice(last, m.index) })
    const kind = m[1]
    const id = m[2]
    out.push({
      type: 'img',
      src: kind === 'e' ? '/emojis/' + id + '.png' : '/stickers/twemoji/' + id + '.svg',
      alt: '表情',
    })
    last = m.index + m[0].length
  }
  if (last < s.length) out.push({ type: 'text', v: s.slice(last) })
  return out
})
</script>

<template>
  <span class="dm-content"><template v-for="(p, i) in parts" :key="i"><img
    v-if="p.type === 'img'" :src="p.src" :alt="p.alt" class="dm-emoji" /><template v-else>{{ p.v }}</template></template></span>
</template>

<style scoped>
.dm-content { white-space: pre-wrap; word-break: break-word; }
.dm-emoji { width: 22px; height: 22px; vertical-align: -5px; margin: 0 1px; object-fit: contain; }
</style>
