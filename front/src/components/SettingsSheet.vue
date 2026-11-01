<script setup lang="ts">
import { useRouter } from 'vue-router'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()

function open(path: string) {
  emit('close')
  router.push(path)
}
</script>

<template>
  <div class="sheet-mask" @click="emit('close')" />
  <div class="sheet">
    <div class="sheet-title">设置</div>
    <button class="sheet-row" @click="open('/settings/profile')">
      <span>编辑资料</span>
      <span class="sheet-arrow">›</span>
    </button>
    <button class="sheet-row" @click="open('/settings/privacy')">
      <span>隐私设置</span>
      <span class="sheet-arrow">›</span>
    </button>
    <button class="sheet-close" @click="emit('close')">取消</button>
  </div>
</template>

<style scoped lang="scss">
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 300;
  background: rgba(0, 0, 0, 0.4);
}

.sheet {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 0;
  width: min(100%, 480px);
  z-index: 301;
  padding: 16px 12px calc(12px + env(safe-area-inset-bottom));
  border-radius: 14px 14px 0 0;
  background: #fff;
}

.sheet-title {
  padding: 0 6px 12px;
  font-size: 15px;
  font-weight: 600;
  color: $text-1;
}

.sheet-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 50px;
  padding: 0 6px;
  border-bottom: 0.5px solid $line;
  font-size: 15px;
  color: $text-1;

  &:active {
    background: $bg-secondary;
  }
}

.sheet-arrow {
  color: $text-4;
}

.sheet-close {
  width: 100%;
  height: 46px;
  margin-top: 12px;
  border-radius: $radius;
  background: $bg-secondary;
  font-size: 15px;
  font-weight: 500;
  color: $text-1;
}
</style>
