<script setup lang="ts">
withDefaults(
  defineProps<{
    text: string
    buttonText?: string
  }>(),
  { buttonText: '' }
)

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <div class="empty-state">
    <svg class="empty-illu" viewBox="0 0 120 100" width="120" height="100" fill="none">
      <!-- 参考图：灰色线稿气泡插画 -->
      <circle cx="55" cy="45" r="30" stroke="#E0E0E0" stroke-width="2.5" />
      <circle cx="82" cy="38" r="18" stroke="#E8E8E8" stroke-width="2.5" />
      <path d="M40 45c-4-6-15 0-4 0" stroke="#E0E0E0" stroke-width="2.5" stroke-linecap="round" />
      <circle cx="47" cy="42" r="3" fill="#D8D8D8" />
      <circle cx="65" cy="42" r="3" fill="#D8D8D8" />
      <path d="M50 56c4 3 9 3 13 0" stroke="#E0E0E0" stroke-width="2.5" stroke-linecap="round" />
      <path d="M30 78l-6 8M90 78l6 8" stroke="#E8E8E8" stroke-width="2.5" stroke-linecap="round" />
    </svg>
    <div class="empty-text">{{ text }}</div>
    <button v-if="buttonText" class="btn-outline" @click="emit('click')">{{ buttonText }}</button>
  </div>
</template>

<style scoped lang="scss">
.empty-illu {
  margin-bottom: 16px;
}
</style>
