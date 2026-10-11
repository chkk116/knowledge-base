<script setup>
/**
 * 移动端「应用锁」设置
 *
 * 打开 App / 从后台切回需输入 PIN（校验界面在 MobileApp.vue 的锁屏遮罩里）。
 * 只存加盐哈希，不存明文（utils/appLock.js）。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getLockConfig, setPin, verifyPin, disableLock, setLockTimeout, isValidPin, clearLegacyLock } from '../../utils/appLock'

const router = useRouter()
const cfg = ref(getLockConfig())
const enabled = computed(() => !!cfg.value.enabled)

const TIMEOUTS = [
  { v: 0,   name: '立即' },
  { v: 30,  name: '30 秒' },
  { v: 60,  name: '1 分钟' },
  { v: 300, name: '5 分钟' },
]

/** 弹两次输入（设置 / 修改 PIN 共用） */
async function askPin(title) {
  let p1 = ''
  try {
    const r1 = await ElMessageBox.prompt('请输入 4~6 位数字密码', title, {
      confirmButtonText: '下一步', cancelButtonText: '取消',
      inputType: 'password', inputPattern: /^\d{4,6}$/, inputErrorMessage: '需 4~6 位数字',
      inputPlaceholder: '4~6 位数字',
    })
    p1 = r1.value
  } catch { return null }
  try {
    const r2 = await ElMessageBox.prompt('请再次输入以确认', title, {
      confirmButtonText: '确定', cancelButtonText: '取消',
      inputType: 'password', inputPattern: /^\d{4,6}$/, inputErrorMessage: '需 4~6 位数字',
      inputPlaceholder: '再输一次',
    })
    if (r2.value !== p1) { window.__kbMsg?.error('两次输入不一致'); return null }
    return p1
  } catch { return null }
}

async function onEnable() {
  const pin = await askPin('开启应用锁')
  if (!pin || !isValidPin(pin)) return
  try { await setPin(pin) } catch (e) { window.__kbMsg?.error('开启失败：' + (e?.message || e)); return }
  cfg.value = getLockConfig()
  window.__kbMsg?.success('应用锁已开启')
}

async function onChange() {
  const cur = await ElMessageBox.prompt('请先验证当前密码', '修改密码', {
    confirmButtonText: '下一步', cancelButtonText: '取消',
    inputType: 'password', inputPlaceholder: '当前密码',
  }).catch(() => null)
  if (!cur) return
  try {
    if (!(await verifyPin(cur.value))) { window.__kbMsg?.error('密码不正确'); return }
  } catch (e) { window.__kbMsg?.error(e?.message || '校验失败'); return }
  const pin = await askPin('设置新密码')
  if (!pin) return
  try { await setPin(pin) } catch (e) { window.__kbMsg?.error('设置失败：' + (e?.message || e)); return }
  cfg.value = getLockConfig()
  window.__kbMsg?.success('密码已更新')
}

async function onDisable() {
  const r = await ElMessageBox.prompt('请输入当前密码以关闭', '关闭应用锁', {
    confirmButtonText: '关闭', cancelButtonText: '取消',
    inputType: 'password', inputPlaceholder: '当前密码', type: 'warning',
  }).catch(() => null)
  if (!r) return
  try {
    await disableLock(r.value)
  } catch (e) {
    const msg = e?.message || '关闭失败'
    // 旧版 FNV 哈希存量：算法升级后需重置一次（旧哈希无法校验明文 PIN）
    if (msg.includes('算法已升级')) {
      try {
        await ElMessageBox.confirm('旧版密码格式无法直接校验，需重置应用锁（关闭后可重新开启）', '应用锁升级', {
          confirmButtonText: '重置并关闭', cancelButtonText: '暂不', type: 'warning',
        })
      } catch { return }
      clearLegacyLock()
      cfg.value = getLockConfig()
      window.__kbMsg?.success('已重置，应用锁已关闭')
      return
    }
    window.__kbMsg?.error(msg)
    return
  }
  cfg.value = getLockConfig()
  window.__kbMsg?.success('应用锁已关闭')
}

function pickTimeout(v) {
  setLockTimeout(v)
  cfg.value = getLockConfig()
}
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">应用锁</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <div class="hero" :class="{ on: enabled }">
        <span class="hero-ico"><el-icon :size="30"><component :is="enabled ? 'Lock' : 'Unlock'" /></el-icon></span>
        <div class="hero-t">{{ enabled ? '应用锁已开启' : '应用锁未开启' }}</div>
        <div class="hero-d">
          {{ enabled ? '打开 App 或从后台切回时，需要输入密码' : '开启后，他人拿到手机也无法查看你的文档' }}
        </div>
        <button class="hero-btn" @click="enabled ? onDisable() : onEnable()">
          {{ enabled ? '关闭应用锁' : '开启应用锁' }}
        </button>
      </div>

      <template v-if="enabled">
        <div class="m-card sec">
          <div class="m-sec-title">自动锁定</div>
          <div class="chips">
            <button v-for="t in TIMEOUTS" :key="t.v" class="chip" :class="{ on: cfg.timeout === t.v }"
                    type="button" @click="pickTimeout(t.v)">{{ t.name }}</button>
          </div>
          <div class="hint">切到后台超过该时间后，回来需要重新验证</div>
        </div>

        <div class="m-card sec">
          <button class="m-row" type="button" @click="onChange">
            <span class="m-ico gray"><el-icon><Key /></el-icon></span>
            <span class="m-row-body"><span class="m-row-title">修改密码</span></span>
            <el-icon class="m-row-extra"><Right /></el-icon>
          </button>
        </div>
      </template>

      <div class="tip">密码只保存在本机（加盐哈希），不会上传服务器；忘记密码需重新登录并重设。</div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }

.hero {
  background: var(--m-card); border-radius: var(--m-radius-lg); padding: 24px 18px 20px;
  text-align: center; box-shadow: var(--m-shadow); margin-bottom: 12px;
}
.hero-ico {
  display: grid; place-items: center; width: 62px; height: 62px; border-radius: 20px;
  margin: 0 auto; background: var(--m-card-2); color: var(--m-text-dim);
}
.hero.on .hero-ico { background: var(--m-primary-soft); color: var(--m-primary); }
.hero-t { margin-top: 12px; font-size: 16px; font-weight: 600; color: var(--m-text); }
.hero-d { margin-top: 6px; font-size: 12.5px; color: var(--m-text-dim); line-height: 1.6; }
.hero-btn {
  margin-top: 16px; height: 40px; padding: 0 22px; border-radius: 20px; border: none;
  background: var(--m-primary); color: #fff; font-size: 14.5px; cursor: pointer;
}
.hero.on .hero-btn { background: var(--m-card-2); color: var(--m-danger); border: 1px solid var(--m-border); }

.sec { padding: 4px 14px 10px; }
.sec .m-sec-title { padding-left: 0; }
.chips { display: flex; gap: 8px; padding: 4px 0 2px; }
.chip {
  flex: 1 1 0; height: 36px; border-radius: 10px; cursor: pointer; font-size: 13.5px;
  border: 1px solid var(--m-border); background: var(--m-card-2); color: var(--m-text-sub);
}
.chip.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); font-weight: 600; }
.hint { font-size: 12px; color: var(--m-text-dim); padding-top: 4px; }
.tip { margin-top: 14px; font-size: 12px; color: var(--m-text-dim); line-height: 1.7; }
</style>
