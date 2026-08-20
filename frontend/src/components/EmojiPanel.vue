<script setup>
import { ref } from 'vue'

// 表情图：贴吧 APK 提取的原版 image_emoticon 系列，已下载到 public/emojis/
// 注意：CDN 仓库 microlong666/tieba_mobile_emotions 只提供 image_emoticon ~ image_emoticon50（共 50 张）
// 51~60 在原下载时为 404 错误页（非图片），已弃用，切勿再引用
const imgs = [
  'image_emoticon',
  ...Array.from({ length: 49 }, (_, i) => 'image_emoticon' + (i + 2)),
]
// 经典颜文字（纯文本，直接插入）
const kaomoji = [
  '(￣▽￣)~*', '(￣▽￣)', '(￣﹏￣)', '(⊙o⊙)', '(⊙_⊙)', '(≧▽≦)',
  '(╯‵□′)╯︵┻━┻', '(ノ￣▽￣)', '╮(╯▽╰)╭', '(；￣Д￣)', '(￣︶￣)',
  '(＠_＠)', '(°▽°)', '(｡･ω･｡)', '(╯_╰)', '(ノω<。)',
  'Orz', 'QAQ', 'o(╥﹏╥)o', '(●ˊωˋ●)', '(☆▽☆)', '(✿◡‿◕)',
  '(｀・ω・´)', '(╹◡╹)', '(◕‿◕)', '(/≧▽≦)/', '(ﾉ◕ヮ◕)ﾉ', 'ヽ(｡◕‿◕｡)ﾉ',
  '(；′⌒`)', '（￣□￣）', '(・∀・)',
]

const tab = ref('img')
const emit = defineEmits(['insert'])
const pickImg = (name) => emit('insert', { kind: 'img', name })
const pickK = (t) => emit('insert', { kind: 'text', text: t })
</script>

<template>
  <div class="emoji-panel">
    <div class="tabs">
      <button :class="{ on: tab === 'img' }" @click="tab = 'img'">表情</button>
      <button :class="{ on: tab === 'kaomoji' }" @click="tab = 'kaomoji'">颜文字</button>
    </div>
    <div class="body">
      <div v-if="tab === 'img'" class="grid img-grid">
        <button v-for="n in imgs" :key="n" class="cell" @click="pickImg(n)">
          <img :src="'/emojis/' + n + '.png'" alt="" loading="lazy" />
        </button>
      </div>
      <div v-else class="grid k-grid">
        <button v-for="(t, i) in kaomoji" :key="i" class="cell k" @click="pickK(t)">{{ t }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.emoji-panel {
  border: 1px solid #e5e7eb; border-radius: 8px; background: #fff;
  width: 284px; box-shadow: 0 6px 18px rgba(0, 0, 0, 0.08); overflow: hidden;
}
.tabs { display: flex; border-bottom: 1px solid #f1f5f9; }
.tabs button {
  flex: 1; border: none; background: none; padding: 6px 0; cursor: pointer;
  font-size: 12px; color: #64748b;
}
.tabs button.on { color: #4f46e5; font-weight: 600; box-shadow: inset 0 -2px 0 #4f46e5; }
.body { padding: 6px; max-height: 190px; overflow: auto; }
.grid { display: grid; gap: 4px; }
.img-grid { grid-template-columns: repeat(7, 1fr); }
.k-grid { grid-template-columns: repeat(3, 1fr); }
.cell {
  border: 1px solid transparent; border-radius: 5px; background: none; cursor: pointer;
  padding: 3px; display: flex; align-items: center; justify-content: center; min-height: 38px;
  transition: .12s;
}
.cell:hover { border-color: #c7d2fe; background: #eef2ff; }
.img-grid .cell img { width: 32px; height: 32px; object-fit: contain; pointer-events: none; }
.k-grid .cell { font-size: 12px; color: #334155; padding: 3px 2px; line-height: 1.2; }
.k-grid .cell:hover { color: #4f46e5; }

/* 暗色适配 */
html.dark .emoji-panel { background: #1f1f27; border-color: #2c2c36; }
html.dark .tabs { border-bottom-color: #2c2c36; }
html.dark .tabs button { color: #9aa0ad; }
html.dark .tabs button.on { color: #a5b4fc; box-shadow: inset 0 -2px 0 #a5b4fc; }
html.dark .cell:hover { background: #2d2d3a; border-color: #4f46e5; }
html.dark .k-grid .cell { color: #c2c6d2; }
</style>
