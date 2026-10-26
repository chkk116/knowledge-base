<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="hd">
          <span>企业登录 · LDAP / AD</span>
          <el-tag v-if="cfg.enabled" type="success" size="small">已启用</el-tag>
          <el-tag v-else type="info" size="small">未启用</el-tag>
        </div>
      </template>

      <p class="tip">
        启用后登录页会出现「使用域账号登录」。流程：服务账号先 bind → 按过滤条件搜出用户 DN → 用该 DN + 用户密码再 bind 验证。
        本站不保存域密码，只保存服务账号口令（用于搜索）。
      </p>

      <el-form label-width="130px" style="max-width: 720px">
        <el-form-item label="启用域账号登录"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="LDAP 地址">
          <el-input v-model="form.url" placeholder="ldap://dc.example.com:389 或 ldaps://dc.example.com:636" />
        </el-form-item>
        <el-form-item label="Base DN">
          <el-input v-model="form.baseDn" placeholder="DC=corp,DC=local" />
        </el-form-item>
        <el-form-item label="用户过滤">
          <el-input v-model="form.userFilter" placeholder="(sAMAccountName={0})　AD 用这个；OpenLDAP 用 (uid={0})" />
          <div class="hint">{0} 会被替换成转义后的登录名</div>
        </el-form-item>
        <el-form-item label="服务账号 DN">
          <el-input v-model="form.bindDn" placeholder="CN=svc-kb,OU=Service,DC=corp,DC=local" />
          <div class="hint">只读搜索用；留空表示匿名 bind（多数域控不允许）</div>
        </el-form-item>
        <el-form-item label="服务账号密码">
          <el-input v-model="form.bindPassword" type="password" show-password
                    :placeholder="cfg.hasBindPassword ? '已配置（留空则不改）' : '请输入'" />
        </el-form-item>
        <el-form-item label="显示名属性">
          <el-input v-model="form.attrName" placeholder="displayName（AD）/ cn（OpenLDAP）" />
        </el-form-item>
        <el-form-item label="邮箱属性">
          <el-input v-model="form.attrMail" placeholder="mail" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" @click="test">测试连接</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :title="testResult.message"
                :closable="false" show-icon style="max-width: 720px" />
    </el-card>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="hd">
          <span>企业登录 · OIDC / 扫码</span>
          <el-tag v-if="oidc.enabled" type="success" size="small">已启用</el-tag>
          <el-tag v-else type="info" size="small">未启用</el-tag>
        </div>
      </template>
      <p class="tip">
        标准 OIDC 授权码流程（Keycloak / Azure AD / 企业微信 / 钉钉 / 飞书 的自建应用均可）。
        回调地址填：<code>{{ origin }}/api/auth/sso/oidc/callback</code>
      </p>
      <el-form label-width="130px" style="max-width: 720px">
        <el-form-item label="启用单点登录"><el-switch v-model="oidc.enabled" /></el-form-item>
        <el-form-item label="授权页地址"><el-input v-model="oidc.authUrl" placeholder="https://id.example.com/oauth2/authorize" /></el-form-item>
        <el-form-item label="Token 地址"><el-input v-model="oidc.tokenUrl" placeholder="https://id.example.com/oauth2/token" /></el-form-item>
        <el-form-item label="用户信息地址"><el-input v-model="oidc.userinfoUrl" placeholder="https://id.example.com/oauth2/userinfo" /></el-form-item>
        <el-form-item label="Client ID"><el-input v-model="oidc.clientId" /></el-form-item>
        <el-form-item label="Client Secret">
          <el-input v-model="oidc.clientSecret" type="password" show-password
                    :placeholder="oidcCfg.hasClientSecret ? '已配置（留空则不改）' : '可空（公开客户端）'" />
        </el-form-item>
        <el-form-item label="Scopes"><el-input v-model="oidc.scopes" placeholder="openid profile email" /></el-form-item>
        <el-form-item label="用户标识字段"><el-input v-model="oidc.userField" placeholder="preferred_username（企微 userid / 钉钉 nick）" /></el-form-item>
        <el-form-item label="按钮文案"><el-input v-model="oidc.buttonLabel" placeholder="企业单点登录" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="oidcSaving" @click="saveOidc">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <!-- 第三方扫码登录（微信 / QQ / GitHub） -->
    <el-card v-for="sp in socials" :key="sp.key" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="hd">
          <span>{{ sp.title }}</span>
          <el-tag v-if="sp.cfg.enabled" type="success" size="small">已启用</el-tag>
          <el-tag v-else size="small">未启用</el-tag>
        </div>
      </template>
      <p class="tip">{{ sp.tip }}</p>
      <el-form label-width="130px" style="max-width: 720px">
        <el-form-item label="启用">
          <el-switch v-model="sp.form.enabled" />
        </el-form-item>
        <el-form-item :label="sp.idLabel">
          <el-input v-model="sp.form.id" :placeholder="sp.idPlaceholder" />
        </el-form-item>
        <el-form-item :label="sp.secretLabel">
          <el-input v-model="sp.form.secret" type="password" show-password
                    :placeholder="sp.cfg['has' + sp.secretFlag] ? '已保存，留空表示不修改' : '粘贴密钥'" />
        </el-form-item>
        <el-form-item label="回调地址">
          <el-input :model-value="origin + '/api/auth/sso/' + sp.key + '/callback'" readonly>
            <template #append>
              <el-button @click="copy(sp.key)">复制</el-button>
            </template>
          </el-input>
          <div class="hint">必须与{{ sp.title }}后台登记的「授权回调域/回调地址」完全一致（含 http/https 与路径）</div>
        </el-form-item>
        <el-form-item label="登录按钮文案">
          <el-input v-model="sp.form.buttonLabel" :placeholder="sp.defaultLabel" />
        </el-form-item>
        <el-form-item label="首次扫码自动注册">
          <el-switch v-model="sp.form.autoRegister" />
          <div class="hint">
            关闭时：只有「已绑定」的账号能扫码登录（更安全，适合内部知识库）；
            开启时：任何人扫码都会自动开一个 USER 账号（适合公开站点）
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="sp.saving" @click="saveSocial(sp)">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api/request'
import { errMsg } from '../../utils/errMsg'

