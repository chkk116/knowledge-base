<template>
  <div class="mail">
    <!-- 左：账号与文件夹 -->
    <aside class="mail-nav">
      <div class="mn-head">
        <span class="mn-title">邮件</span>
        <el-tooltip content="绑定新邮箱" placement="bottom">
          <el-button text size="small" @click="openBind"><el-icon><Plus /></el-icon></el-button>
        </el-tooltip>
      </div>

      <div v-if="!accounts.length" class="mn-empty">
        <p>还没有绑定邮箱</p>
        <el-button type="primary" size="small" @click="openBind">绑定邮箱</el-button>
      </div>

      <div v-else class="mn-accounts">
        <div
          v-for="a in accounts" :key="a.id"
          class="mn-acc" :class="{ on: activeId === a.id }"
          @click="pickAccount(a.id)"
        >
          <span class="dot" :class="a.status === 'OK' ? 'ok' : 'err'" />
          <div class="mn-acc-main">
            <div class="mn-acc-mail">{{ a.email }}</div>
            <div class="mn-acc-sub">
              {{ providerName(a.provider) }}
              <template v-if="a.lastSyncAt"> · {{ fromNow(a.lastSyncAt) }}</template>
            </div>
          </div>
          <el-dropdown trigger="click" @command="(cmd) => onAccCmd(cmd, a)">
            <span class="mn-more" @click.stop><el-icon><MoreFilled /></el-icon></span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="sync" :disabled="syncing">立即同步</el-dropdown-item>
                <el-dropdown-item command="retest">重测连接</el-dropdown-item>
                <el-dropdown-item command="remove" divided>解绑</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <div class="mn-folders">
        <div class="mn-folder" :class="{ on: !unreadOnly }" @click="unreadOnly = false; loadList()">
          <el-icon><Message /></el-icon><span>全部邮件</span>
          <em v-if="total">{{ total }}</em>
        </div>
        <div class="mn-folder" :class="{ on: unreadOnly }" @click="unreadOnly = true; loadList()">
          <el-icon><Bell /></el-icon><span>未读</span>
        </div>
      </div>

      <div class="mn-foot">
        <span v-if="syncing" class="mn-sync">同步中…</span>
        <el-button text size="small" @click="syncActive">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </aside>

    <!-- 中：邮件列表 -->
    <section class="mail-list">
      <div class="ml-head">
        <el-input v-model="keyword" size="small" placeholder="搜索主题 / 发件人" clearable
                  @keyup.enter="loadList" @clear="loadList">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
      <div class="ml-body">
        <div v-if="loading" class="ml-hint">加载中…</div>
        <el-empty v-else-if="!messages.length" :image-size="72"
                  :description="accounts.length ? '没有邮件（绑定后首次同步需要几秒）' : '先绑定一个邮箱'" />
        <template v-else>
          <div
            v-for="m in messages" :key="m.id"
            class="ml-item" :class="{ on: current && current.id === m.id, unread: m.unread, active: activeId && m.accountId === activeId }"
            @click="openMessage(m.id)"
          >
            <div class="ml-row1">
              <span class="ml-from">{{ m.fromName || m.fromAddr || '未知发件人' }}</span>
              <span class="ml-time">{{ fmtTime(m.sentAt) }}</span>
            </div>
            <div class="ml-subject">
              <span v-if="m.hasAttach" class="clip"><el-icon><Paperclip /></el-icon></span>
              {{ m.subject || '（无主题）' }}
            </div>
            <div class="ml-snippet">{{ m.snippet }}</div>
          </div>
        </template>
      </div>
      <div v-if="total > messages.length" class="ml-more">
        <el-button text size="small" :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>
    </section>

    <!-- 右：阅读窗 -->
    <section class="mail-read">
      <div v-if="!current" class="mr-empty">
        <div class="mr-empty-ico"><el-icon><Message /></el-icon></div>
        <p>选择一封邮件查看</p>
      </div>
      <template v-else>
        <header class="mr-head">
          <h2>{{ current.subject || '（无主题）' }}</h2>
          <div class="mr-meta">
            <el-avatar :size="30" class="mr-avatar">{{ (current.fromName || current.fromAddr || '?').slice(0, 1) }}</el-avatar>
            <div class="mr-from">
              <div class="mr-from-name">{{ current.fromName || current.fromAddr }}</div>
              <div class="mr-from-sub">{{ current.fromAddr }} · {{ fmtFull(current.sentAt) }}</div>
            </div>
            <el-tag v-if="current.unread" size="small" type="warning" effect="plain">未读</el-tag>
          </div>
        </header>

        <div class="mr-attach" v-if="attachments.length">
          <div v-for="(f, i) in attachments" :key="i" class="mr-file">
            <el-icon><Document /></el-icon>
            <span class="fname">{{ f.name }}</span>
            <span class="fsize">{{ fmtSize(f.size) }}</span>
            <a v-if="f.url" :href="f.url" target="_blank" class="fopen">查看</a>
            <span v-else class="fnosave">未保存</span>
          </div>
        </div>

        <div class="mr-body" v-if="current.htmlBody" v-html="safeHtml" />
        <pre class="mr-text" v-else>{{ current.textBody || '（无正文）' }}</pre>
      </template>
    </section>

    <!-- 绑定邮箱 -->
    <el-dialog v-model="bindOpen" title="绑定邮箱" width="520px">
      <div class="bind">
        <div class="bind-providers">
          <div
            v-for="p in providers" :key="p.id"
            class="bind-p" :class="{ on: form.provider === p.id }"
            @click="pickProvider(p)"
          >
            <span class="bp-name">{{ p.name }}</span>
            <span class="bp-type">{{ p.authType === 'OAUTH' ? 'OAuth 授权' : '授权码' }}</span>
          </div>
        </div>

        <template v-if="curProvider">
          <el-alert v-if="curProvider.authType === 'CODE'" type="info" :closable="false" class="bind-tip">
            需要先在邮箱网页版「设置 → 账户」里开启 IMAP 并生成<b>授权码</b>（不是登录密码）。
          </el-alert>
          <el-alert v-else type="info" :closable="false" class="bind-tip">
            点击下方按钮跳转 {{ curProvider.name }} 授权页，授权后自动回到本系统。
          </el-alert>

          <el-form label-width="86px" class="bind-form">
            <el-form-item label="邮箱地址">
              <el-input v-model="form.email" :placeholder="curProvider.emailHint" :disabled="curProvider.authType === 'OAUTH'" />
            </el-form-item>
            <el-form-item v-if="curProvider.authType === 'CODE'" label="授权码">
              <el-input v-model="form.code" type="password" show-password placeholder="邮箱设置里生成的授权码" />
            </el-form-item>
            <template v-if="form.provider === 'CUSTOM'">
              <el-form-item label="IMAP">
                <el-input v-model="form.imapHost" placeholder="imap.example.com" />
              </el-form-item>
              <el-form-item label="SMTP">
                <el-input v-model="form.smtpHost" placeholder="smtp.example.com" />
              </el-form-item>
            </template>
          </el-form>

          <div v-if="bindError" class="bind-err">{{ bindError }}</div>
        </template>
      </div>
      <template #footer>
        <el-button @click="bindOpen = false">取消</el-button>
        <el-button
          v-if="curProvider && curProvider.authType === 'OAUTH'"
          type="primary" :loading="oauthStarting" @click="doOauth"
        >去授权</el-button>
        <el-button
          v-else
          type="primary" :loading="binding" :disabled="!form.email || !form.code" @click="doBind"
        >绑定并同步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, MoreFilled, Message, Bell, Search, Refresh, Paperclip, Document } from '@element-plus/icons-vue'
