<script setup lang="ts">
import { ref } from 'vue'
import AppImage from '@/components/AppImage.vue'

const props = withDefaults(
  defineProps<{
    images: string[]
    /** AppImage 占位渐变 seed 前缀（每张拼上序号） */
    seed?: string | number
    /** 每张比例，默认 4:3 */
    ratio?: string
  }>(),
  { seed: 0, ratio: '4:3' }
)

const index = ref(0)
const dragStartX = ref<number | null>(null)
const DRAG_THRESHOLD = 40

function onDown(e: PointerEvent) {
  dragStartX.value = e.clientX
}
function onUp(e: PointerEvent) {
  if (dragStartX.value === null) return
  const dx = e.clientX - dragStartX.value
  dragStartX.value = null
  if (Math.abs(dx) < DRAG_THRESHOLD) return
  go(index.value + (dx < 0 ? 1 : -1))
}
function go(i: number) {
  index.value = Math.min(Math.max(i, 0), props.images.length - 1)
}
</script>

<template>
  <section
    v-if="images.length"
    class="carousel"
    @pointerdown="onDown"
    @pointerup="onUp"
    @pointercancel="dragStartX = null"
    @pointerleave="dragStartX = null"
  >
    <div class="track" :style="{ transform: `translateX(-${index * 100}%)` }">
      <div v-for="(img, i) in images" :key="i" class="slide">
        <AppImage :src="img" :seed="`${seed}-${i}`" :ratio="ratio" />
      </div>
    </div>
    <template v-if="images.length > 1">
      <div class="dots">
        <button
          v-for="n in images.length"
          :key="n"
          class="dot"
          :class="{ on: n - 1 === index }"
          :aria-label="`切换到第 ${n} 张`"
          @click="go(n - 1)"
        />
      </div>
      <span class="counter">{{ index + 1 }}/{{ images.length }}</span>
    </template>
  </section>
</template>

<style scoped lang="scss">
.carousel {
  position: relative;
  overflow: hidden;
  background: $bg-page-alt;
  touch-action: pan-y;
  user-select: none;

  /* 图片本身不吃指针：否则桌面拖动会触发浏览器原生图片拖拽（dragstart→pointercancel），滑动失效 */
  :deep(img) {
    pointer-events: none;
    -webkit-user-drag: none;
  }
}

.track {
  display: flex;
  transition: transform 0.28s ease;
}

.slide {
  flex: 0 0 100%;
  min-width: 100%;
}

.dots {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 6px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: 0 0 2px rgba(0, 0, 0, 0.25);

  &.on {
    background: #fff;
  }
}

.counter {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 2px 8px;
  border-radius: $radius-full;
  background: rgba(0, 0, 0, 0.4);
  color: #fff;
  font-size: 11px;
}
</style>