const cfg = ref({ enabled: false, hasBindPassword: false })
const oidcCfg = ref({ hasClientSecret: false })
const oidc = ref({ enabled: false, authUrl: '', tokenUrl: '', userinfoUrl: '', clientId: '', clientSecret: '', scopes: 'openid profile email', userField: 'preferred_username', buttonLabel: '企业单点登录' })
const oidcSaving = ref(false)
const origin = typeof window !== 'undefined' ? window.location.origin : ''

async function loadOidc() {
  try {
    const { data } = await api.get('/admin/sso/oidc')
    oidcCfg.value = data || {}
    oidc.value = {
      enabled: !!data.enabled, authUrl: data.authUrl || '', tokenUrl: data.tokenUrl || '',
      userinfoUrl: data.userinfoUrl || '', clientId: data.clientId || '', clientSecret: '',
      scopes: data.scopes || 'openid profile email',
      userField: data.userField || 'preferred_username',
      buttonLabel: data.buttonLabel || '企业单点登录'
    }
  } catch (e) { ElMessage.error(errMsg(e, '读取 OIDC 配置失败')) }
}

async function saveOidc() {
  oidcSaving.value = true
  try {
    const { data } = await api.put('/admin/sso/oidc', { ...oidc.value, clientSecret: oidc.value.clientSecret || null })
    oidcCfg.value = data
    oidc.value.clientSecret = ''
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    oidcSaving.value = false
  }
}
const form = ref({
  enabled: false, url: '', baseDn: '', userFilter: '(sAMAccountName={0})',
  bindDn: '', bindPassword: '', attrName: 'displayName', attrMail: 'mail'
})
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

async function load() {
  try {
    const { data } = await api.get('/admin/sso/ldap')
    cfg.value = data || cfg.value
    form.value = {
      enabled: !!cfg.value.enabled,
      url: cfg.value.url || '',
      baseDn: cfg.value.baseDn || '',
      userFilter: cfg.value.userFilter || '(sAMAccountName={0})',
      bindDn: cfg.value.bindDn || '',
      bindPassword: '',
      attrName: cfg.value.attrName || 'displayName',
      attrMail: cfg.value.attrMail || 'mail'
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '读取配置失败'))
  }
}

