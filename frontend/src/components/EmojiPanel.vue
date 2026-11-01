<script setup>
import { ref } from 'vue'

/**
 * 表情面板（文章 / 评论 / 私信共用）
 *
 * 分类：
 *   - 贴吧：public/emojis 下的 50 张 image_emoticon*.png（历史资源）
 *   - 颜文字：纯文本
 *   - 笑脸/手势/动物/美食/活动/出行/物品/符号：Twemoji SVG（CC-BY 4.0，见 public/stickers/twemoji/LICENSE.txt）
 *
 * 对外事件：insert({ kind:'img', src, name }) 或 insert({ kind:'text', text })
 * 调用方按 kind 决定插入 <img> 还是文本；私信里用 [[e:name]] / [[t:code]] 令牌落库。
 */
const TIEBA = [
  'image_emoticon',
  ...Array.from({ length: 49 }, (_, i) => 'image_emoticon' + (i + 2)),
]

const KAOMOJI = [
  '(￣▽￣)~*', '(￣▽￣)', '(￣﹏￣)', '(⊙o⊙)', '(⊙_⊙)', '(≧▽≦)',
  '(╯‵□′)╯︵┻━┻', '(ノ￣▽￣)', '╮(╯▽╰)╭', '(；￣Д￣)', '(￣︶￣)',
  '(＠_＠)', '(°▽°)', '(｡･ω･｡)', '(╯_╰)', '(ノω<。)',
  'Orz', 'QAQ', 'o(╥﹏╥)o', '(●ˊωˋ●)', '(☆▽☆)', '(✿◡‿◕)',
  '(｀・ω・´)', '(╹◡╹)', '(◕‿◕)', '(/≧▽≦)/', '(ﾉ◕ヮ◕)ﾉ', 'ヽ(｡◕‿◕｡)ﾉ',
  '(；′⌒`)', '（￣□￣）', '(・∀・)',
]

// Twemoji 码点（文件名即码点，多码点用 - 连接）
const TWEMOJI = {
  smileys: '1f600 1f603 1f604 1f601 1f606 1f605 1f923 1f602 1f642 1f643 1f609 1f60a 1f60d 1f929 1f618 1f617 1f61b 1f61c 1f92a 1f911 1f917 1f92d 1f971 1f634'.split(' '),
  gestures: '1f44d 1f44e 1f44c 1f44f 1f64c 1f64f 1f450 1f91d 1f4aa 270c 1f918 1f919 1f91a 1f44b 1f44a 270b 1f596 1f440 1f4a5 1f64b'.split(' '),
  animals: '1f436 1f431 1f42d 1f439 1f430 1f42f 1f43b 1f43c 1f428 1f42e 1f437 1f438 1f414 1f427 1f426 1f986 1f985 1f989 1f984 1f98a'.split(' '),
  food: '1f34e 1f34f 1f350 1f351 1f352 1f353 1f354 1f355 1f35a 1f35b 1f35c 1f35d 1f363 1f369 1f36a 1f36b 1f36c 1f37a 1f37b 1f382'.split(' '),
  activity: '26bd 1f3c0 1f3c8 26be 1f3be 1f3d0 1f3b1 1f3ae 1f3b2 1f3b3 1f3af 1f3b4 1f3ad 1f3a8 1f3aa 1f3b5 1f3b6 1f3b7 1f3b8 1f3ba'.split(' '),
  travel: '1f697 1f695 1f699 1f68c 1f68e 1f6b2 2708 1f680 1f681 1f682 1f684 1f688 1f6a2 26f5 1f6a4 1f3d6 1f3dd 1f5fd 1f3f0 1f3d4'.split(' '),
  objects: '1f4a1 1f4bb 1f4f1 1f4f7 1f3a5 1f4fa 1f4fb 1f4de 1f4e0 1f4e1 1f50b 1f50c 1f4b0 1f4b3 1f4dd 1f4da 1f4d6 1f512 1f511 1f527'.split(' '),
  symbols: '2764 1f494 1f495 1f49e 1f498 1f49d 1f496 1f49b 1f49a 1f499 1f49c 1f49f 1f4af 1f4a2 1f4a4 1f4a3 1f4a8 1f4ab 1f4ac 1f525'.split(' '),
}

const TABS = [
  { key: 'tieba', label: '贴吧' },
  { key: 'kaomoji', label: '颜文字' },
  { key: 'smileys', label: '笑脸' },
  { key: 'gestures', label: '手势' },
  { key: 'animals', label: '动物' },
  { key: 'food', label: '美食' },
  { key: 'activity', label: '活动' },
  { key: 'travel', label: '出行' },
  { key: 'objects', label: '物品' },
  { key: 'symbols', label: '符号' },
]

