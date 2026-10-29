<!--
  考试与练习 · 统一子导航
  ------------------------------------------------------------------
  把原先散落的 7 个入口（在线考试 / 练习 / 错题本 / 我的成绩 / 答题闯关 /
  我的测验 / 刷题）收敛成一条 tab。各页面只需传 active 标明当前项。
  「答题闯关」已并入在线考试（同一套试卷与答题流程），不再单独出现；
  「我的测验」实为 AI 个人题库（非重复入口），更名「我的题库」保留。
-->
<template>
  <nav class="exam-tabs" aria-label="考试与练习导航">
    <button
      v-for="t in tabs"
      :key="t.key"
      type="button"
      class="et-item"
      :class="{ active: active === t.key }"
      @click="go(t)"
    >{{ t.label }}</button>
  </nav>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'

defineProps({ active: { type: String, default: '' } })

const route = useRoute()
const router = useRouter()

const tabs = [
  { key: 'exams', label: '在线考试', path: '/exams' },
  { key: 'practice', label: '练习', path: '/exams/practice' },
  { key: 'wrong', label: '错题本', path: '/exams/wrong' },
  { key: 'mine', label: '我的成绩', path: '/exams/mine' },
  { key: 'myquiz', label: '我的题库', path: '/my-quiz' },
]

function go(t) {
  if (route.path !== t.path) router.push(t.path)
}
</script>

<style scoped>
.exam-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  margin: 0 0 18px;
  background: #f1f5f9;
  border-radius: 12px;
  overflow-x: auto;
  scrollbar-width: none;
}
.exam-tabs::-webkit-scrollbar { display: none; }
.et-item {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: #475569;
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
  padding: 9px 16px;
  border-radius: 9px;
  cursor: pointer;
  white-space: nowrap;
  transition: background .16s, color .16s, box-shadow .16s;
}
.et-item:hover { color: #1d4ed8; }
.et-item.active {
  background: #fff;
  color: #2563eb;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .12);
}
:deep(html.dark) .exam-tabs { background: #23233a; }
:deep(html.dark) .et-item { color: #a5a7bd; }
:deep(html.dark) .et-item:hover { color: #93b4ff; }
:deep(html.dark) .et-item.active { background: #2f2f4a; color: #a9c2ff; box-shadow: none; }

@media (max-width: 640px) {
  .exam-tabs { margin-bottom: 14px; }
  .et-item { font-size: 13.5px; padding: 8px 13px; }
}
</style>
