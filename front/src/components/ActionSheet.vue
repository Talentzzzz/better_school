<script setup lang="ts">
defineProps<{ danger: string; cancel?: string }>()
const emit = defineEmits<{ danger: []; cancel: [] }>()
</script>

<template>
  <div class="sheet-mask" @click="emit('cancel')" />
  <div class="action-sheet safe-bottom">
    <button class="sheet-item sheet-item--danger" @click="emit('danger')">{{ danger }}</button>
    <button class="sheet-item" @click="emit('cancel')">{{ cancel || '取消' }}</button>
  </div>
</template>

<style scoped lang="scss">
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgba(0, 0, 0, 0.4);
}

.action-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 201;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  background: $bg-secondary;
  border-radius: 14px 14px 0 0;
}

.sheet-item {
  display: block;
  width: 100%;
  height: 48px;
  border-radius: $radius;
  background: #fff;
  font-size: 15px;
  color: $text-1;
  text-align: center;

  &:active {
    background: $bg-secondary;
  }

  &--danger {
    color: $danger;
    margin-bottom: 8px;
  }
}
</style>