const tab = ref('tieba')
const props = defineProps({ sheet: { type: Boolean, default: false } })
const emit = defineEmits(['insert'])
const tiebaSrc = (n) => '/emojis/' + n + '.png'
const twSrc = (c) => '/stickers/twemoji/' + c + '.svg'
</script>

<template>
  <div class="emoji-panel" :class="{ sheet: props.sheet }">
    <div class="tabs">
      <button v-for="t in TABS" :key="t.key" :class="{ on: tab === t.key }" type="button"
              @click="tab = t.key">{{ t.label }}</button>
    </div>

    <div class="body">
      <div v-if="tab === 'tieba'" class="grid img-grid">
        <button v-for="n in TIEBA" :key="n" class="cell" type="button"
                @click="emit('insert', { kind: 'img', name: n, src: tiebaSrc(n) })">
          <img :src="tiebaSrc(n)" alt="" loading="lazy" />
        </button>
      </div>

      <div v-else-if="tab === 'kaomoji'" class="grid k-grid">
        <button v-for="(t, i) in KAOMOJI" :key="i" class="cell k" type="button"
                @click="emit('insert', { kind: 'text', text: t })">{{ t }}</button>
      </div>

      <div v-else class="grid img-grid">
        <button v-for="c in TWEMOJI[tab]" :key="c" class="cell" type="button"
                @click="emit('insert', { kind: 'img', name: 'tw:' + c, src: twSrc(c) })">
          <img :src="twSrc(c)" alt="" loading="lazy" />
        </button>
      </div>
    </div>

    <div class="foot">Emoji artwork by Twemoji · CC-BY 4.0</div>
  </div>
</template>

<style scoped>
.emoji-panel {
  border: 1px solid #e5e7eb; border-radius: 12px; background: #fff;
  width: 328px; box-shadow: 0 10px 28px rgba(15, 23, 42, .12); overflow: hidden;
}
.tabs {
  display: flex; flex-wrap: wrap; gap: 2px; padding: 4px 6px 2px; border-bottom: 1px solid #f1f5f9;
}
.tabs button {
  flex: 0 0 auto; border: none; background: none; padding: 5px 7px; cursor: pointer;
  font-size: 12px; color: #64748b; border-radius: 7px; white-space: nowrap;
}
.tabs button:hover { color: #2563eb; background: #f8fafc; }
.tabs button.on { color: #2563eb; font-weight: 600; background: #eef2ff; }
.body { padding: 8px; height: 196px; overflow-y: auto; }
.grid { display: grid; gap: 2px; }
.img-grid { grid-template-columns: repeat(8, 1fr); }
.k-grid { grid-template-columns: repeat(3, 1fr); gap: 4px; }
.cell {
  border: 1px solid transparent; border-radius: 7px; background: none; cursor: pointer;
  padding: 2px; display: flex; align-items: center; justify-content: center; min-height: 36px;
  transition: background .12s, border-color .12s;
}
.cell:hover { background: #eef2ff; border-color: #dbe3ff; }
.img-grid .cell img { width: 28px; height: 28px; object-fit: contain; pointer-events: none; }
.k-grid .cell { font-size: 12px; color: #334155; padding: 4px 2px; line-height: 1.25; word-break: break-all; }
.k-grid .cell:hover { color: #2563eb; }
.foot { padding: 4px 8px 6px; font-size: 10.5px; color: #b6bdc9; text-align: right; }

/* 移动端底部弹层：占满宽度，网格更大更好点 */
.emoji-panel.sheet { width: 100%; border: 0; border-radius: 16px 16px 0 0; box-shadow: none; }
.emoji-panel.sheet .tabs { padding: 6px 10px 2px; }
.emoji-panel.sheet .tabs button { padding: 6px 9px; font-size: 12.5px; }
.emoji-panel.sheet .body { height: 248px; padding: 8px 10px; }
.emoji-panel.sheet .img-grid { grid-template-columns: repeat(8, 1fr); gap: 4px; }
.emoji-panel.sheet .img-grid .cell img { width: 32px; height: 32px; }
.emoji-panel.sheet .cell { min-height: 40px; }
.emoji-panel.sheet .foot { padding-bottom: calc(6px + env(safe-area-inset-bottom)); }

html.dark .emoji-panel { background: #1f1f2b; border-color: #2f2f3d; box-shadow: 0 10px 28px rgba(0,0,0,.5); }
html.dark .tabs { border-bottom-color: #2f2f3d; }
html.dark .tabs button { color: #9aa0ad; }
html.dark .tabs button:hover { background: #262633; color: #a9c2ff; }
html.dark .tabs button.on { color: #a9c2ff; background: #2a2a46; box-shadow: none; }
html.dark .cell:hover { background: #2a2a3a; border-color: #3a3a52; }
html.dark .k-grid .cell { color: #c2c6d2; }
html.dark .foot { color: #5b6070; }
</style>
