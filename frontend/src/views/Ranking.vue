<script setup>
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '../store/auth'
import { ranking } from '../api/auth'
import { getLevel, LEVELS } from '../utils/level'

const auth = useAuthStore()
const top = ref([])
const me = ref({ rank: 0, points: 0, username: '' })
const total = ref(0)
const loading = ref(false)

const rankClass = (i) => (i === 0 ? 'gold' : i === 1 ? 'silver' : i === 2 ? 'bronze' : '')
const medal = (i) => (i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : (i + 1))

onMounted(load)
async function load() {
  loading.value = true
  try {
    const { data } = await ranking()
    top.value = Array.isArray(data.top) ? data.top : []
    me.value = data.me || { rank: 0, points: 0, username: auth.user?.username }
    total.value = data.total || 0
  } catch (e) {
    top.value = []
  } finally {
    loading.value = false
  }
}

const myLevel = computed(() => getLevel(auth.user?.points || 0))
</script>

<template>
  <div class="rank-page">
    <div class="head">
      <div class="page-title">🏆 积分排行榜</div>
      <!-- 小圆圈：点击查看积分规则与等级详情 -->
      <el-popover placement="bottom-start" :width="320" trigger="click">
        <template #reference>
          <button class="info-circle" type="button" title="查看积分规则与等级">i</button>
        </template>
        <div class="pop">
          <div class="pop-title">积分规则</div>
          <ul class="pop-rules">
            <li>每日登录：<b>+5</b> 积分</li>
            <li>每发布一篇：<b>+20</b> 积分</li>
            <li>帖子浏览量达 50 成为热帖：<b>+50</b> 积分</li>
          </ul>
          <div class="pop-title">等级（按累计积分）</div>
          <table class="lv-table">
            <tbody>
              <tr v-for="(l, i) in LEVELS" :key="i">
                <td class="lv-no">Lv{{ i + 1 }}</td>
                <td class="lv-name">{{ l.title }}</td>
                <td class="lv-min">{{ l.min }}+</td>
              </tr>
            </tbody>
          </table>
        </div>
      </el-popover>
    </div>

    <!-- 我的排名 -->
    <div class="me-card">
      <div class="me-avatar">
        <img v-if="auth.user?.avatar" :src="auth.user.avatar" alt="" />
        <span v-else>{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0) }}</span>
      </div>
      <div class="me-info">
        <div class="me-name">{{ auth.user?.displayName || auth.user?.username }}</div>
        <div class="me-sub">Lv{{ myLevel.level }} · {{ myLevel.title }}</div>
      </div>
      <div class="me-right">
        <div class="me-rank">第 <b>{{ me.rank || '—' }}</b> 名</div>
        <div class="me-points">{{ auth.user?.points || 0 }} 积分</div>
      </div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="top.length === 0" class="empty">暂无排行数据</div>
    <ul v-else class="list">
      <li v-for="(u, i) in top" :key="u.username" :class="{ mine: u.username === auth.user?.username }">
        <div class="rk" :class="rankClass(i)">{{ medal(i) }}</div>
        <div class="av">
          <img v-if="u.avatar" :src="u.avatar" alt="" />
          <span v-else>{{ (u.displayName || u.username).charAt(0) }}</span>
        </div>
        <div class="who">
          <div class="nm">{{ u.displayName }}</div>
          <div class="lv">Lv{{ getLevel(u.points).level }} · {{ getLevel(u.points).title }}</div>
        </div>
        <div class="pt">{{ u.points }} 分</div>
      </li>
    </ul>

    <p class="total" v-if="total">共 {{ total }} 位用户参与排行</p>
  </div>
</template>

