<script setup lang="ts">
import { computed } from 'vue'
import { useToasts } from './Toast'

// 渲染最近的 1 条（审核场景 toast 密度低，单条足够）
const items = useToasts()
const current = computed(() => items.value[items.value.length - 1] ?? null)
</script>

<template>
  <Transition name="toast">
    <div v-if="current" class="toast" :class="current.tone">
      <span class="ic" />
      {{ current.text }}
    </div>
  </Transition>
</template>

<style scoped lang="scss">
.toast {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 9px;
  max-width: 380px;
  padding: 12px 18px;
  border-radius: $radius;
  background: $bg-2;
  border: 1px solid $line;
  box-shadow: $shadow;
  font-size: 13px;
  font-weight: 500;

  .ic {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: $blue;
    flex-shrink: 0;
  }

  &.ok {
    border-color: rgba(46, 194, 126, 0.4);
    .ic {
      background: $green;
    }
  }

  &.warn {
    border-color: rgba(255, 92, 108, 0.45);
    .ic {
      background: $red;
    }
  }
}

.toast-enter-active,
.toast-leave-active {
  transition: 0.28s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(12px);
}
</style>
