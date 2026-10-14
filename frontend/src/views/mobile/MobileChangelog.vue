<script setup>
/**
 * 移动端「更新日志」：完整版本历史（关于页只展示本版本，详情走这里）
 */
import { useRouter } from 'vue-router'
import { CHANGELOG } from '../../changelog'

const router = useRouter()
const list = CHANGELOG
</script>

<template>
  <div class="cl">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">更新日志</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <div v-for="(c, i) in list" :key="c.version" class="item" :class="{ first: i === 0 }">
        <div class="dot-col">
          <span class="dot" :class="{ on: i === 0 }"></span>
          <span v-if="i < list.length - 1" class="line"></span>
        </div>
        <div class="content">
          <div class="head">
            <span class="ver">v{{ c.version }}</span>
            <span class="title">{{ c.title }}</span>
            <span class="date">{{ c.date }}</span>
          </div>
          <ul class="items">
            <li v-for="(it, j) in c.items" :key="j">{{ it }}</li>
          </ul>
        </div>
      </div>
    </div>
    <div style="height:20px"></div>
  </div>
</template>

<style scoped>
.cl { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 8px 16px 24px; }
.item { display: flex; gap: 10px; }
.dot-col { flex: none; display: flex; flex-direction: column; align-items: center; width: 14px; padding-top: 5px; }
.dot { width: 10px; height: 10px; border-radius: 50%; background: var(--m-border); flex: none; }
.dot.on { background: var(--m-primary); box-shadow: 0 0 0 3px var(--m-primary-soft); }
.line { flex: 1 1 auto; width: 2px; background: var(--m-border); margin: 4px 0; min-height: 20px; }
.content { flex: 1 1 auto; min-width: 0; padding-bottom: 18px; }
.head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.ver { font-size: 12px; font-weight: 700; color: var(--m-primary); background: var(--m-primary-soft); border-radius: 6px; padding: 2px 7px; }
.title { font-size: 14.5px; font-weight: 600; color: var(--m-text); }
.date { font-size: 12px; color: var(--m-text-dim); margin-left: auto; }
.items { margin: 8px 0 0; padding-left: 18px; }
.items li { font-size: 13px; line-height: 1.8; color: var(--m-text-sub); }
</style>
