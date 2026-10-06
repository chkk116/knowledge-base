<script setup>
/**
 * 移动端隐私政策页（本地内置，离线可读）
 *
 * 内容覆盖本 App 实际的数据处理行为，供 Google Play「Data safety」与应用内展示使用。
 * 运营方如需对外正式发布，可在此基础上补充公司主体、联系方式、生效日期等。
 */
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="pp">
    <header class="pp-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="pp-title">隐私政策</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Document /></el-icon></button>
    </header>

    <article class="pp-body">
      <p class="pp-update">最近更新：2026-10-06</p>

      <h3>一、我们收集的信息</h3>
      <p>为向你提供知识库服务，我们会在你注册、使用过程中收集以下信息：</p>
      <ul>
        <li><b>账号信息</b>：注册时提供的用户名、密码（以加盐哈希存储，服务端不保存明文）、昵称、头像、邮箱等资料。</li>
        <li><b>你创建的内容</b>：你上传、编辑的文档、图片、音视频、评论、收藏、题库与课程等，存储于你所属的服务器。</li>
        <li><b>本地识别数据</b>：当你使用「拍照建文档」的离线 OCR 时，图片仅在<b>本机设备</b>上由 Tesseract 引擎识别，默认不会上传原图；识别结果是否上传由你主动决定。</li>
        <li><b>使用日志</b>：登录时间、操作审计、设备类型等用于安全风控与故障排查，不会用于广告画像。</li>
      </ul>

      <h3>二、信息的存储与安全</h3>
      <p>所有网络传输均通过 <b>HTTPS 加密</b>通道进行；密码与敏感密钥以加密形式存储。我们采取访问控制、操作审计等措施保护你的数据。</p>

      <h3>三、信息的共享</h3>
      <p>我们<b>不会</b>将你的个人信息出售或共享给第三方广告商。仅在以下情形可能披露：</p>
      <ul>
        <li>获得你的明确同意；</li>
        <li>法律法规或司法/监管部门要求；</li>
        <li>为保护本平台及用户的合法权益所必需。</li>
      </ul>

      <h3>四、你的权利</h3>
      <ul>
        <li><b>查阅与更正</b>：你可随时在「我的 → 账号与资料」中查看和修改个人资料。</li>
        <li><b>注销账号</b>：你可在「设置 → 注销账号」中申请删除你的账号及关联的个人数据，删除后相关个人信息将无法恢复。</li>
        <li><b>数据导出</b>：你可下载或复制自己创建的内容。</li>
      </ul>

      <h3>五、未成年人</h3>
      <p>本服务主要面向成人用户。若你为未成年人，请在监护人指导下使用。</p>

      <h3>六、联系方式</h3>
      <p>如你对本隐私政策有任何疑问，可通过应用内反馈或运营方公布的联系方式与我们联系。</p>

      <p class="pp-note">本政策仅描述本 App 的技术与产品行为。最终以运营方在应用商店与官方网站发布的正式版本为准。</p>
    </article>

    <div style="height:24px"></div>
  </div>
</template>

<style scoped>
.pp { min-height: 100%; background: var(--m-bg); }
.pp-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.pp-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.pp-body {
  padding: 8px 20px 0;
  font-size: 14px; line-height: 1.75; color: var(--m-text);
}
.pp-update { color: var(--m-text-dim); font-size: 12.5px; }
.pp-body h3 { font-size: 15.5px; margin: 22px 0 8px; color: var(--m-text); }
.pp-body p { margin: 8px 0; }
.pp-body ul { padding-left: 20px; margin: 8px 0; }
.pp-body li { margin: 6px 0; }
.pp-note {
  margin-top: 18px; padding: 12px;
  background: var(--m-card); border-radius: var(--m-radius);
  color: var(--m-text-dim); font-size: 12.5px;
}
</style>
