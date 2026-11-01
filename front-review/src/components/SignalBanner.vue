<script setup lang="ts">
import { computed } from 'vue'
import { useConsoleStore } from '@/stores/console'

const store = useConsoleStore()

const mode = computed(() => store.bannerMode)

const head = computed(() => {
  switch (mode.value) {
    case 'offline':
      return store.wsStatus === 'stopped' ? '连接已断开' : '连接中…'
    case 'busy':
      return '手中有单'
    case 'signal':
      return '有新工单'
    default:
      return '队列已清空'
  }
})

const sub = computed(() => {
  switch (mode.value) {
    case 'offline':
      return store.wsStatus === 'stopped'
        ? '长连接已断开 → 收不到派单信号，请刷新页面重连（池是持久化的，积压不会丢）'
        : '正在与 /ws/review 建立长连接（presence），连接建立即在线并补推积压'
    case 'busy':
      return `当前工单 #${store.current?.claim.taskId ?? ''} 审完即可再拉`
    case 'signal':
      return `服务端广播「有新工单」（当前积压 ${store.backlogTotal} 条）· 点「拉一条」取活（一次一条）`
    default:
      return '暂无待审内容，等待新内容进审（派单信号到达时本条会亮起）'
  }
})
</script>

<template>
  <div class="signal-banner" :class="[mode, { pulse: store.pulse }]">
    <span class="beacon" />
    <div class="text">
      <div class="head">{{ head }}</div>
      <div class="sub">{{ sub }}</div>
    </div>
    <div class="actions">
      <button
        class="pull-btn"
        :disabled="mode !== 'signal'"
        @click="store.pullOne()"
      >
        拉一条
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.signal-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 18px 0;
  padding: 11px 16px;
  border-radius: $radius-card;
  background: $bg-1;
  border: 1px solid $line-soft;
  transition: 0.25s;

  &.signal {
    background: linear-gradient(90deg, rgba(255, 36, 66, 0.14), rgba(255, 36, 66, 0.03));
    border-color: rgba(255, 36, 66, 0.34);
  }

  &.busy {
    border-color: rgba(76, 141, 255, 0.3);
    background: linear-gradient(90deg, rgba(76, 141, 255, 0.1), transparent);
  }

  &.offline {
    opacity: 0.72;
  }

  &.pulse .beacon {
    animation: pulse-dot 0.9s ease-in-out infinite;
  }
}

.beacon {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  background: $text-4;

  .signal & {
    background: $brand;
    box-shadow: 0 0 0 5px rgba(255, 36, 66, 0.16);
  }
  .busy & {
    background: $blue;
    box-shadow: 0 0 0 5px rgba(76, 141, 255, 0.16);
  }
  .empty & {
    background: $text-4;
  }
}

.text {
  flex: 1;
  min-width: 0;
}

.head {
  font-size: 14px;
  font-weight: 700;

  .offline & {
    color: $text-2;
  }
  .signal & {
    color: $brand;
  }
}

.sub {
  margin-top: 2px;
  font-size: 12px;
  color: $text-3;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pull-btn {
  height: 34px;
  padding: 0 20px;
  border-radius: $radius-full;
  background: $brand;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  transition: 0.15s;
  box-shadow: 0 6px 18px rgba(255, 36, 66, 0.3);

  &:hover:not(:disabled) {
    background: $brand-dark;
  }

  &:disabled {
    background: $bg-3;
    color: $text-3;
    box-shadow: none;
  }
}
</style>
