<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 无图占位：4 组渐变按 seed 哈希取一（文案居中显示）
 */
const GRADIENTS = [
  'linear-gradient(135deg, #FFE3E8 0%, #FFF3D6 100%)',
  'linear-gradient(135deg, #D9ECFF 0%, #E8F6E9 100%)',
  'linear-gradient(135deg, #EAD9FF 0%, #FFE9F0 100%)',
  'linear-gradient(135deg, #FFF0D9 0%, #FFDBDB 100%)'
]

const props = withDefaults(
  defineProps<{
    src?: string | null
    seed?: string | number
    text?: string
    ratio?: string // '3:4' | '1:1' | '4:3'，默认 3:4
    /** 按图片真实宽高比自适应（加载前用 ratio 兜底），极限比例收敛到 0.6~1.5 */
    dynamic?: boolean
  }>(),
  { src: '', seed: 0, text: '', ratio: '3:4', dynamic: false }
)

const failed = ref(false)
const natural = ref<number | null>(null) // 实际高/宽

watch(
  () => props.src,
  () => {
    failed.value = false
    natural.value = null
  }
)

function onImgLoad(e: Event) {
  if (!props.dynamic) return
  const img = e.target as HTMLImageElement
  if (img.naturalWidth) {
    const r = img.naturalHeight / img.naturalWidth
    natural.value = Math.min(Math.max(r, 0.6), 1.5)
  }
}

const showImage = computed(() => !!props.src && !failed.value)
const boxRatio = computed(() => {
  const [w, h] = props.ratio.split(':').map(Number)
  const hOverW = props.dynamic && natural.value !== null ? natural.value : h / w
  return `1 / ${hOverW}`
})
const gradient = computed(() => {
  const s = String(props.seed)
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return GRADIENTS[h % GRADIENTS.length]
})
</script>

<template>
  <div class="app-image" :style="{ background: gradient, aspectRatio: boxRatio }">
    <img v-if="showImage" :src="src ?? ''" loading="lazy" @error="failed = true" @load="onImgLoad" />
    <span v-else class="app-image-placeholder">{{ text }}</span>
  </div>
</template>

<style scoped lang="scss">
.app-image {
  position: relative;
  width: 100%;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.app-image-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(28, 28, 30, 0.35);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 1px;
}
</style>