import { fromNow } from '../utils/time'
import { resolveUrl } from '../utils/runtimeBase'
import {
  mailProviders, listMailAccounts, bindMailByCode, startMailOauth,
  retestMailAccount, removeMailAccount, syncMailAccount, listMailMessages, getMailMessage
} from '../api/mail'

const providers = ref([])
const accounts = ref([])
const activeId = ref(null)
const unreadOnly = ref(false)
const keyword = ref('')
const messages = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const loadingMore = ref(false)
const syncing = ref(false)
const current = ref(null)

const bindOpen = ref(false)
const binding = ref(false)
const oauthStarting = ref(false)
const bindError = ref('')
const form = ref({ provider: 'QQ', email: '', code: '', imapHost: '', smtpHost: '' })
const curProvider = computed(() => providers.value.find((p) => p.id === form.value.provider) || null)

const providerName = (id) => (providers.value.find((p) => p.id === id) || {}).name || id

const attachments = computed(() => {
  if (!current.value || !current.value.attachments) return []
  try { return JSON.parse(current.value.attachments) || [] } catch { return [] }
})

/** 邮件 HTML 一律进 iframe 隔离不现实，这里至少去掉脚本与事件属性 */
const safeHtml = computed(() => {
  const raw = String(current.value?.htmlBody || '')
  return raw
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<iframe[\s\S]*?<\/iframe>/gi, '')
    .replace(/\son\w+\s*=\s*"[^"]*"/gi, '')
    .replace(/\son\w+\s*=\s*'[^']*'/gi, '')
    .replace(/javascript:/gi, '')
})

