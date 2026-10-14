<script setup>
/**
 * 移动端「课程中心」：分类筛选 + 课程卡片列表
 * 复用 api/training.js 的 listCourses / courseCategories
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listCourses, courseCategories } from '../../api/training'

const router = useRouter()
const loading = ref(true)
const courses = ref([])
const categories = ref([])
const activeCat = ref('')

const LEVEL = { 1: '入门', 2: '进阶', 3: '高级' }

async function load() {
  loading.value = true
  try {
    const [cs, cats] = await Promise.all([
      listCourses(activeCat.value).then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => []),
      courseCategories().then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => [])
    ])
    courses.value = cs
    categories.value = cats
  } finally {
    loading.value = false
  }
}

function pickCat(c) { activeCat.value = (activeCat.value === c ? '' : c); load() }
function open(id) { router.push(`/course/${id}`) }

onMounted(load)
</script>

<template>
  <div class="courses">
    <div class="m-header">
      <div class="m-title">课程中心</div>
      <div class="m-sub">学知识、考能力</div>
    </div>

    <!-- 分类筛选 -->
    <div v-if="categories.length" class="cats">
      <button class="cat" :class="{ on: activeCat === '' }" @click="pickCat('')">全部</button>
      <button v-for="c in categories" :key="c" class="cat" :class="{ on: activeCat === c }" @click="pickCat(c)">{{ c }}</button>
    </div>

    <div class="body">
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px"><div class="m-skel" style="height:54px"></div></div>
      </div>

      <div v-else-if="courses.length" class="grid">
        <button v-for="c in courses" :key="c.id" class="course" type="button" @click="open(c.id)">
          <span class="cover" :style="c.cover ? { backgroundImage: `url(${c.cover})` } : {}">
            <el-icon v-if="!c.cover"><Notebook /></el-icon>
          </span>
          <span class="c-body">
            <span class="c-title">{{ c.title }}</span>
            <span class="c-desc">{{ c.summary || '—' }}</span>
            <span class="c-meta">
              <i class="lv" :class="'lv' + c.level">{{ LEVEL[c.level] || '入门' }}</i>
              <template v-if="c.ownerName"><i class="by">@{{ c.ownerName }}</i></template>
            </span>
          </span>
        </button>
      </div>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Notebook /></el-icon></span>
          <div>暂无课程</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.courses { min-height: 100%; }
.body { padding: 0 16px; }
.cats { display: flex; gap: 8px; overflow-x: auto; padding: 12px 16px 4px; }
.cats::-webkit-scrollbar { display: none; }
.cat {
  flex: none; border: none; background: var(--m-card-2); color: var(--m-text-sub);
  font-size: 13px; padding: 6px 14px; border-radius: 999px; cursor: pointer; white-space: nowrap;
}
.cat.on { background: var(--m-primary); color: #fff; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.course {
  border: none; background: var(--m-card); border-radius: var(--m-radius); overflow: hidden;
  display: flex; flex-direction: column; text-align: left; cursor: pointer; padding: 0;
  box-shadow: var(--m-shadow);
}
.course:active { transform: scale(.98); }
.cover {
  height: 92px; display: grid; place-items: center; color: #fff;
  background: linear-gradient(135deg, #4f86ff, #6366f1); font-size: 30px;
  background-size: cover; background-position: center;
}
.c-body { padding: 10px 12px 12px; display: flex; flex-direction: column; gap: 5px; }
.c-title { font-size: 14.5px; font-weight: 600; color: var(--m-text); line-height: 1.35;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.c-desc { font-size: 11.5px; color: var(--m-text-dim); line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.c-meta { display: flex; gap: 6px; margin-top: 2px; }
.lv { font-style: normal; font-size: 10.5px; padding: 1px 7px; border-radius: 999px; }
.lv1 { background: #e8f3ff; color: #1677ff; }
.lv2 { background: #fff3e8; color: #ff7d00; }
.lv3 { background: #fde8ef; color: #f53f3f; }
.by { font-style: normal; font-size: 10.5px; color: var(--m-text-dim); }
</style>
