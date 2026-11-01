<script setup lang="ts">
import { useConfirm } from './ConfirmDialog'

const state = useConfirm()

function done(v: boolean) {
  state.value.resolve?.(v)
  state.value = { open: false, title: '', message: '', resolve: null }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="state.open" class="confirm-mask" @click.self="done(false)">
      <div class="confirm-box">
        <div class="confirm-title">{{ state.title }}</div>
        <div v-if="state.message" class="confirm-message">{{ state.message }}</div>
        <div class="confirm-actions">
          <button class="confirm-btn cancel" @click="done(false)">取消</button>
          <button class="confirm-btn ok" @click="done(true)">确定</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped lang="scss">
.confirm-mask {
  position: fixed;
  inset: 0;
  z-index: 998;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.4);
}

.confirm-box {
  width: min(300px, 82vw);
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
}

.confirm-title {
  padding: 20px 20px 8px;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
}

.confirm-message {
  padding: 0 20px 16px;
  text-align: center;
  font-size: 13px;
  color: $text-2;
}

.confirm-actions {
  display: flex;
  border-top: 0.5px solid $line;
}

.confirm-btn {
  flex: 1;
  height: 46px;
  font-size: 15px;

  &.cancel {
    color: $text-2;
  }

  &.ok {
    color: $primary;
    font-weight: 600;
  }
}
</style>