function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function fmtFull(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function fmtSize(n) {
  if (!n) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

async function loadProviders() {
  try { providers.value = (await mailProviders()).data || [] } catch { providers.value = [] }
}

async function loadAccounts() {
  try { accounts.value = (await listMailAccounts()).data || [] } catch { accounts.value = [] }
  if (!activeId.value && accounts.value.length) activeId.value = null
}

async function loadList(more = false) {
  if (more) loadingMore.value = true
  else { loading.value = true; page.value = 0 }
  try {
    const params = { page: page.value, size: 30 }
    if (activeId.value) params.accountId = activeId.value
    if (keyword.value.trim()) params.q = keyword.value.trim()
    const { data } = await listMailMessages(params)
    const items = data.items || []
    messages.value = more ? [...messages.value, ...items] : items
    total.value = data.total || 0
  } catch (e) {
    if (!more) messages.value = []
  } finally {
    loading.value = false
    loadingMore.value = false
  }
  // 未读过滤是前端过滤（后端按账号/关键词即可，保持接口简单）
  if (unreadOnly.value) messages.value = messages.value.filter((m) => m.unread)
}

function loadMore() {
  page.value += 1
  loadList(true)
}

function pickAccount(id) {
  activeId.value = activeId.value === id ? null : id
  loadList()
}

async function openMessage(id) {
  try {
    current.value = (await getMailMessage(id)).data
  } catch (e) {
    ElMessage.error('读取邮件失败')
  }
}

async function syncActive() {
  if (!accounts.value.length) { ElMessage.info('先绑定一个邮箱'); return }
  syncing.value = true
  try {
    let added = 0
    for (const a of accounts.value) {
      try { const { data } = await syncMailAccount(a.id); added += data.added || 0 } catch (e) { /* 单个失败不影响其它 */ }
    }
    await loadAccounts()
    await loadList()
    ElMessage.success(added > 0 ? `已同步 ${added} 封新邮件` : '已是最新')
  } finally {
    syncing.value = false
  }
}

async function onAccCmd(cmd, a) {
  if (cmd === 'sync') {
    syncing.value = true
    try {
      const { data } = await syncMailAccount(a.id)
      await loadAccounts(); await loadList()
      ElMessage.success(data.added > 0 ? `已同步 ${data.added} 封` : '已是最新')
    } catch (e) { ElMessage.error('同步失败') } finally { syncing.value = false }
  } else if (cmd === 'retest') {
    try {
      const { data } = await retestMailAccount(a.id)
      ElMessage[data.status === 'OK' ? 'success' : 'error'](data.status === 'OK' ? '连接正常' : (data.lastError || '连接失败'))
      await loadAccounts()
    } catch (e) { ElMessage.error('重测失败') }
  } else if (cmd === 'remove') {
    try {
      await ElMessageBox.confirm(`解绑 ${a.email}？已同步的邮件会一并删除。`, '解绑邮箱', { type: 'warning' })
      await removeMailAccount(a.id)
      await loadAccounts()
      if (!accounts.value.length) { messages.value = []; current.value = null }
      loadList()
    } catch (e) { /* 取消 */ }
  }
}

function openBind() {
  bindError.value = ''
  form.value = { provider: 'QQ', email: '', code: '', imapHost: '', smtpHost: '' }
  bindOpen.value = true
}

function pickProvider(p) {
  form.value.provider = p.id
  form.value.email = ''
  form.value.code = ''
  form.value.imapHost = p.imapHost || ''
  form.value.smtpHost = p.smtpHost || ''
  bindError.value = ''
}

async function doBind() {
  binding.value = true
  bindError.value = ''
  try {
    const payload = {
      provider: form.value.provider,
      email: form.value.email.trim(),
      code: form.value.code.trim(),
    }
    if (form.value.provider === 'CUSTOM') {
      payload.imapHost = form.value.imapHost.trim()
      payload.smtpHost = form.value.smtpHost.trim()
    }
    const { data } = await bindMailByCode(payload)
    bindOpen.value = false
    ElMessage.success(`已绑定 ${data.email}，正在同步…`)
    await loadAccounts()
    await loadList()
  } catch (e) {
    bindError.value = e?.response?.data?.error || e.message || '绑定失败'
  } finally {
    binding.value = false
  }
}

async function doOauth() {
  oauthStarting.value = true
  try {
    const { data } = await startMailOauth(form.value.provider)
    window.open(data.url, 'kb-mail-oauth', 'width=560,height=680')
  } catch (e) {
    bindError.value = e?.response?.data?.error || e.message || '无法发起授权'
  } finally {
    oauthStarting.value = false
  }
}

function onOauthMsg(ev) {
  const d = ev?.data
  if (!d || d.type !== 'kb-mail-oauth') return
  bindOpen.value = false
  if (d.ok) {
    ElMessage.success('邮箱已绑定，正在同步…')
    loadAccounts().then(() => loadList())
  } else {
    ElMessage.error('授权未完成')
  }
}

onMounted(async () => {
  window.addEventListener('message', onOauthMsg)
  await loadProviders()
  await loadAccounts()
  await loadList()
})

onBeforeUnmount(() => window.removeEventListener('message', onOauthMsg))
</script>

<style scoped>
.mail { display: flex; height: calc(100vh - 0px); background: #fff; }
.mail-nav { width: 236px; flex: none; border-right: 1px solid #eef0f3; display: flex; flex-direction: column; background: #fbfcfd; }
.mn-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px 8px; }
.mn-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.mn-empty { padding: 20px 16px; text-align: center; color: #94a3b8; font-size: 12.5px; }
.mn-empty p { margin: 0 0 10px; }
.mn-accounts { overflow: auto; }
.mn-acc { display: flex; align-items: center; gap: 8px; padding: 9px 12px; cursor: pointer; border-left: 3px solid transparent; }
.mn-acc:hover { background: #f1f5f9; }
.mn-acc.on { background: #eef2ff; border-left-color: #6366f1; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.dot.ok { background: #16a34a; }
.dot.err { background: #dc2626; }
.mn-acc-main { min-width: 0; flex: 1; }
.mn-acc-mail { font-size: 12.5px; color: #1e293b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mn-acc-sub { font-size: 11px; color: #94a3b8; }
.mn-more { color: #94a3b8; cursor: pointer; }
.mn-folders { padding: 10px 8px; border-top: 1px solid #eef0f3; }
.mn-folder { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 7px; font-size: 12.5px; color: #475569; cursor: pointer; }
.mn-folder:hover { background: #f1f5f9; }
.mn-folder.on { background: #eef2ff; color: #4f46e5; font-weight: 500; }
.mn-folder em { margin-left: auto; font-style: normal; font-size: 11px; color: #94a3b8; }
.mn-foot { margin-top: auto; padding: 8px 12px; border-top: 1px solid #eef0f3; display: flex; align-items: center; justify-content: space-between; }
.mn-sync { font-size: 11px; color: #6366f1; }

.mail-list { width: 336px; flex: none; border-right: 1px solid #eef0f3; display: flex; flex-direction: column; }
.ml-head { padding: 10px 12px; }
.ml-body { flex: 1; overflow: auto; }
.ml-hint { padding: 24px; text-align: center; color: #94a3b8; font-size: 12.5px; }
.ml-item { padding: 10px 14px; border-bottom: 1px solid #f4f6f8; cursor: pointer; }
.ml-item:hover { background: #f8fafc; }
.ml-item.on { background: #eef2ff; }
.ml-item.unread .ml-from { font-weight: 600; color: #1e293b; }
.ml-row1 { display: flex; align-items: baseline; gap: 8px; }
.ml-from { font-size: 12.5px; color: #475569; flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ml-time { font-size: 11px; color: #94a3b8; flex: none; }
.ml-subject { margin-top: 2px; font-size: 13px; color: #1e293b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.clip { color: #64748b; margin-right: 4px; font-size: 11px; }
.ml-snippet { margin-top: 2px; font-size: 11.5px; color: #94a3b8; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ml-more { padding: 6px; text-align: center; border-top: 1px solid #f1f5f9; }

.mail-read { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.mr-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: #cbd5e1; }
.mr-empty-ico { font-size: 40px; margin-bottom: 8px; }
.mr-head { padding: 18px 22px 10px; border-bottom: 1px solid #f1f5f9; }
.mr-head h2 { margin: 0 0 10px; font-size: 17px; color: #0f172a; line-height: 1.45; }
.mr-meta { display: flex; align-items: center; gap: 10px; }
.mr-avatar { background: #6366f1; color: #fff; font-size: 13px; }
.mr-from-name { font-size: 12.5px; color: #1e293b; }
.mr-from-sub { font-size: 11px; color: #94a3b8; }
.mr-attach { padding: 10px 22px; display: flex; flex-wrap: wrap; gap: 8px; border-bottom: 1px solid #f1f5f9; }
.mr-file { display: flex; align-items: center; gap: 6px; padding: 5px 9px; border: 1px solid #e5e7eb; border-radius: 7px; font-size: 11.5px; color: #475569; }
.fsize { color: #94a3b8; }
.fopen { color: #4f46e5; text-decoration: none; }
.fnosave { color: #cbd5e1; }
.mr-body { flex: 1; overflow: auto; padding: 16px 22px 28px; font-size: 13.5px; line-height: 1.8; color: #334155; }
.mr-body :deep(img) { max-width: 100%; }
.mr-body :deep(table) { border-collapse: collapse; }
.mr-body :deep(td), .mr-body :deep(th) { border: 1px solid #e5e7eb; padding: 4px 8px; }
.mr-text { flex: 1; overflow: auto; margin: 0; padding: 16px 22px 28px; font-family: inherit; font-size: 13.5px; line-height: 1.8; color: #334155; white-space: pre-wrap; word-break: break-word; }

.bind-providers { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.bind-p { padding: 7px 11px; border: 1px solid #e5e7eb; border-radius: 8px; cursor: pointer; display: flex; flex-direction: column; min-width: 118px; }
.bind-p:hover { border-color: #c7d2fe; }
.bind-p.on { border-color: #6366f1; background: #eef2ff; }
.bp-name { font-size: 12.5px; color: #1e293b; }
.bp-type { font-size: 10.5px; color: #94a3b8; }
.bind-tip { margin-bottom: 12px; }
.bind-err { color: #dc2626; font-size: 12px; margin-top: 6px; }

@media (max-width: 1024px) {
  .mail-nav { width: 190px; }
  .mail-list { width: 280px; }
}
@media (max-width: 640px) {
  .mail { flex-direction: column; }
  .mail-nav, .mail-list { width: 100%; border-right: none; border-bottom: 1px solid #eef0f3; }
  .mail-nav { max-height: 190px; }
  .mail-list { max-height: 46vh; }
  .mail-read { flex: 1; }
}
</style>
