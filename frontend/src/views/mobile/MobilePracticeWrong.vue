<script setup>
/**
 * 移动端「错题本」（2026-10-21）
 * 练习流水派生：最近一次答错的题在这里，答对一次自动移出；也可手动「已掌握」。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { practiceWrongList, practiceWrongClear, practiceStats } from '../../api/training'

const router = useRouter()
const list = ref([])
const stats = ref({})
const loading = ref(true)
const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }

const strip = (html) => String(html || '').replace(/<[^>]+>/g, '').slice(0, 60)

async function load() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([
      practiceWrongList().catch(() => ({ data: [] })),
      practiceStats().catch(() => ({ data: {} })),
    ])
    list.value = a.data || []
    stats.value = b.data || {}
  } finally { loading.value = false }
}

async function markKnown(q) {
  try {
    await practiceWrongClear(q.id)
    list.value = list.value.filter((x) => x.id !== q.id)
    window.__kbMsg?.success('已移出错题本')
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="wr">
    <header class="pad">
      <button class="bk" type="button" aria-label="返回" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="tt">错题本</div>
    </header>

    <div class="stats">
      <div class="st"><b>{{ stats.wrong ?? list.length }}</b><span>待复习</span></div>
      <div class="st"><b>{{ stats.total ?? 0 }}</b><span>累计答题</span></div>
      <div class="st"><b>{{ stats.rate ?? 0 }}%</b><span>正确率</span></div>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="m-card">
      <div class="m-empty">
        <span class="ico"><el-icon :size="30"><CircleCheck /></el-icon></span>
        <div>还没有错题</div>
        <div class="es">去练几道题，答错的会自动出现在这里</div>
      </div>
      <button class="go" type="button" @click="router.push('/exams')">去练习</button>
    </div>

    <div v-else class="list">
      <div v-for="q in list" :key="q.id" class="card">
        <div class="c-hd">
          <span class="ty">{{ TYPE_CN[q.type] || '单选' }}</span>
          <span v-if="q.category" class="cat">{{ q.category }}</span>
          <button class="known" type="button" @click="markKnown(q)">已掌握</button>
        </div>
        <div class="c-q" v-html="q.content"></div>
        <div class="c-ans">
          <div class="line bad"><span class="lab">我的答案</span><span>{{ q.lastAnswer || '未作答' }}</span></div>
          <div class="line ok">
            <span class="lab">正确答案</span>
            <span class="inline">
              <span v-for="c in q.correctAnswer" :key="c.label"><b>{{ c.label }}</b> {{ strip(c.content) }}</span>
            </span>
          </div>
          <div v-if="q.analysis" class="line ana"><span class="lab">解析</span><span v-html="q.analysis"></span></div>
        </div>
      </div>
    </div>
    <div style="height:16px"></div>
  </div>
</template>

<style scoped>
.wr { min-height: 100%; background: var(--m-bg); }
.pad { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 8px; padding: 10px 12px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); display: grid; place-items: center; }
.tt { flex: 1; font-size: 19px; font-weight: 700; color: var(--m-text); }
.stats { display: flex; gap: 12px; margin: 12px 16px 10px; padding: 14px; background: var(--m-card);
  border-radius: 14px; border: 1px solid var(--m-border); }
.st { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.st b { font-size: 18px; color: var(--m-text); }
.st span { font-size: 11.5px; color: var(--m-text-dim); }
.tip { padding: 60px 32px; text-align: center; color: var(--m-text-dim); }
.es { font-size: 12px; color: var(--m-text-dim); margin-top: 4px; }
.go { width: calc(100% - 32px); margin: 0 16px 16px; height: 42px; border: none; border-radius: 12px;
  background: var(--m-primary); color: #fff; font-size: 14px; font-family: inherit; }
.list { padding: 0 16px; }
.card { background: var(--m-card); border: 1px solid var(--m-border); border-radius: 14px; padding: 14px; margin-bottom: 10px; }
.c-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ty { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: var(--m-card-2); color: var(--m-text-sub); }
.cat { font-size: 11.5px; color: var(--m-text-dim); }
.known { margin-left: auto; height: 26px; padding: 0 10px; border: 1px solid var(--m-border); border-radius: 8px;
  background: transparent; color: var(--m-primary); font-size: 12px; font-family: inherit; }
.c-q { font-size: 14.5px; line-height: 1.7; color: var(--m-text); }
.c-ans { margin-top: 10px; display: flex; flex-direction: column; gap: 7px; }
.line { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.6; }
.lab { flex: none; width: 58px; color: var(--m-text-dim); }
.line.bad span:last-child { color: var(--m-danger); }
.line.ok { color: var(--m-success); }
.inline { display: flex; flex-direction: column; gap: 3px; }
.line.ana { color: var(--m-text-sub); }
</style>
