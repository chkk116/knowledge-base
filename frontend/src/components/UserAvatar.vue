<template>
  <span class="uav" :style="boxStyle" :title="name">
    <img v-if="showImg" :src="src" alt="" @error="broken = true" />
    <span v-else class="uav-txt">{{ initial }}</span>
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: Number, default: 34 },
})

const broken = ref(false)
watch(() => props.src, () => { broken.value = false })

// 外部「默认头像生成器」不算真实头像：国内访问慢且常显示成奇怪图案，直接用首字母
const isDefaultGen = computed(() => /dicebear\.com|no_portrait\.png/i.test(props.src || ''))
const showImg = computed(() => !!props.src && !broken.value && !isDefaultGen.value)
const initial = computed(() => (props.name || '?').trim().slice(0, 1).toUpperCase())

const PALETTE = ['#1677ff', '#13c2c2', '#52c41a', '#fa8c16', '#eb2f96', '#722ed1', '#2f54eb', '#fa541c']
const color = computed(() => {
  const s = props.name || ''
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
})

const boxStyle = computed(() => ({
  width: props.size + 'px',
  height: props.size + 'px',
  background: showImg.value ? '#f2f3f5' : color.value,
  fontSize: Math.round(props.size * 0.42) + 'px',
}))
</script>

<style scoped>
.uav {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  overflow: hidden;
  flex: 0 0 auto;
  color: #fff;
  font-weight: 600;
  line-height: 1;
  user-select: none;
}
.uav img { width: 100%; height: 100%; object-fit: cover; display: block; }
.uav-txt { transform: translateY(0.5px); }
</style>
