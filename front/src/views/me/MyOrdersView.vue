<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMyErrandOrders } from '@/api/errand'
import { listMyProductOrders } from '@/api/trade'
import { useWaterfall } from '@/composables/useWaterfall'
import type { ErrandOrder, ProductOrder } from '@/types/api'
import { formatMoney, formatTime } from '@/utils/format'
import { ERRAND_STATUS_TEXT } from '@/utils/errand'
import { statusText } from '@/utils/orderAction'
import NavBar from '@/components/NavBar.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 聚合订单页：前端并发调两个既有域接口、前端聚合（无后端聚合接口）。
 * 两 tab 与市集页同序；每 tab 一个独立流（各自分页、各自哨兵），子 tab / 筛选切换重置本 tab 的流。
 */
type OuterTab = 'errand' | 'idle'
type ErrandRole = 'publish' | 'take'
type IdleRole = 'buy' | 'sell'
type ErrandStatusFilter = 'ALL' | 'COMPLETED' | 'CANCELLED'

const PAGE_SIZE = 10

const route = useRoute()
const router = useRouter()

const OUTER_TABS: { key: OuterTab; label: string }[] = [
  { key: 'errand', label: '跑腿' },
  { key: 'idle', label: '闲置' }
]

const ERRAND_ROLES: { key: ErrandRole; label: string }[] = [
  { key: 'publish', label: '我发布的' },
  { key: 'take', label: '我接的' }
]

const IDLE_ROLES: { key: IdleRole; label: string }[] = [
  { key: 'buy', label: '我买的' },
  { key: 'sell', label: '我卖的' }
]

const ERRAND_STATUS_CHIPS: { key: ErrandStatusFilter; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' }
]

/** 初值取 ?tab=（SearchView 的「查看更多我的跑腿」指向本页跑腿 tab） */
function readTab(value: unknown): OuterTab {
  return value === 'idle' ? 'idle' : 'errand'
}

const tab = ref<OuterTab>(readTab(route.query.tab))
const errandRole = ref<ErrandRole>('publish')
const errandStatus = ref<ErrandStatusFilter>('ALL')
const idleRole = ref<IdleRole>('buy')

// 跑腿（我发布的 / 我接的 共用一个流：切子 tab、切状态都重置本流）
const {
  list: errandList,
  loading: errandLoading,
  finished: errandFinished,
  loaded: errandLoaded,
  sentinel: errandSentinel,
  load: loadErrand,
  reset: resetErrand
} = useWaterfall<ErrandOrder>((page) =>
  listMyErrandOrders(errandRole.value, {
    status: errandStatus.value === 'ALL' ? undefined : errandStatus.value,
    page,
    pageSize: PAGE_SIZE
  })
)

// 闲置（我买的 / 我卖的）
const {
  list: idleList,
  loading: idleLoading,
  finished: idleFinished,
  loaded: idleLoaded,
  sentinel: idleSentinel,
  load: loadIdle,
  reset: resetIdle
} = useWaterfall<ProductOrder>((page) => listMyProductOrders(idleRole.value, page, PAGE_SIZE))

function selectTab(next: OuterTab) {
  if (tab.value === next) return
  tab.value = next
  ensureLoaded()
  window.scrollTo(0, 0)
}

function ensureLoaded() {
  if (tab.value === 'errand' && !errandLoaded.value) loadErrand()
  else if (tab.value === 'idle' && !idleLoaded.value) loadIdle()
}

function selectErrandRole(next: ErrandRole) {
  if (errandRole.value === next) return
  errandRole.value = next
  resetErrand()
  loadErrand()
}

function selectIdleRole(next: IdleRole) {
  if (idleRole.value === next) return
  idleRole.value = next
  resetIdle()
  loadIdle()
}

function selectErrandStatus(next: ErrandStatusFilter) {
  if (errandStatus.value === next) return
  errandStatus.value = next
  resetErrand()
  loadErrand()
}

onMounted(() => {
  // 并发调两个既有接口（两个 tab 的主列表一次拉齐），不做后端聚合
  loadErrand()
  loadIdle()
})
</script>

<template>
  <div class="page--plain my-orders">
    <NavBar title="我的订单" />

    <div class="tabs-equal">
      <button
        v-for="t in OUTER_TABS"
        :key="t.key"
        :class="{ on: tab === t.key }"
        @click="selectTab(t.key)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 跑腿：我发布的 / 我接的 + 状态筛选；列表行沿用旧「我的跑腿」页 -->
    <template v-if="tab === 'errand'">
      <div class="sub-tabs">
        <button
          v-for="t in ERRAND_ROLES"
          :key="t.key"
          class="sub-tab"
          :class="{ on: errandRole === t.key }"
          @click="selectErrandRole(t.key)"
        >
          {{ t.label }}
        </button>
      </div>
      <div class="status-chips">
        <button
          v-for="c in ERRAND_STATUS_CHIPS"
          :key="c.key"
          class="chip"
          :class="{ on: errandStatus === c.key }"
          @click="selectErrandStatus(c.key)"
        >
          {{ c.label }}
        </button>
      </div>

      <div v-if="errandList.length" class="order-list">
        <button
          v-for="o in errandList"
          :key="o.orderId"
          class="order-row"
          @click="router.push(`/errand/${o.orderId}`)"
        >
          <div class="order-head">
            <span class="order-status">{{ ERRAND_STATUS_TEXT[o.status] || o.status }}</span>
            <span class="order-reward">
              ¥{{ formatMoney(o.reward) }}
            </span>
          </div>
          <div class="order-desc ellipsis-2">{{ o.description }}</div>
          <div class="order-meta">
            <AppAvatar
              :src="errandRole === 'publish' ? o.takerAvatar : o.publisherAvatar"
              :name="errandRole === 'publish' ? (o.takerNickname ?? '—') : o.publisherNickname"
              :size="18"
            />
            <span class="ellipsis">
              {{
                errandRole === 'publish'
                  ? o.takerNickname
                    ? `接单者 ${o.takerNickname}`
                    : '等待接单'
                  : `发布者 ${o.publisherNickname}`
              }}
            </span>
            <span class="order-time">{{ formatTime(o.createdAt) }}</span>
          </div>
        </button>
      </div>
      <EmptyState
        v-else-if="!errandLoading"
        :text="errandRole === 'publish' ? '还没有发布过跑腿单' : '还没有接过跑腿单'"
        button-text="去市集看看"
        @click="router.push('/market')"
      />
      <div ref="errandSentinel" class="sentinel">
        {{ !errandList.length ? '' : errandLoading ? '加载中…' : errandFinished ? '已经到底啦' : '' }}
      </div>
    </template>

    <!-- 闲置：我买的 / 我卖的；列表行沿用旧「我的商品订单」页 -->
    <template v-else>
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
    </template>
  </div>
</template>

<style scoped lang="scss">
.my-orders {
  min-height: 100vh;
  background: $bg-page-alt;
}

// 外层两 tab 用全局 .tabs-equal（与市集消息页同一套），此处不重复声明

// 子 tab（我发布的 / 我接的、我买的 / 我卖的）
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

// 跑腿状态筛选
.status-chips {
  display: flex;
  gap: 18px;
  padding: 0 16px 10px;
  background: #fff;
}

.chip {
  font-size: 13px;
  color: $text-2;

  &.on {
    color: $primary;
    font-weight: 600;
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