async function save() {
  saving.value = true
  try {
    const { data } = await api.put('/admin/sso/ldap', { ...form.value, bindPassword: form.value.bindPassword || null })
    cfg.value = data
    form.value.bindPassword = ''
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  testResult.value = null
  try {
    const { data } = await api.post('/admin/sso/ldap/test', {})
    testResult.value = data
  } catch (e) {
    testResult.value = { ok: false, message: errMsg(e, '测试失败') }
  } finally {
    testing.value = false
  }
}

// ===== 第三方扫码登录（微信 / QQ / GitHub） =====
// 三家的字段名不同（微信 AppID/AppSecret、QQ APP ID/APP Key、GitHub Client ID/Client Secret），
// 但接口形状一致，所以用一张表驱动渲染，省三份几乎一样的表单。
const socials = ref([
  {
    key: 'wechat', title: '微信扫码登录',
    idField: 'appId', secretField: 'appSecret', secretFlag: 'AppSecret',
    idLabel: 'AppID', secretLabel: 'AppSecret',
    idPlaceholder: '开放平台 → 网站应用 → AppID',
    defaultLabel: '微信扫码登录',
    tip: '需要微信开放平台「网站应用」（企业资质 + 300 元/年认证 + 已备案域名）。扫码走 snsapi_login，用户用手机微信扫一下就登录。',
    cfg: {}, form: {}, saving: false
  },
  {
    key: 'qq', title: 'QQ 登录',
    idField: 'appId', secretField: 'appKey', secretFlag: 'AppKey',
    idLabel: 'APP ID', secretLabel: 'APP Key',
    idPlaceholder: 'QQ互联 → 应用管理 → APP ID',
    defaultLabel: 'QQ 登录',
    tip: '需要 QQ 互联（connect.qq.com）创建网站应用并审核通过。注意回调地址必须与登记的完全一致。',
    cfg: {}, form: {}, saving: false
  },
  {
    key: 'gitee', title: 'Gitee 登录',
    idField: 'clientId', secretField: 'clientSecret', secretFlag: 'ClientSecret',
    idLabel: 'Client ID', secretLabel: 'Client Secret',
    idPlaceholder: 'gitee.com/oauth/applications 里的 Client ID',
    defaultLabel: 'Gitee 登录',
    tip: '国内门槛最低的一家：gitee.com/oauth/applications 新建应用即可，**个人账号就能申请**，不要企业资质和认证费。'
      + '回调地址填 https://你的域名/api/auth/sso/gitee/callback，审核基本是即时生效。',
    cfg: {}, form: {}, saving: false
  },
  {
    key: 'github', title: 'GitHub 登录',
    idField: 'clientId', secretField: 'clientSecret', secretFlag: 'ClientSecret',
    idLabel: 'Client ID', secretLabel: 'Client Secret',
    idPlaceholder: 'Iv23li… 或 Ov23li…（不是数字 App ID）',
    defaultLabel: 'GitHub 登录',
    tip: '门槛最低，不需要备案和认证费。OAuth App 与 GitHub App 都能用：'
      + '①「App ID / 应用 ID」是一串纯数字，那是给安装令牌用的，这里用不到；'
      + '② 这里要填的是「Client ID / 客户 ID」，形如 Iv23li… / Ov23li…；'
      + '③ Client secret 是 40 位十六进制，GitHub App 需先点「Generate a new client secret」生成（只显示一次）。',
    cfg: {}, form: {}, saving: false
  }
])

async function loadSocials() {
  for (const sp of socials.value) {
    try {
      const { data } = await api.get(`/admin/sso/${sp.key}`)
      sp.cfg = data || {}
      sp.form = {
        enabled: !!data.enabled,
        id: data[sp.idField] || '',
        secret: '',
        buttonLabel: data.buttonLabel || sp.defaultLabel,
        autoRegister: !!data.autoRegister
      }
    } catch (e) {
      // 单个身份源读失败不影响其它卡片
      ElMessage.error(errMsg(e, `读取${sp.title}配置失败`))
    }
  }
}

async function saveSocial(sp) {
  sp.saving = true
  try {
    const body = {
      enabled: sp.form.enabled,
      [sp.idField]: sp.form.id,
      [sp.secretField]: sp.form.secret || null,
      buttonLabel: sp.form.buttonLabel,
      autoRegister: sp.form.autoRegister
    }
    const { data } = await api.put(`/admin/sso/${sp.key}`, body)
    sp.cfg = data || {}
    sp.form.secret = ''
    ElMessage.success(`${sp.title}已保存`)
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    sp.saving = false
  }
}

function copy(key) {
  const url = `${origin}/api/auth/sso/${key}/callback`
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url).then(
      () => ElMessage.success('已复制回调地址'),
      () => ElMessage.warning(url)
    )
  } else {
    ElMessage.info(url)
  }
}

onMounted(load)
onMounted(loadOidc)
onMounted(loadSocials)
</script>

<style scoped>
.hd { display: flex; align-items: center; gap: 10px; font-weight: 600; }
.tip { color: #6b7280; font-size: 13px; line-height: 1.8; margin: 0 0 18px; }
.hint { color: #9aa0ac; font-size: 12px; margin-top: 4px; }
</style>
