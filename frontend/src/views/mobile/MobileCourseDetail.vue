<script setup>
/**
 * 移动端课程详情：课程头图 + 简介 + 章节列表（带完成进度）+ 开始/继续学习 + 结业证书
 * 复用 api/training.js 的 getCourse / courseProgress / courseCertificate
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCourse, courseProgress, courseCertificate } from '../../api/training'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const loading = ref(true)
const course = ref(null)
const chapters = ref([])
const done = ref([])          // 已完成的 chapterId 列表
const cert = ref(null)
const certVisible = ref(false)

const pct = computed(() => {
  if (!chapters.value.length) return 0
  const f = chapters.value.filter((c) => done.value.includes(c.id)).length
  return Math.round((f / chapters.value.length) * 100)
})
/** 已完成章节数：光看百分比看不出「还差几章」，这里直接给数量 */
const doneCount = computed(() => chapters.value.filter((c) => done.value.includes(c.id)).length)

async function load() {
  loading.value = true
  try {
    const [c, p] = await Promise.all([
      getCourse(id).then((r) => r.data || r).catch(() => null),
      courseProgress(id).then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => [])
    ])
    // 接口返回 { course: {...}, chapters: [...] }（嵌套）
    course.value = (c && c.course) || c
    chapters.value = (c && c.chapters) || []
    // ⚠️ 接口返回每章一条并带 finished 标记，只取 finished 的 chapterId，
    // 否则进度永远是 100%（旧代码就是这个 bug，导致「没学也显示学完」）
    done.value = p
      .filter((x) => (typeof x === 'object' ? x.finished !== false : true))
      .map((x) => (typeof x === 'object' ? x.chapterId : x))
      .filter(Boolean)
  } finally {
    loading.value = false
  }
}

function openChapter(chId) { router.push(`/course/${id}/chapter/${chId}`) }
function start() {
  if (!chapters.value.length) return
  const next = chapters.value.find((c) => !done.value.includes(c.id)) || chapters.value[0]
  openChapter(next.id)
}
async function showCert() {
  try { const r = await courseCertificate(id); cert.value = r.data || r } catch { cert.value = null }
  certVisible.value = true
}

onMounted(load)
</script>

<template>
  <div class="cd" v-if="course">
    <div class="hero" :style="course.cover ? { backgroundImage: `url(${course.cover})` } : {}">
      <button class="back" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="hero-mask">
        <div class="h-title">{{ course.title }}</div>
        <div class="h-meta">
          <i class="lv" :class="'lv' + course.level">{{ {1:'入门',2:'进阶',3:'高级'}[course.level] || '入门' }}</i>
          <span v-if="course.ownerName">@{{ course.ownerName }}</span>
        </div>
      </div>
    </div>

    <div class="body">
      <div class="m-card">
        <div class="summary">{{ course.summary || '该课程暂无简介' }}</div>
        <div class="progress">
          <div class="bar"><i :style="{ width: pct + '%' }"></i></div>
          <span class="ptxt">已学 {{ doneCount }}/{{ chapters.length }} 章 · {{ pct }}%</span>
        </div>
        <button class="m-btn" @click="start">{{ done.length ? '继续学习' : '开始学习' }}</button>
        <button v-if="pct === 100" class="m-btn ghost" style="margin-top:8px" @click="showCert">查看结业证书</button>
      </div>

      <div class="m-sec-title">章节（{{ chapters.length }}）</div>
      <div class="m-card">
        <button v-for="(ch, i) in chapters" :key="ch.id" class="m-row" type="button" @click="openChapter(ch.id)">
          <span class="m-ico" :class="done.includes(ch.id) ? 'green' : ''">
            <el-icon><component :is="done.includes(ch.id) ? 'CircleCheckFilled' : 'Document'" /></el-icon>
          </span>
          <span class="m-row-body">
            <span class="m-row-title">{{ i + 1 }}. {{ ch.title }}</span>
            <span class="m-row-desc" v-if="ch.durationMin">约 {{ ch.durationMin }} 分钟</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <div v-if="!chapters.length" class="m-empty"><div>该课程暂无章节</div></div>
      </div>
    </div>

    <div style="height:12px"></div>

    <div v-if="certVisible" class="mask" @click.self="certVisible = false">
      <div class="cert">
        <div class="cert-t">结业证书</div>
        <div class="cert-name">{{ cert?.userName || '学员' }}</div>
        <div class="cert-c">已完成《{{ course.title }}》全部章节</div>
        <div class="cert-no" v-if="cert?.certNo">证书编号 {{ cert.certNo }}</div>
        <button class="m-btn" @click="certVisible = false">关闭</button>
      </div>
    </div>
  </div>
  <div v-else class="loading">加载中…</div>
</template>

<style scoped>
.cd { min-height: 100%; background: var(--m-bg); }
.hero {
  position: relative; height: 168px; color: #fff; background: linear-gradient(135deg, #4f86ff, #6366f1);
  background-size: cover; background-position: center;
}
.hero-mask { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0,0,0,.05), rgba(0,0,0,.55));
  display: flex; flex-direction: column; justify-content: flex-end; padding: 16px; }
.back { position: absolute; top: 12px; left: 12px; width: 34px; height: 34px; border-radius: 50%;
  border: none; background: rgba(0,0,0,.3); color: #fff; display: grid; place-items: center; cursor: pointer; }
.h-title { font-size: 19px; font-weight: 700; }
.h-meta { margin-top: 6px; display: flex; gap: 8px; align-items: center; font-size: 12.5px; }
.lv { font-style: normal; padding: 1px 8px; border-radius: 999px; background: rgba(255,255,255,.22); }
.body { padding: 12px 16px 0; }
.summary { font-size: 13.5px; color: var(--m-text-sub); line-height: 1.6; }
.progress { display: flex; align-items: center; gap: 10px; margin: 12px 0; }
.progress .bar { flex: 1; height: 6px; border-radius: 999px; background: var(--m-border); overflow: hidden; }
.progress .bar i { display: block; height: 100%; background: var(--m-primary); border-radius: 999px; transition: width .3s; }
.ptxt { font-size: 12px; color: var(--m-text-dim); white-space: nowrap; }
.m-btn { width: 100%; }
.mask { position: fixed; inset: 0; z-index: 90; background: var(--m-mask); display: grid; place-items: center; padding: 24px; }
.cert { width: 100%; max-width: 300px; background: linear-gradient(160deg, #fff7e6, #ffe9c7); color: #7a4b12;
  border-radius: var(--m-radius-lg); padding: 24px 20px; text-align: center; box-shadow: 0 12px 32px rgba(0,0,0,.2); }
.cert-t { font-size: 18px; font-weight: 700; color: #b45309; }
.cert-name { font-size: 22px; font-weight: 700; margin: 12px 0 4px; }
.cert-c { font-size: 13px; }
.cert-no { font-size: 11px; color: #a86a2c; margin-top: 6px; }
.cert .m-btn { background: #b45309; color: #fff; margin-top: 16px; }
.loading { padding: 40px; text-align: center; color: var(--m-text-dim); }
</style>
