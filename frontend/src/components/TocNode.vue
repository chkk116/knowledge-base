<script setup>
// 文章目录的递归节点：大标题（h1/h2）可折叠其下的小标题（h2/h3）
import { ref } from 'vue'
const props = defineProps({
  node: { type: Object, required: true },
  active: { type: String, default: '' },
})
const emit = defineEmits(['go'])
// 用本地展开态，避免直接修改 prop（Vue 中改 prop 不可靠、部分构建下不触发刷新）
const open = ref(props.node.open !== false)
const hasChildren = !!(props.node.children && props.node.children.length)
function toggle() {
  open.value = !open.value
}
</script>

<template>
  <li class="toc-i" :class="['lv' + node.level, { on: active === node.id }]">
    <div class="toc-row" :class="{ collapsed: !open }" @click="emit('go', node.id)">
      <button
        v-if="hasChildren"
        type="button"
        class="tw"
        :class="{ open }"
        :title="open ? '折叠' : '展开'"
        @click.stop="toggle"
      >▸</button>
      <span v-else class="tw placeholder"></span>
      <span class="tt">{{ node.text }}</span>
    </div>
    <ul v-if="hasChildren && open" class="toc-sub">
      <TocNode
        v-for="c in node.children"
        :key="c.id"
        :node="c"
        :active="active"
        @go="emit('go', $event)"
      />
    </ul>
  </li>
</template>

<style scoped>
.toc-i { list-style: none; }
.toc-row {
  display: flex; align-items: center; gap: 4px; cursor: pointer;
  font-size: 13px; color: #475569; padding: 4px 0 4px 10px;
  border-left: 2px solid #eef2f7; transition: .15s;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.toc-row:hover { color: #6366f1; }
.toc-i.on > .toc-row { color: #4f46e5; border-left-color: #4f46e5; font-weight: 600; }
/* 折叠态：竖线变淡，提示其下还有内容 */
.toc-row.collapsed { border-left-color: #e2e8f0; }
.tw {
  flex: none; width: 15px; height: 15px; border: none; background: none; padding: 0;
  color: #94a3b8; cursor: pointer; font-size: 10px; line-height: 1;
  transition: transform .15s; transform: rotate(0deg); transform-origin: 50% 50%;
}
.tw.open { transform: rotate(90deg); }
.tw.placeholder { cursor: default; }
.tt { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.toc-sub { list-style: none; padding: 0; margin: 0; }
/* 子层级缩进 + 字号区分（h2~h6，层级越深缩进越多、字号越小） */
.toc-i.lv2 > .toc-row { padding-left: 20px; }
.toc-i.lv3 > .toc-row { padding-left: 32px; font-size: 12px; color: #64748b; }
.toc-i.lv3.on > .toc-row { color: #4f46e5; }
.toc-i.lv4 > .toc-row { padding-left: 44px; font-size: 12px; color: #94a3b8; }
.toc-i.lv5 > .toc-row { padding-left: 56px; font-size: 12px; color: #94a3b8; }
.toc-i.lv6 > .toc-row { padding-left: 68px; font-size: 12px; color: #94a3b8; }

/* 暗色适配 */
html.dark .toc-row { color: #c2c6d2; border-left-color: #2c2c36; }
html.dark .toc-row:hover { color: #a5b4fc; }
html.dark .toc-i.on > .toc-row { color: #a5b4fc; border-left-color: #a5b4fc; }
html.dark .tw { color: #7c8194; }
</style>
