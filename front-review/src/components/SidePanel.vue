<script setup lang="ts">
import { useConsoleStore, type BannerMode } from '@/stores/console'
import type { EventKind } from '@/types/review'
import { formatClockTime } from '@/utils/format'

const store = useConsoleStore()

const EVENT_META: Record<EventKind, { label: string; color: string }> = {
  signal: { label: '推送', color: '#ff2442' },
  claim: { label: '拉取', color: '#22b8cf' },
  pass: { label: '通过', color: '#2ec27e' },
  reject: { label: '拒绝', color: '#ff5c6c' },
  release: { label: '放回', color: '#a06bff' },
  recycle: { label: '回收', color: '#f5a623' },
  presence: { label: '在线', color: '#a06bff' }
}

const MODE_LABEL: Record<BannerMode, string> = {
  offline: '长连接断开 · 收不到信号',
  busy: '手中有单 · 审完可再拉',
  signal: '收到推送信号 · 待拉取',
  empty: '队列空 · 等待进审'
}
</script>

<template>
  <aside class="side">
    <!-- 连接状态（presence 由 /ws/review 承载：连接即在线、心跳保活、断开删除） -->
    <section class="card panel">
      <div class="panel-title">连接状态（presence）</div>
      <div class="me-row">
        <span class="dot" :class="{ on: store.wsStatus === 'open' }" />
        <span class="me-name">/ws/review</span>
        <span class="mode-tag">{{ MODE_LABEL[store.bannerMode] }}</span>
      </div>
      <ul class="facts">
        <li><span>在线口径</span><b>连接建立即在线，服务端 Redis 记录</b></li>
        <li><span>心跳</span><b>25s PING · 断线退避重连</b></li>
        <li><span>派单信号</span><b>只唤醒（广播积压数），不指定给谁</b></li>
        <li><span>上线补推</span><b>连接建立即推当前积压数</b></li>
      </ul>
    </section>

    <!-- 统计 -->
    <section class="card panel">
      <div class="panel-title">我的处理量</div>
      <div class="stats">
        <div class="stat-cell">
          <b>{{ store.doneCount }}</b>
          <span>已处理</span>
        </div>
        <div class="stat-cell">
          <b>{{ store.avgSeconds ? store.avgSeconds + 's' : '—' }}</b>
          <span>平均耗时</span>
        </div>
        <div class="stat-cell">
          <b :class="{ warn: store.reclaimedCount > 0 }">{{ store.reclaimedCount }}</b>
          <span>租约回收</span>
        </div>
        <div class="stat-cell">
          <b>{{ store.backlogTotal }}</b>
          <span>队列深度</span>
        </div>
      </div>
    </section>

    <!-- 事件流 -->
    <section class="card panel events-panel">
      <div class="panel-title">
        派单事件流
        <span class="live">实时</span>
      </div>
      <ul class="events">
        <li v-for="e in store.events" :key="e.id" class="event">
          <span class="e-time">{{ formatClockTime(e.at) }}</span>
          <span class="e-kind" :style="{ color: EVENT_META[e.kind].color }">
            {{ EVENT_META[e.kind].label }}
          </span>
          <span class="e-text">{{ e.text }}</span>
        </li>
        <li v-if="!store.events.length" class="event-empty">暂无事件</li>
      </ul>
    </section>
  </aside>
</template>

<style scoped lang="scss">
.side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow-y: auto;
}

.panel {
  padding: 14px;
  flex-shrink: 0;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 11px;
}

.live {
  padding: 1px 7px;
  border-radius: $radius-full;
  background: rgba(46, 194, 126, 0.15);
  color: $green;
  font-size: 10px;
  font-weight: 600;
}

.me-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 11px;
  margin-bottom: 8px;
  border-bottom: 1px solid $line-soft;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: $text-4;
  flex-shrink: 0;

  &.on {
    background: $green;
    box-shadow: 0 0 0 3px rgba(46, 194, 126, 0.18);
  }
}

.me-name {
  font-size: 13px;
  font-weight: 600;
  font-family: ui-monospace, monospace;
}

.mode-tag {
  margin-left: auto;
  font-size: 11px;
  color: $text-3;
  text-align: right;
}

.facts {
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 11.5px;

  li {
    display: flex;
    gap: 8px;
  }

  span {
    flex-shrink: 0;
    width: 60px;
    color: $text-3;
  }

  b {
    color: $text-2;
    font-weight: 500;
    line-height: 1.5;
  }
}

.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.stat-cell {
  padding: 10px;
  border-radius: $radius;
  background: $bg-2;
  text-align: center;

  b {
    display: block;
    font-size: 20px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    &.warn {
      color: $amber;
    }
  }

  span {
    font-size: 11px;
    color: $text-3;
  }
}

.events-panel {
  flex: 1;
  min-height: 160px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.events {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.event {
  display: flex;
  gap: 7px;
  padding: 5px 6px;
  border-radius: $radius-sm;
  font-size: 11.5px;
  line-height: 1.45;

  &:hover {
    background: $bg-2;
  }
}

.e-time {
  flex-shrink: 0;
  color: $text-4;
  font-family: ui-monospace, monospace;
}

.e-kind {
  flex-shrink: 0;
  font-weight: 700;
}

.e-text {
  color: $text-2;
  word-break: break-word;
}

.event-empty {
  padding: 20px 0;
  text-align: center;
  color: $text-3;
  font-size: 12px;
}
</style>
