<script setup lang="ts">
import { computed } from 'vue'
import { useConsoleStore } from '@/stores/console'
import { useAuthStore } from '@/stores/auth'
import type { WsStatus } from '@/utils/reviewWs'

const store = useConsoleStore()
const auth = useAuthStore()

const STATUS_TEXT: Record<WsStatus, string> = {
  idle: '未连接',
  connecting: '连接中…',
  open: '在线',
  retrying: '重连中…',
  stopped: '已断开'
}

const wsText = computed(() => STATUS_TEXT[store.wsStatus])
const wsOnline = computed(() => store.wsStatus === 'open')
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <div class="logo">审</div>
      <div class="brand-text">
        <div class="brand-title">审核中台</div>
        <div class="brand-sub">多类型同屏 · 推信号 + 拉工单</div>
      </div>
    </div>

    <div class="spacer" />

    <div class="online-count" :class="{ off: !wsOnline }">
      <span class="live-dot" />
      长连接 <b>{{ wsText }}</b>
    </div>

    <button class="ghost-btn" :disabled="store.wsStatus !== 'open'" @click="store.refresh()">刷新计数</button>

    <div class="presence" :class="{ on: wsOnline }" :title="'presence 由 /ws/review 连接承载'">
      <span class="dot" />
      {{ wsOnline ? '在线' : '离线' }}
    </div>

    <div class="me">
      <div class="avatar">{{ auth.nickname.slice(0, 1) }}</div>
      <div class="me-text">
        <div class="me-name">{{ auth.nickname }}</div>
        <div class="me-role">审核员 · {{ auth.role }}</div>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.topbar {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 60px;
  padding: 0 18px;
  background: $bg-1;
  border-bottom: 1px solid $line-soft;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, $brand, $brand-dark);
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 14px rgba(255, 36, 66, 0.35);
}

.brand-title {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.brand-sub {
  font-size: 11px;
  color: $text-3;
}

.spacer {
  flex: 1;
}

.online-count {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: $text-2;

  b {
    color: $text-1;
  }

  &.off {
    opacity: 0.6;
  }
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: $green;
  box-shadow: 0 0 0 3px rgba(46, 194, 126, 0.16);

  .off & {
    background: $text-4;
    box-shadow: none;
  }
}

.ghost-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: $radius-sm;
  background: $bg-2;
  border: 1px solid $line;
  color: $text-2;
  font-size: 12px;
  transition: 0.15s;

  &:hover:not(:disabled) {
    background: $bg-3;
    color: $text-1;
  }

  &:disabled {
    opacity: 0.5;
  }
}

.presence {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 13px;
  border-radius: $radius-full;
  background: $bg-2;
  border: 1px solid $line;
  color: $text-3;
  font-size: 12px;
  font-weight: 600;
  transition: 0.15s;

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: $text-4;
  }

  &.on {
    background: $green-soft;
    border-color: rgba(46, 194, 126, 0.4);
    color: $green;

    .dot {
      background: $green;
      box-shadow: 0 0 0 3px rgba(46, 194, 126, 0.2);
    }
  }
}

.me {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 14px;
  margin-left: 2px;
  border-left: 1px solid $line;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3a4a66, #22303f);
  color: $text-1;
  font-size: 13px;
  font-weight: 600;
  display: grid;
  place-items: center;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.me-name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
}

.me-role {
  font-size: 11px;
  color: $text-3;
}
</style>
