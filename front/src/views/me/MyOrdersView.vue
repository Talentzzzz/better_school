<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyProductOrders } from '@/api/trade'
import { useWaterfall } from '@/composables/useWaterfall'
import type { ProductOrder } from '@/types/api'
import { formatMoney, formatTime } from '@/utils/format'
import { statusText } from '@/utils/orderAction'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 聚合订单页：前端调商品订单域接口。
 * 子 tab（我买的 / 我卖的）切换重置本流。
 */
type IdleRole = 'buy' | 'sell'

const PAGE_SIZE = 10

const router = useRouter()

const IDLE_ROLES: { key: IdleRole; label: string }[] = [
  { key: 'buy', label: '我买的' },
  { key: 'sell', label: '我卖的' }
]

const idleRole = ref<IdleRole>('buy')

// 闲置（我买的 / 我卖的）
const {
  list: idleList,
  loading: idleLoading,
  finished: idleFinished,
  sentinel: idleSentinel,
  load: loadIdle,
  reset: resetIdle
} = useWaterfall<ProductOrder>((page) => listMyProductOrders(idleRole.value, page, PAGE_SIZE))

function selectIdleRole(next: IdleRole) {
  if (idleRole.value === next) return
  idleRole.value = next
  resetIdle()
  loadIdle()
}

onMounted(() => {
  loadIdle()
})
</script>

<template>
  <div class="page--plain my-orders">
    <NavBar title="我的订单" />

    <div class="sub-tabs">
      <button
        v-for="t in IDLE_ROLES"
        :key="t.key"
        class="sub-tab"
        :class="{ on: idleRole === t.key }"
        @click="selectIdleRole(t.key)"
      >
        {{ t.label }}
      </button>
    </div>

    <div v-if="idleList.length" class="order-list">
      <button
        v-for="o in idleList"
        :key="o.orderId"
        class="order-row"
        @click="router.push(`/trade/order/${o.orderId}`)"
      >
        <div class="order-head">
          <span class="order-status">{{ statusText(o.status, idleRole === 'buy' ? 'buyer' : 'seller') }}</span>
          <span class="order-reward">¥{{ formatMoney(o.price) }}</span>
        </div>
        <div class="order-desc ellipsis-2">{{ o.productTitle }}</div>
        <div class="order-meta">
          <span class="ellipsis">
            {{ idleRole === 'buy' ? `卖家 ${o.sellerNickname}` : `买家 ${o.buyerNickname}` }}
          </span>
          <span class="order-time">{{ formatTime(o.createdAt) }}</span>
        </div>
      </button>
    </div>
    <EmptyState
      v-else-if="!idleLoading"
      :text="idleRole === 'buy' ? '还没有购买记录' : '还没有卖出记录'"
      button-text="去市集看看"
      @click="router.push('/market')"
    />
    <div ref="idleSentinel" class="sentinel">
      {{ !idleList.length ? '' : idleLoading ? '加载中…' : idleFinished ? '已经到底啦' : '' }}
    </div>
  </div>
</template>

<style scoped lang="scss">
.my-orders {
  min-height: 100vh;
  background: $bg-page-alt;
}

// 子 tab（我买的 / 我卖的）
.sub-tabs {
  display: flex;
  gap: 10px;
  padding: 8px 12px;
  background: #fff;
}

.sub-tab {
  height: 28px;
  padding: 0 12px;
  border-radius: $radius-full;
  background: $bg-secondary;
  color: $text-2;
  font-size: 12px;

  &.on {
    background: $primary-light;
    color: $primary;
    font-weight: 500;
  }
}

.order-list {
  padding: 8px 12px 0;
}

.order-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 14px;
  margin-bottom: 8px;
  border-radius: $radius-card;
  background: #fff;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.order-status {
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 11px;
}

.order-reward {
  color: $primary;
  font-size: 14px;
  font-weight: 600;
}

.order-desc {
  font-size: 14px;
  font-weight: 500;
}

.order-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: $text-3;

  > span:first-child {
    min-width: 0;
  }
}

.order-time {
  margin-left: auto;
  flex-shrink: 0;
}

.sentinel {
  padding: 18px 0;
  text-align: center;
  color: $text-4;
  font-size: 12px;
}
</style>
