<script lang="ts">
/** 四格去向的键（go 事件的载荷；调用方用它决定跳哪） */
export type EntryKey = 'orders' | 'favorites' | 'messages' | 'activity'
</script>

<script setup lang="ts">
withDefaults(defineProps<{ unread?: number }>(), { unread: 0 })
defineEmits<{ go: [key: EntryKey] }>()

const items = [
  { key: 'orders', label: '我的订单', tint: '#ffe7ec', color: '#ff2442', icon: 'receipt' },
  { key: 'favorites', label: '收藏', tint: '#fff3e0', color: '#ff9500', icon: 'star' },
  { key: 'messages', label: '消息', tint: '#e8f1ff', color: '#3e7bfa', icon: 'chat' },
  { key: 'activity', label: '活动', tint: '#f1ecff', color: '#7a5cff', icon: 'gift' }
] as const
</script>

<template>
  <div class="entry-row">
    <button v-for="it in items" :key="it.key" class="entry-item" @click="$emit('go', it.key)">
      <span class="entry-tile" :style="{ background: it.tint, color: it.color }">
        <svg
          viewBox="0 0 24 24"
          width="21"
          height="21"
          fill="none"
          stroke="currentColor"
          stroke-width="1.9"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <template v-if="it.icon === 'receipt'">
            <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" /><path d="M9 8h6M9 12h6" />
          </template>
          <template v-else-if="it.icon === 'star'">
            <path d="M12 3.7l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z" />
          </template>
          <template v-else-if="it.icon === 'chat'">
            <path d="M21 11.5a7.5 7.5 0 0 1-11 6.7L4.5 20l1.8-5A7.5 7.5 0 1 1 21 11.5z" />
          </template>
          <template v-else>
            <path d="M20 12v8H4v-8" /><path d="M2 7h20v5H2z" /><path d="M12 7v13" />
            <path d="M12 7S9.5 3 7.5 4.4 8 7 12 7zM12 7s2.5-4 4.5-2.6S16 7 12 7z" />
          </template>
        </svg>
        <span v-if="it.key === 'messages' && unread > 0" class="entry-badge">
          {{ unread > 99 ? '99+' : unread }}
        </span>
      </span>
      <span class="entry-label">{{ it.label }}</span>
    </button>
  </div>
</template>

<style scoped lang="scss">
/* 市集入口行：吸顶第二层（tab 条之下、filter-bar 之上）。
   高度受全局 line-height 影响，故 filter-bar 的 top 由调用方实测 offsetHeight 下发，不在此写死。 */
.entry-row {
  position: sticky;
  top: 48px;
  z-index: 99;
  display: flex;
  padding: 8px 12px 10px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.entry-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.entry-tile {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 14px;
}

.entry-badge {
  position: absolute;
  top: -5px;
  right: -7px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: $primary;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

.entry-label {
  font-size: 12px;
  color: $text-2;
}
</style>
