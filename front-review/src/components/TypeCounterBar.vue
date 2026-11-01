<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useConsoleStore } from '@/stores/console'
import { TYPES } from '@/types/review'
import { formatWait } from '@/utils/format'

const store = useConsoleStore()

/** 四类型计数（含 0，保持「在审四类内容」的可读性）；数据来自 GET /tasks/counts，只读、不可点 */
const items = computed(() =>
  TYPES.map((t) => ({ key: t.key, label: t.label, color: t.color, count: store.counts[t.key] }))
)

/** 最早已等待：以 store.oldestEnteredAt 为锚点，本地 30s 心跳刷新展示 */
const now = ref(Date.now())
const tick = window.setInterval(() => (now.value = Date.now()), 30_000)
onBeforeUnmount(() => clearInterval(tick))

const oldest = computed(() =>
  store.oldestEnteredAt ? formatWait(now.value - store.oldestEnteredAt) : '—'
)
</script>

<template>
  <div class="type-bar">
    <span class="bar-label">待审积压</span>
    <span class="bar-total">{{ store.backlogTotal }}</span>

    <div class="chips">
      <span v-for="t in items" :key="t.key" class="chip" :class="{ zero: t.count === 0 }">
        <span class="dot" :style="{ background: t.color }" />
        {{ t.label }}
        <b>{{ t.count }}</b>
      </span>
    </div>

    <span class="oldest">最早已等待 {{ oldest }}</span>
  </div>
</template>

<style scoped lang="scss">
.type-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 18px 0;
  padding: 0 14px;
  height: 34px;
  border-radius: $radius;
  background: $bg-1;
  border: 1px solid $line-soft;
  font-size: 12px;
}

.bar-label {
  color: $text-3;
}

.bar-total {
  min-width: 22px;
  height: 19px;
  padding: 0 7px;
  border-radius: $radius-full;
  background: $brand-soft;
  color: $brand;
  font-size: 11px;
  font-weight: 700;
  display: inline-grid;
  place-items: center;
}

.chips {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 9px;
  border-radius: $radius-full;
  background: $bg-2;
  color: $text-2;
  white-space: nowrap;

  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  b {
    color: $text-1;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  &.zero {
    opacity: 0.42;

    b {
      color: $text-3;
      font-weight: 500;
    }
  }
}

.oldest {
  margin-left: auto;
  flex-shrink: 0;
  color: $text-3;
  font-size: 11px;
}
</style>
