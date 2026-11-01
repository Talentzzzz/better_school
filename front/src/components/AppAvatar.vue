<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string | null
    name?: string
    size?: number
  }>(),
  { src: '', name: '', size: 32 }
)

const colors = ['#FF8FA3', '#7FB5FF', '#8FBF9F', '#C9A7EB', '#F5B971']
const bg = computed(() => {
  const s = props.name || 'U'
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return colors[h % colors.length]
})
const initial = computed(() => (props.name || 'U').slice(0, 1))
</script>

<template>
  <div
    class="app-avatar"
    :style="{ width: size + 'px', height: size + 'px' }"
  >
    <img v-if="src" :src="src" alt="" />
    <span v-else class="initial" :style="{ background: bg, fontSize: size * 0.42 + 'px' }">{{
      initial
    }}</span>
  </div>
</template>

<style scoped lang="scss">
.app-avatar {
  position: relative;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .initial {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: #fff;
    font-weight: 600;
  }
}
</style>
