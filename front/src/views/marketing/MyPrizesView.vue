<script setup lang="ts">
import { onMounted, ref } from 'vue'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { confirmReceived, listMyPrizes, prizeTint, tierText } from '@/api/marketing'
import type { UserPrize, UserPrizeStatus } from '@/api/marketing'
import { formatDateTime } from '@/utils/format'

// 四段状态与后端一一对应：待兑换 / 已兑换 / 已发货 / 已完成（THANKS 档不在「我的奖品」里）
const tabs = [
  { key: 'PENDING', label: '待兑换' },
  { key: 'EXCHANGED', label: '已兑换' },
  { key: 'SHIPPED', label: '已发货' },
  { key: 'COMPLETED', label: '已完成' }
] as const satisfies ReadonlyArray<{ key: UserPrizeStatus; label: string }>

const tab = ref<UserPrizeStatus>('PENDING')
const list = ref<UserPrize[]>([])
const loading = ref(true)

const STATE_TEXT: Record<UserPrizeStatus, string> = {
  PENDING: '待兑换',
  EXCHANGED: '已兑换',
  SHIPPED: '已发货',
  COMPLETED: '已完成'
}

async function load() {
  loading.value = true
  try {
    list.value = (await listMyPrizes(tab.value, 1, 50)).list
  } catch {
    // 默认 toast 已提示
  } finally {
    loading.value = false
  }
}

function switchTab(key: UserPrizeStatus) {
  if (tab.value === key) return
  tab.value = key
  load()
}

// ---------- 确认收货：已发货 → 已完成（后端幂等，重复点仍返回 COMPLETED） ----------

const receiving = ref<string | null>(null)

async function onReceive(p: UserPrize) {
  const ok = await confirmDialog('确认收货', `确认已收到「${p.name}」？`)
  if (!ok) return
  receiving.value = p.id
  try {
    await confirmReceived(p.id)
    toast('已确认收货')
    load()
  } catch {
    // 默认 toast 已提示（含 PRIZE_NOT_RECEIVABLE）
  } finally {
    receiving.value = null
  }
}

// 色块按 prizeId 取，与营销页奖品目录同一奖品同色（UUID 不能取模，走 prizeTint 哈希）
const tintOf = (p: UserPrize) => prizeTint(p.prizeId)

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="我的奖品" />
    <div class="tabs-equal">
      <button
        v-for="t in tabs"
        :key="t.key"
        :class="{ on: tab === t.key }"
        @click="switchTab(t.key)"
      >
        {{ t.label }}
      </button>
    </div>

    <div class="p-body">
      <template v-if="!loading && list.length">
        <div v-for="p in list" :key="p.id" class="p-card">
          <div class="p-art" :style="{ background: tintOf(p) }">第 {{ p.tier }} 等</div>
          <div class="p-main">
            <div class="p-rank">{{ tierText(p.tier) }}</div>
            <div class="p-name">{{ p.name }}</div>
            <div class="p-time">中奖于 {{ formatDateTime(p.createdAt) }}</div>
          </div>
          <div class="p-right">
            <span class="p-state" :class="{ pending: p.status === 'PENDING' }">{{ STATE_TEXT[p.status] }}</span>
            <button
              v-if="p.status === 'SHIPPED'"
              class="p-btn"
              :disabled="receiving === p.id"
              @click="onReceive(p)"
            >
              {{ receiving === p.id ? '提交中…' : '确认收货' }}
            </button>
          </div>
        </div>
      </template>
      <EmptyState v-else-if="!loading" text="这里还没有奖品" />
      <div v-else class="p-loading">加载中…</div>
    </div>
  </div>
</template>

<style scoped lang="scss">
// 四段 tab 复用全局 .tabs-equal（票 04 共享件，与聚合订单页 / 市集消息页同一套）
.page {
  min-height: 100vh;
  background: $bg-page-alt;
}

.p-body {
  min-height: calc(100vh - 100px);
  padding-bottom: 24px;
}

.p-loading {
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: $text-4;
}

.p-card {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 10px 12px 0;
  padding: 12px;
  border-radius: $radius-card;
  background: #fff;
  box-shadow: $shadow-card;
}

.p-art {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: $radius;
  font-size: 15px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.6);
  flex-shrink: 0;
}

.p-main {
  flex: 1;
  min-width: 0;
}

.p-rank {
  font-size: 11px;
  color: $primary;
  font-weight: 600;
}

.p-name {
  margin-top: 2px;
  font-size: 15px;
  font-weight: 600;
}

.p-time {
  margin-top: 3px;
  font-size: 11px;
  color: $text-3;
}

.p-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.p-state {
  font-size: 12px;
  color: $text-3;

  &.pending {
    color: $primary;
    font-weight: 600;
  }
}

.p-btn {
  padding: 5px 12px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 12px;
  font-weight: 600;

  &:disabled {
    background: $text-4;
  }
}
</style>
