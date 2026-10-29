<template>
  <el-dialog :model-value="modelValue" :title="isFeedback ? '意见反馈' : '举报'" width="480px"
             @update:model-value="$emit('update:modelValue', $event)" @closed="reset">
    <div class="rp">
      <p v-if="targetLabel" class="rp-target">{{ targetLabel }}</p>
      <el-select v-model="reason" placeholder="选择原因" class="rp-sel">
        <el-option v-for="r in reasons" :key="r" :label="r" :value="r" />
      </el-select>
      <el-input v-model="detail" type="textarea" :rows="4" maxlength="2000" show-word-limit
                :placeholder="isFeedback ? '说说你遇到的问题或想要的功能' : '补充说明（可选）'" />
      <p class="rp-tip">提交后由管理员处理；同一对象 10 分钟内只能提交一次。</p>
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!reason" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitReport } from '../api/report'

const props = defineProps({
  modelValue: Boolean,
  targetType: { type: String, default: 'FEEDBACK' },
  targetId: { type: [Number, String], default: null },
  targetLabel: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'submitted'])

const isFeedback = computed(() => props.targetType === 'FEEDBACK')
const reasons = computed(() => (isFeedback.value
  ? ['功能建议', '使用问题', '内容纠错', '其它']
  : ['垃圾广告', '辱骂攻击', '违法违规', '侵权内容', '虚假信息', '其它']))

const reason = ref('')
const detail = ref('')
const saving = ref(false)

function reset() { reason.value = ''; detail.value = '' }

async function submit() {
  saving.value = true
  try {
    await submitReport({
      targetType: props.targetType,
      targetId: props.targetId,
      reason: reason.value,
      detail: detail.value,
    })
    ElMessage.success(isFeedback.value ? '反馈已提交，谢谢' : '举报已提交，我们会尽快处理')
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '提交失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.rp { display: flex; flex-direction: column; gap: 10px; }
.rp-target { margin: 0; font-size: 12.5px; color: #6b7280; }
.rp-sel { width: 100%; }
.rp-tip { margin: 0; font-size: 12px; color: #9ca3af; }
</style>
