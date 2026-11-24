<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMyCredit, listMyCreditLogs } from '@/api/credit'
import type { CreditLog } from '@/types/api'
import { formatTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'

const score = ref<number | null>(null)
const status = ref('NORMAL')
const logs = ref<CreditLog[]>([])
const loading = ref(true)

function level(score: number): { text: string; cls: string } {
  if (score >= 80) return { text: '信用优良', cls: 'good' }
  if (score >= 60) return { text: '信用正常', cls: 'normal' }
  if (score > 0) return { text: '信用警告，谨慎交易', cls: 'warn' }
  return { text: '已被封禁', cls: 'bad' }
}

async function load() {
  try {
    const [my, lg] = await Promise.all([getMyCredit(), listMyCreditLogs(1, 50)])
    score.value = my.creditScore
    status.value = my.status
    logs.value = lg.list
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page--plain credit-page">
    <NavBar title="信用分" />

    <div class="credit-hero">
      <div class="score-circle" :class="score !== null ? level(score).cls : ''">
        <div class="score-num">{{ score ?? '—' }}</div>
        <div class="score-unit">分</div>
      </div>
      <div class="score-level">{{ score !== null ? level(score).text : '加载中…' }}</div>
      <div class="score-tip">信用分低于 60 不能发布帖子或商品，低于 30 账号将被封禁</div>
    </div>

    <div class="log-section">
      <div class="log-title">信用分变动记录</div>
      <template v-if="logs.length">
        <div v-for="l in logs" :key="l.id" class="log-row">
          <div class="log-main">
            <div class="log-desc">{{ l.reasonDesc }}</div>
            <div class="log-time">{{ formatTime(l.createdAt) }}</div>
          </div>
          <div class="log-change" :class="l.change >= 0 ? 'up' : 'down'">
            {{ l.change >= 0 ? '+' : '' }}{{ l.change }}
          </div>
        </div>
      </template>
      <EmptyState v-else-if="!loading" text="暂无变动记录" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.credit-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 28px 0 20px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.score-circle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 108px;
  height: 108px;
  border-radius: 50%;
  border: 5px solid $bg-search;
  color: $text-1;

  &.good {
    border-color: #2e9e44;
    color: #2e9e44;
  }

  &.normal {
    border-color: #58b12f;
    color: #2e9e44;
  }

  &.warn {
    border-color: #ff9500;
    color: #ff9500;
  }

  &.bad {
    border-color: #e02020;
    color: #e02020;
  }
}

.score-num {
  font-size: 40px;
  font-weight: 700;
}

.score-unit {
  font-size: 13px;
}

.score-level {
  margin-top: 12px;
  font-size: 15px;
  font-weight: 600;
}

.score-tip {
  margin-top: 6px;
  padding: 0 24px;
  text-align: center;
  font-size: 11px;
  color: $text-3;
}

.log-section {
  margin: 10px;
  padding: 0 14px 12px;
  border-radius: $radius;
  background: #fff;
}

.log-title {
  padding: 14px 0 4px;
  font-size: 13px;
  font-weight: 600;
}

.log-row {
  display: flex;
  align-items: center;
  padding: 12px 0;
  border-bottom: 0.5px solid $line;

  &:last-child {
    border-bottom: none;
  }
}

.log-main {
  flex: 1;
  min-width: 0;
}

.log-desc {
  font-size: 14px;
}

.log-time {
  margin-top: 2px;
  font-size: 11px;
  color: $text-4;
}

.log-change {
  font-size: 16px;
  font-weight: 700;

  &.up {
    color: #2e9e44;
  }

  &.down {
    color: #e02020;
  }
}
</style>
