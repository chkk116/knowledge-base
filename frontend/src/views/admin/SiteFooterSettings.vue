<script setup>
/**
 * 页脚设置（站点设置 → 页脚设置）
 *
 * 维护网站底部展示的内容。默认**全部为空**，页脚不渲染 —— 本地开发环境保持干净，
 * 线上部署后由管理员在这里填一次，全站（网页版 + 移动端 + App）生效。
 *
 * 两类字段：
 *   - 备案信息是**固定项**：合规要求 ICP/公安备案号必须展示在页脚底部并链到官方查询系统，
 *     链接与图标由代码自动生成，不做自定义；
 *   - 其余展示内容走 **items**：管理员自行增删，每项 = 展示文字 + 可选链接。
 *     开源仓库、版权、联系方式、友情链接等都在这里加，不再为每种内容预留单独字段。
 *
 * 合规提示：ICP 备案号与域名一一对应，**不可挪用其它域名的备案号**；
 * 填错等同于伪造备案，会影响该备案下的所有网站。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSiteInfoStore, ICP_URL, policeUrl } from '../../store/siteInfo'
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
  // 自定义项：文字必填，链接要么留空要么是 http(s) 开头
  const bad = siteInfo.items.find((it) => !it.name || !it.name.trim())
  if (bad) {
    ElMessage.warning('有自定义内容没填展示文字，请补全或删除该行')
    return
  }
  const badUrl = siteInfo.items.find((it) => it.url && !/^https?:\/\//i.test(it.url.trim()))
  if (badUrl) {
    ElMessage.warning('链接需以 http:// 或 https:// 开头，或留空只显示文字')
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
        网站底部展示的备案信息与自定义内容。留空即不显示；全部为空时页脚整体隐藏（本地开发默认如此）。
        版本号由构建自动生成，无需填写。
      </p>
    </div>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">备案信息（固定项）</span></template>

      <el-form label-width="110px" label-position="left">
        <el-form-item label="显示页脚">
          <el-switch v-model="siteInfo.enabled" />
          <span class="hint">关闭后全站不再显示页脚（含已填写的内容）</span>
        </el-form-item>

        <el-form-item label="ICP 备案号">
          <el-input v-model="siteInfo.icpNo" placeholder="如：京ICP备00000000号-1" maxlength="60" clearable />
          <div class="hint">
            必须与本站<strong>域名</strong>对应的备案号一致（不可挪用其它域名的备案号）；展示时自动链接到工信部查询系统
          </div>
        </el-form-item>

        <el-form-item label="公安备案号">
          <el-input v-model="siteInfo.policeNo" placeholder="如：京公网安备 00000000000000" maxlength="60" clearable />
          <div class="hint">
            链接与图标自动生成（从号码中提取数字作为查询码），无需单独填链接
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header>
        <span class="card-title">展示内容（可自己添加）</span>
      </template>

      <p class="block-sub">
        除了备案号，页脚还要显示什么都可以自己加：每一行 = 展示文字 + 链接。
        填了链接就是可点跳转（如开源仓库），留空则只显示文字（如版权信息、联系方式）。
      </p>

      <div v-if="!siteInfo.items.length" class="empty">还没有添加内容，点下面的「添加一项」开始</div>

      <div v-for="(it, i) in siteInfo.items" :key="i" class="item-row">
        <span class="idx">{{ i + 1 }}</span>
        <el-input
          v-model="it.name"
          class="f-name"
          placeholder="展示文字，如：开源仓库 / © 2025 你的名称"
          maxlength="60"
        />
        <el-input
          v-model="it.url"
          class="f-url"
          placeholder="链接（可留空，留空只显示文字）"
          maxlength="200"
          clearable
        />
        <el-button size="small" :disabled="i === 0" title="上移" @click="siteInfo.moveUp(i)">↑</el-button>
        <el-button size="small" :disabled="i === siteInfo.items.length - 1" title="下移" @click="siteInfo.moveDown(i)">↓</el-button>
        <el-button size="small" type="danger" plain @click="siteInfo.removeItem(i)">删除</el-button>
      </div>

      <div class="actions">
        <el-button @click="siteInfo.addItem()">+ 添加一项</el-button>
      </div>
    </el-card>

    <el-card class="cfg" shadow="never">
      <template #header><span class="card-title">保存</span></template>
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
            <a v-if="siteInfo.icpNo" :href="ICP_URL" target="_blank" rel="noopener">{{ siteInfo.icpNo }}</a>
            <span v-if="siteInfo.icpNo && siteInfo.policeNo" class="pv-sep">|</span>
            <a v-if="siteInfo.policeNo" :href="policeUrl(siteInfo.policeNo)" target="_blank" rel="noopener">
              <img src="/police.png" alt="公安备案" class="pv-ico" />{{ siteInfo.policeNo }}
            </a>
          </div>
          <div class="pv-line">
            <span>知识库 v{{ versionPreview || '—' }}</span>
            <template v-for="(it, i) in siteInfo.items" :key="i">
              <span class="pv-sep">|</span>
              <a v-if="it.url" :href="it.url" target="_blank" rel="noopener">{{ it.name || '（未填写）' }}</a>
              <span v-else>{{ it.name || '（未填写）' }}</span>
            </template>
          </div>
        </template>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.sf-page { max-width: 900px; }
.page-head { margin-bottom: 18px; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; color: var(--el-text-color-primary); }
.page-head .sub { margin: 6px 0 0; font-size: 13px; line-height: 1.7; color: var(--el-text-color-secondary); }
.card-title { font-weight: 600; }
.cfg { margin-bottom: 16px; }
.cfg :deep(.el-form-item) { margin-bottom: 18px; }
.cfg :deep(.el-form-item__content) { display: block; }
.hint { margin-top: 6px; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.block-sub { margin: 0 0 14px; font-size: 12.5px; line-height: 1.7; color: var(--el-text-color-secondary); }
.actions { display: flex; gap: 8px; padding-top: 4px; }

.item-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.item-row .idx {
  width: 20px; flex: none; text-align: center;
  font-size: 12px; color: var(--el-text-color-placeholder);
}
.item-row .f-name { width: 280px; flex: none; }
.item-row .f-url { flex: 1 1 auto; min-width: 0; }
.empty {
  padding: 16px; margin-bottom: 12px;
  border: 1px dashed var(--el-border-color); border-radius: 8px;
  text-align: center; font-size: 12.5px; color: var(--el-text-color-placeholder);
}

.preview-card { margin-top: 0; }
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
