<script setup lang="ts">
import { useToasts } from './Toast'

const toasts = useToasts()
</script>

<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast-item">{{ t.text }}</div>
    </TransitionGroup>
  </div>
</template>

<style scoped lang="scss">
.toast-host {
  position: fixed;
  top: 60px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 999;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: min(100%, 360px);
  pointer-events: none;
}

.toast-item {
  max-width: 100%;
  padding: 8px 16px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.82);
  color: #fff;
  font-size: 13px;
  text-align: center;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.2s;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
