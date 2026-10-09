<script setup>
/**
 * Web 端免责声明页（公网可访问，登录页与页脚均可跳转）
 *
 * 内容与移动端 MobileDisclaimer.vue 保持一致 —— 改动其一时请同步另一处。
 * ⚠️ 本页为通用模板，正式对外前建议请法务按实际业务复核。
 */
const UPDATED = '2026-10-09'
const EFFECTIVE = '2026-10-01'
</script>

<template>
  <div class="pp-page">
    <article class="pp-card">
      <h1>免责声明</h1>
      <p class="pp-update">最近更新：{{ UPDATED }}　生效日期：{{ EFFECTIVE }}</p>

      <h3>引言</h3>
      <p>
        本声明适用于"知识库"网站（www.chkk116.cloud）与"知识库"应用（以下合称"本站"），
        由<b>本站运营者</b>（以下称"我们"）制定。你访问或使用本站服务，即表示已阅读、理解并同意本声明。
        本声明与<a href="/privacy" target="_blank" rel="noopener noreferrer">《隐私政策》</a>共同构成你与本站之间的约定。
      </p>

      <h3>一、内容仅供参考</h3>
      <p>
        本站内容由运营者或用户提供，仅用于信息交流与学习参考，<b>不构成任何专业意见</b>
        （包括但不限于法律、医疗、投资、财务、税务等）。依据本站内容作出的任何决定及其后果，由你自行承担。
      </p>

      <h3>二、AI 生成内容</h3>
      <p>本站提供 AI 问答、AI 辅助写作与图像识别等功能，请特别注意：</p>
      <ul>
        <li>AI 生成的内容<b>可能存在错误、过时、片面或不完整</b>，我们不对其准确性、完整性作任何保证；</li>
        <li>AI 输出不代表本站立场，<b>不应作为决策的唯一依据</b>；涉及重要事项请以权威来源或专业人士意见为准；</li>
        <li>不得利用 AI 功能生成、传播违法违规、侵犯他人权益或误导性内容。</li>
      </ul>

      <h3>三、用户上传与发布的内容</h3>
      <ul>
        <li>用户对其上传或发布的文档、图片、音视频等内容<b>独立承担全部责任</b>，须保证拥有相应权利且不侵犯他人合法权益；</li>
        <li>本站不对用户内容做实质性审查，但有权对涉嫌违规内容采取删除、屏蔽、限制功能等措施；</li>
        <li>权利人认为站内内容侵犯其合法权益的，可通过下方联系方式通知我们，我们将依法及时处理（通知—删除）。</li>
      </ul>

      <h3>四、服务可用性</h3>
      <p>
        本站服务按"现状"和"现有"基础提供。我们<b>不保证服务不间断、无错误或绝对安全</b>；
        因系统维护、升级、故障、网络中断、不可抗力或第三方服务异常导致的服务中断、延迟或功能受限，我们不承担责任。
      </p>

      <h3>五、数据与备份</h3>
      <p>
        我们已采取合理的技术与管理措施保护数据安全，但<b>不对数据丢失作绝对保证</b>。
        请你对重要资料自行另行备份。因用户误操作、设备损坏、不可抗力等造成的数据丢失，我们不承担赔偿责任。
      </p>

      <h3>六、第三方链接与服务</h3>
      <p>
        本站可能包含指向第三方网站的链接，或接入第三方服务（如 AI 模型服务、云存储）。
        我们不对第三方的内容、隐私做法或可用性负责；访问或使用前请自行判断并遵守其条款。
      </p>

      <h3>七、开源软件</h3>
      <p>
        本应用的部分代码基于 AGPL-3.0 等开源许可证发布，相关权利与义务以对应开源许可证的规定为准。
      </p>

      <h3>八、责任限制</h3>
      <p>
        在法律允许的最大范围内，对因使用或无法使用本站服务所产生的间接损失、附带损失、后果性损失或利润损失，
        我们不承担赔偿责任。本条不排除或限制依法不能排除或限制的责任。
      </p>

      <h3>九、声明的变更</h3>
      <p>
        我们可能适时修订本声明。涉及重大变更时，我们会通过站内公告等显著方式提示你。继续使用即表示你接受修订后的声明。
      </p>

      <h3>十、联系我们</h3>
      <p>如对本声明有任何疑问、意见或投诉，可通过以下方式联系我们：</p>
      <ul>
        <li>运营主体：<b>本站运营者</b></li>
        <li>联系邮箱：<b>zxc1275981466@qq.com</b></li>
      </ul>

      <p class="pp-tip">
        本页为通用免责声明模板，具体权利义务以法律法规及你与本站的实际约定为准。
      </p>
    </article>
  </div>
</template>

<style scoped>
.pp-page { padding: 28px 16px 40px; }
.pp-card {
  max-width: 820px; margin: 0 auto; padding: 32px 36px;
  background: #fff; border-radius: 14px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  color: var(--el-text-color-primary, #303133);
  font-size: 14px; line-height: 1.8;
}
html.dark .pp-card { background: #1b1b22; border-color: #2c2c34; color: #d5d7e0; }
.pp-card h1 { font-size: 22px; margin: 0 0 6px; }
.pp-update { color: var(--el-text-color-secondary, #909399); font-size: 13px; margin: 0 0 4px; }
.pp-card h3 { font-size: 16px; margin: 26px 0 8px; }
.pp-card p { margin: 8px 0; }
.pp-card ul { padding-left: 22px; margin: 8px 0; }
.pp-card li { margin: 6px 0; }
.pp-card a { color: var(--el-color-primary, #3b6fe0); }
.pp-tip {
  margin-top: 22px;
  padding: 10px 12px; border-radius: 8px; font-size: 13px;
  background: var(--el-fill-color-light, #f5f7fa); color: var(--el-text-color-secondary, #909399);
}
@media (max-width: 640px) {
  .pp-card { padding: 20px 16px; }
}
</style>
