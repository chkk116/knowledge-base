<script setup>
/**
 * 页脚设置（站点设置 → 页脚设置）
 *
 * 维护网站底部展示的备案信息 / 开源仓库 / 版权。
 * 默认**全部为空**，页脚不渲染 —— 本地开发环境保持干净，
 * 线上部署后由管理员在这里填一次，全站（网页版 + 移动端 + App）生效。
 *
 * 合规提示：ICP 备案号与域名一一对应，**不可挪用其它域名的备案号**；
 * 填错等同于伪造备案，会影响该备案下的所有网站。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSiteInfoStore } from '../../store/siteInfo'
import { APP_VERSION } from '../../utils/appInfo'

const siteInfo = useSiteInfoStore()
const saving = ref(false)

onMounted(() => { if (!siteInfo.loaded) siteInfo.load() })

/** 预览用的版本号（与构建期注入的一致） */
const versionPreview = APP_VERSION

async function save() {
  // 备案号形如「京ICP备00000000号-1」，只做轻校验，避免挡住正常值
  if (siteInfo.icpNo && !/ICP备|ICP证|备案/.test(siteInfo.icpNo)) {
    ElMessage.warning('ICP 备案号格式看起来不对，请核对后再保存')
    return
  }
  saving.value = true
  try {
    await siteInfo.save()
    ElMessage.success('页脚配置已保存，全站已生效')
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

async function clearAll() {
  siteInfo.reset()
  ElMessage.info('已清空表单，点击「保存」后生效')
}
</script>

<template>
  <div class="sf-page">
    <div class="page-head">
      <h2>页脚设置</h2>
      <p class="sub">
        网站底部展示的备案信息、开源仓库与版权。留空即不显示；三项全空时页脚整体隐藏（本地开发默认如此）。
        版本号由构建自动生成，无需填写。
      </p>
    </div>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">展示内容</span></template>

      <el-form label-width="110px" label-position="left">
        <el-form-item label="显示页脚">
          <el-switch v-model="siteInfo.enabled" />
          <span class="hint">关闭后全站不再显示页脚（含已填写的内容）</span>
        </el-form-item>

        <el-divider />

        <el-form-item label="ICP 备案号">
          <el-input v-model="siteInfo.icpNo" placeholder="如：京ICP备00000000号-1" maxlength="60" clearable />
          <div class="hint">
            必须与本站**域名**对应的备案号一致（不可挪用其它域名的备案号）；展示时自动链接到工信部查询系统
          </div>
        </el-form-item>

        <el-form-item label="公安备案号">
          <el-input v-model="siteInfo.policeNo" placeholder="如：京公网安备 00000000000000" maxlength="60" clearable />
          <div class="hint">
            链接与图标自动生成（从号码中提取数字作为查询码），无需单独填链接
          </div>
        </el-form-item>

        <el-form-item label="开源仓库（可选）">
          <el-input v-model="siteInfo.giteeUrl" placeholder="如：https://gitee.com/your-name/your-repo" clearable />
          <div class="hint">自己的代码仓库地址，留空则不显示</div>
        </el-form-item>

        <el-form-item label="版权信息（可选）">
          <el-input v-model="siteInfo.copyright" placeholder="如：© 2025 你的名称" maxlength="60" clearable />
          <div class="hint">留空则不显示</div>
        </el-form-item>
      </el-form>

      <div class="actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="clearAll">清空</el-button>
        <el-button text @click="siteInfo.load()">重新读取</el-button>
      </div>
    </el-card>

    <el-card class="preview-card" shadow="never">
      <template #header><span class="card-title">效果预览</span></template>
      <div class="preview">
        <div v-if="!siteInfo.visible" class="pv-empty">当前配置为空，页脚不显示</div>
        <template v-else>
          <div v-if="siteInfo.icpNo || siteInfo.policeNo" class="pv-line">
            <a v-if="siteInfo.icpNo" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">{{ siteInfo.icpNo }}</a>
            <span v-if="siteInfo.icpNo && siteInfo.policeNo" class="pv-sep">|</span>
            <a v-if="siteInfo.policeNo" href="https://beian.mps.gov.cn/" target="_blank" rel="noopener">
              <img src="/police.png" alt="公安备案" class="pv-ico" />{{ siteInfo.policeNo }}
            </a>
          </div>
          <div class="pv-line">
            <span>知识库 v{{ versionPreview || '—' }}</span>
            <template v-if="siteInfo.giteeUrl"><span class="pv-sep">|</span><span>开源仓库</span></template>
            <template v-if="siteInfo.copyright"><span class="pv-sep">|</span><span>{{ siteInfo.copyright }}</span></template>
          </div>
        </template>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.sf-page { max-width: 860px; }
.page-head { margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; color: var(--el-text-color-primary); }
.page-head .sub { margin: 6px 0 0; font-size: 13px; line-height: 1.7; color: var(--el-text-color-secondary); }
.card-title { font-weight: 600; }
.cfg :deep(.el-form-item) { margin-bottom: 18px; }
.cfg :deep(.el-form-item__content) { display: block; }
.hint { margin-top: 6px; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.actions { display: flex; gap: 8px; padding-top: 4px; }

.preview-card { margin-top: 16px; }
.preview {
  padding: 14px 8px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  text-align: center;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}
.pv-line { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 6px; }
.pv-line a { display: inline-flex; align-items: center; gap: 4px; color: var(--el-text-color-secondary); text-decoration: none; }
.pv-line a:hover { color: var(--el-color-primary); }
.pv-sep { color: var(--el-border-color); }
.pv-ico { width: 15px; height: 16px; }
.pv-empty { color: var(--el-text-color-placeholder); }
</style>