<style scoped>
/* 居中：回到最初版本（max-width + margin:auto） */
.rank-page { max-width: 960px; margin: 0 auto; }
.head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.page-title { font-size: 20px; font-weight: 700; color: #1e293b; }
html.dark .page-title { color: #e7e7ee; }

/* 小圆圈触发按钮：中性灰默认，hover 才露品牌蓝 */
.info-circle {
  width: 22px; height: 22px; border-radius: 50%;
  border: 1px solid #94a3b8; background: transparent; color: #64748b;
  font-style: italic; font-weight: 700; font-family: Georgia, serif;
  cursor: pointer; line-height: 1; padding: 0; flex: none;
  transition: border-color .15s, color .15s, background-color .15s;
}
.info-circle:hover { border-color: #6366f1; color: #6366f1; background: #eef2ff; }
html.dark .info-circle { border-color: #5a5b6d; color: #9c9db0; }
html.dark .info-circle:hover { border-color: #818cf8; color: #a5b4fc; background: #1a1f2e; }

/* 弹层内容：标题用深色，数值用品牌蓝点缀 */
.pop-title { font-size: 13px; font-weight: 700; color: #1e293b; margin: 4px 0 6px; }
html.dark .pop-title { color: #e7e7ee; }
.pop-rules { margin: 0 0 4px; padding-left: 18px; font-size: 12px; color: #475569; }
html.dark .pop-rules { color: #a6a7b7; }
.pop-rules li { margin: 3px 0; }
.pop-rules b { color: #6366f1; font-weight: 700; }
html.dark .pop-rules b { color: #a5b4fc; }
.lv-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.lv-table td { padding: 3px 6px; border-bottom: 1px solid #f1f5f9; }
html.dark .lv-table td { border-bottom-color: #2a2a35; }
.lv-table tr:last-child td { border-bottom: none; }
.lv-no { color: #1e293b; font-weight: 700; width: 44px; }
html.dark .lv-no { color: #e7e7ee; }
.lv-name { color: #334155; }
html.dark .lv-name { color: #c3c4d8; }
.lv-min { color: #94a3b8; text-align: right; }

/* 我的排名：白底卡片 + 左侧品牌蓝小竖条（点缀） */
.me-card {
  display: flex; align-items: center; gap: 14px;
  background: #fff; color: #1e293b;
  border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 16px 18px; margin-bottom: 16px;
  position: relative; overflow: hidden;
}
html.dark .me-card { background: #18181f; border-color: #2a2a35; color: #e7e7ee; }
.me-card::before {
  content: ''; position: absolute; left: 0; top: 12px; bottom: 12px;
  width: 3px; border-radius: 0 3px 3px 0; background: #6366f1;
}
.me-avatar { width: 52px; height: 52px; border-radius: 50%; background: #f1f5f9; overflow: hidden; flex: none; color: #475569; }
html.dark .me-avatar { background: #2a2a35; color: #a6a7b7; }
.me-avatar img, .me-avatar span {
  width: 100%; height: 100%; object-fit: cover; display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 700;
}
.me-info { flex: 1; }
.me-name { font-size: 16px; font-weight: 700; }
.me-sub { font-size: 12px; color: #64748b; margin-top: 2px; }
html.dark .me-sub { color: #9c9db0; }
.me-right { text-align: right; }
.me-rank { font-size: 12px; color: #64748b; }
html.dark .me-rank { color: #9c9db0; }
.me-rank b { font-size: 20px; color: #1e293b; font-weight: 700; }
html.dark .me-rank b { color: #e7e7ee; }
.me-points { font-size: 13px; color: #6366f1; font-weight: 700; margin-top: 2px; }
html.dark .me-points { color: #a5b4fc; }

/* 排行榜列表：白底卡片，mine 行只用浅灰，前三用金银铜 */
.list { list-style: none; padding: 0; margin: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 16px; overflow: hidden; }
html.dark .list { background: #18181f; border-color: #2a2a35; }
.list li { display: flex; align-items: center; gap: 14px; padding: 12px 16px; border-bottom: 1px solid #f1f5f9; transition: background-color .12s; }
html.dark .list li { border-bottom-color: #2a2a35; }
.list li:last-child { border-bottom: none; }
.list li:hover { background: #f8fafc; }
html.dark .list li:hover { background: #23232e; }
.list li.mine { background: #f8fafc; }
html.dark .list li.mine { background: #23232e; }
.rk { width: 28px; text-align: center; font-weight: 700; font-size: 16px; color: #94a3b8; flex: none; }
.rk.gold { color: #f59e0b; }
.rk.silver { color: #94a3b8; }
.rk.bronze { color: #b45309; }
.av { width: 40px; height: 40px; border-radius: 50%; background: #f1f5f9; overflow: hidden; flex: none; color: #475569; }
html.dark .av { background: #2a2a35; color: #a6a7b7; }
.av img, .av span {
  width: 100%; height: 100%; object-fit: cover; display: flex; align-items: center; justify-content: center;
  font-size: 16px; font-weight: 700;
}
.who { flex: 1; }
.nm { font-size: 15px; font-weight: 600; color: #1e293b; }
html.dark .nm { color: #e7e7ee; }
.lv { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.pt { font-weight: 700; color: #1e293b; }
html.dark .pt { color: #e7e7ee; }
.empty { color: #94a3b8; padding: 20px; text-align: center; }
.total { text-align: center; font-size: 12px; color: #94a3b8; margin-top: 14px; }
</style>
