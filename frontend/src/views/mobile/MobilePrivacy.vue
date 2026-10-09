<script setup>
/**
 * 移动端隐私政策页（本地内置，离线可读）
 *
 * 结构参照主流应用商店对「隐私政策」的要求：
 *   引言 → 收集哪些信息（清单表）→ 怎么用 → 怎么存 → 与谁共享（第三方清单）
 *   → 系统权限说明 → 你的权利 → 未成年人 → 政策更新 → 联系我们
 *
 * 内容必须与 App 的实际行为一致，改动功能（新增采集/新增第三方/新增权限）时同步更新本页。
 * 运营方正式对外发布前，请补齐【】中的主体信息与联系方式。
 */
import { useRouter } from 'vue-router'
const router = useRouter()
const UPDATED = '2026-10-09'
const EFFECTIVE = '2026-10-01'
</script>

<template>
  <div class="pp">
    <header class="pp-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="pp-title">隐私政策</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Document /></el-icon></button>
    </header>

    <article class="pp-body">
      <p class="pp-update">最近更新：{{ UPDATED }}　生效日期：{{ EFFECTIVE }}</p>

      <h3>引言</h3>
      <p>
        本政策由<b>【运营主体：周顺】</b>（以下称"我们"）制定，适用于"知识库"应用（以下称"本应用"）。
        我们深知个人信息对你的重要性，将按照《中华人民共和国个人信息保护法》《网络安全法》等法律法规的要求，
        采取相应的安全保护措施。请你在使用本应用前仔细阅读并理解本政策。
      </p>

      <h3>一、我们收集哪些信息</h3>
      <p>我们仅收集实现产品功能所必需的信息，具体如下：</p>

      <table class="pp-table">
        <thead><tr><th>信息类型</th><th>具体内容</th><th>使用目的</th></tr></thead>
        <tbody>
          <tr>
            <td>账号信息</td>
            <td>用户名、密码、昵称、头像</td>
            <td>创建账号、登录验证、身份展示</td>
          </tr>
          <tr>
            <td>你创建的内容</td>
            <td>文档、图片、音视频、评论、收藏、笔记</td>
            <td>为你提供存储、检索与协作功能</td>
          </tr>
          <tr>
            <td>使用记录</td>
            <td>浏览历史、收藏、点赞、积分、学习进度</td>
            <td>个性化展示、积分与排行</td>
          </tr>
          <tr>
            <td>设备与日志</td>
            <td>设备类型、登录时间、IP 地址、操作记录</td>
            <td>安全风控、故障排查、审计合规</td>
          </tr>
        </tbody>
      </table>

      <p class="pp-tip">
        <b>密码</b>以加盐哈希方式存储，我们不保存也无法还原你的明文密码。
      </p>

      <h3>二、我们如何使用这些信息</h3>
      <ul>
        <li>提供、维护和改进本应用的核心功能（知识库检索、文档协作、培训答题等）；</li>
        <li>进行身份验证与安全防护，识别异常登录与违规行为；</li>
        <li>为你提供 AI 问答等智能化服务（详见下方"第三方服务"）；</li>
        <li>履行法律法规规定的义务。</li>
      </ul>
      <p><b>我们不会</b>将你的个人信息用于广告画像，也<b>不会</b>出售你的个人信息。</p>

      <h3>三、信息如何存储与保护</h3>
      <ul>
        <li><b>存储地点</b>：数据默认存储于<b>中华人民共和国境内</b>的服务器；若管理员配置了境外模型服务，AI 功能的相关内容可能传至对应服务商，届时将在功能内另行提示。</li>
        <li><b>传输安全</b>：所有网络通信均通过 HTTPS 加密通道进行。</li>
        <li><b>存储安全</b>：密码与敏感密钥加密存储；实行最小权限访问控制与操作审计。</li>
        <li><b>保存期限</b>：账号存续期间持续保存；你注销账号后按下方"你的权利"处理。
          登录日志（时间与 IP，不含密码）出于安全审计需要会保留一定期限。</li>
      </ul>

      <h3>四、我们如何共享、转让、公开披露信息</h3>
      <p>我们不会向第三方出售你的个人信息。仅在以下情形共享：</p>
      <ul>
        <li>获得你的<b>明确同意</b>；</li>
        <li>依据法律法规、司法或行政机关的强制性要求；</li>
        <li>为保护本应用及用户的合法权益、公共安全所必需。</li>
      </ul>

      <h3>五、第三方服务</h3>
      <p>为实现特定功能，本应用会调用以下第三方能力。<b>相关信息仅在该功能被使用时才会传输</b>：</p>

      <table class="pp-table">
        <thead><tr><th>服务</th><th>使用场景</th><th>传输内容</th></tr></thead>
        <tbody>
          <tr>
            <td>AI 大模型服务<br /><span class="pp-sub">（由管理员配置，如智谱 GLM、DeepSeek 等）</span></td>
            <td>使用 AI 问答 / 文档辅助 / 云端图片识别时</td>
            <td>你的提问、附件内容、相关文档片段</td>
          </tr>
          <tr>
            <td>本机离线识别<br /><span class="pp-sub">（Tesseract 引擎，随应用内置）</span></td>
            <td>使用「拍照建文档」的离线识别时</td>
            <td><b>不上传</b>，图片仅在本机处理</td>
          </tr>
        </tbody>
      </table>

      <p class="pp-tip">
        使用 AI 功能时，请勿提交身份证号、银行卡号、健康信息等敏感个人信息。
      </p>

      <h3>六、系统权限说明</h3>
      <ul>
        <li><b>相机</b>：仅在你主动使用「拍照建文档」时调用，用于拍摄待识别的文档；</li>
        <li><b>存储 / 文件</b>：用于选择并上传文件、保存你下载的内容；</li>
        <li><b>网络</b>：用于与服务端通信、同步数据。</li>
      </ul>
      <p>以上权限均可在系统设置中随时关闭；关闭后对应功能将不可用，但不影响其他功能。</p>

      <h3>七、你的权利</h3>
      <ul>
        <li><b>查阅与更正</b>：在「我的 → 账号与资料」中查看和修改个人资料。</li>
        <li><b>删除</b>：你可以删除自己创建的文档、评论、文件等内容。</li>
        <li><b>注销账号</b>：在「设置 → 注销账号」中申请（需密码确认）。
          注销后你的个人数据（AI 会话、通知、收藏、点赞、订阅、上传文件等）将被删除；
          已发布的公共内容将匿名显示为「已注销用户」；登录日志按安全审计需要保留。
          <span class="pp-sub">注：演示账号为公共账号，不支持注销；唯一的管理员账号需先创建其他管理员后方可注销。</span>
        </li>
        <li><b>撤回同意</b>：你可以通过停止使用相关功能或关闭系统权限撤回授权。</li>
        <li><b>投诉举报</b>：可通过下方联系方式向我们反馈。</li>
      </ul>

      <h3>八、未成年人保护</h3>
      <p>
        本应用主要面向成人用户。若你是不满 14 周岁的儿童，请在监护人陪同下阅读本政策，并在监护人同意后使用本应用。
        若我们发现在未事先获得监护人同意的情况下收集了儿童个人信息，会尽快予以删除。
      </p>

      <h3>九、本政策的更新</h3>
      <p>
        我们可能适时修订本政策。涉及重大变更（如新增采集范围、新增第三方共享）时，
        我们会通过应用内公告等显著方式提示你。继续使用即表示你接受更新后的政策。
      </p>

      <h3>十、联系我们</h3>
      <p>
        如你对本政策或个人信息保护有任何疑问、意见或投诉，可通过以下方式联系我们：
      </p>
      <ul>
        <li>运营主体：<b>【周顺】</b></li>
        <li>联系邮箱：<b>【请填写】</b></li>
        <li>备案号：湘 ICP 备 2024087310 号-2</li>
      </ul>
      <p>我们将在收到你的请求后 15 个工作日内予以答复。</p>

      <p class="pp-note">
        本页为应用内展示版本，便于离线查阅；与官方网站、应用商店发布的正式版本具有同等效力。
      </p>
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
.pp-body h3 { font-size: 15.5px; margin: 24px 0 8px; color: var(--m-text); }
.pp-body p { margin: 8px 0; }
.pp-body ul { padding-left: 20px; margin: 8px 0; }
.pp-body li { margin: 6px 0; }
.pp-sub { color: var(--m-text-dim); font-size: 12.5px; }

/* 信息清单表：窄屏可横向滑动 */
.pp-table {
  display: block; width: fit-content; max-width: 100%;
  overflow-x: auto; -webkit-overflow-scrolling: touch;
  border-collapse: collapse; margin: 10px 0; font-size: 13px;
}
.pp-table th, .pp-table td {
  border: 1px solid var(--m-border); padding: 7px 9px; text-align: left;
  vertical-align: top; white-space: nowrap;
}
.pp-table td:nth-child(2), .pp-table td:nth-child(3) { white-space: normal; min-width: 96px; }
.pp-table th { background: var(--m-card-2); font-weight: 600; }

.pp-tip {
  padding: 10px 12px; border-radius: var(--m-radius);
  background: var(--m-card-2); color: var(--m-text-sub); font-size: 13px;
}
.pp-note {
  margin-top: 18px; padding: 12px;
  background: var(--m-card); border-radius: var(--m-radius);
  color: var(--m-text-dim); font-size: 12.5px;
}
</style>
