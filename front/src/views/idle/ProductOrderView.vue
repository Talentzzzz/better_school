<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  getProductOrder,
  markDeliveredProduct,
  confirmProductOrder,
  cancelProductOrder
} from '@/api/idle'
import type { ProductOrder } from '@/types/api'
import NavBar from '@/components/NavBar.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatMoney, formatTime } from '@/utils/format'
import { orderActions, statusText, type OrderAction, type OrderRole } from '@/utils/orderAction'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const orderId = Number(route.params.id)
const order = ref<ProductOrder | null>(null)
const busy = ref(false)

const role = computed<OrderRole>(() => {
  if (!order.value) return 'visitor'
  if (order.value.buyerId === auth.userInfo?.userId) return 'buyer'
  if (order.value.sellerId === auth.userInfo?.userId) return 'seller'
  return 'visitor'
})

/** 判据：该「状态 × 角色」下有没有可点的操作 —— 决定等分底栏 / 状态卡二分 */
const actions = computed(() => (order.value ? orderActions(order.value.status, role.value) : null))
const canAct = computed(() => !!(actions.value?.primary || actions.value?.secondary))

async function load() {
  try {
    order.value = await getProductOrder(orderId)
  } catch {
    // toast 已处理
  }
}

function requireLogin(): boolean {
  if (auth.isLoggedIn) return true
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function doAction(fn: () => Promise<unknown>, successMsg: string, needConfirm?: string) {
  if (!requireLogin()) return
  if (needConfirm && !(await confirmDialog(needConfirm))) return
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    toast(successMsg)
    await load()
  } catch {
    // toast 已处理
  } finally {
    busy.value = false
  }
}

const ACTION_HANDLERS: Record<OrderAction['key'], () => void> = {
  pay: () => {
    if (!requireLogin()) return
    const payNo = order.value?.payNo
    if (!payNo) {
      toast('支付单生成中，请稍后刷新重试')
      return
    }
    router.push(`/pay/${payNo}`)
  },
  delivered: () => doAction(() => markDeliveredProduct(orderId), '已标记交付，等待买家确认收货', '确认商品已交付给买家？'),
  confirm: () => doAction(() => confirmProductOrder(orderId), '订单完成，货款已支付给卖家', '确认已收到商品？确认后货款将转给卖家'),
  cancel: () =>
    doAction(
      () => cancelProductOrder(orderId),
      '订单已取消',
      order.value?.status === 'PENDING_PAYMENT' ? '确认取消订单？' : '确认取消订单？已支付的货款将原路退回'
    )
}

function onAction(a?: OrderAction) {
  if (a) ACTION_HANDLERS[a.key]()
}

onMounted(load)
</script>

<template>
  <div class="page--plain order-detail" :class="{ 'has-bar': canAct }">
    <NavBar title="商品订单" />

    <template v-if="order">
      <!-- 纯等待 / 终态：状态卡，不设底栏 -->
      <div v-if="!canAct" class="status-head">
        <div class="status-head-top">
          <span class="status-text">{{ statusText(order.status, role) }}</span>
          <span v-if="actions?.amountLabel" class="status-head-amount">
            <small>{{ actions.amountLabel }}</small>
            <b>¥{{ formatMoney(order.price) }}</b>
          </span>
        </div>
        <div class="status-note">{{ actions?.note }}</div>
        <div v-if="actions?.waiting" class="status-wait">{{ actions.waiting }}</div>
      </div>

      <!-- 有操作：状态条 + 等分底栏 -->
      <div v-else class="status-hero">
        <div class="status-text">{{ statusText(order.status, role) }}</div>
        <div class="status-note">{{ actions?.note }}</div>
      </div>

      <div class="detail-card" @click="router.push(`/product/${order.productId}`)">
        <div class="product-row">
          <div class="product-info">
            <div class="product-title ellipsis-2">{{ order.productTitle }}</div>
            <div class="product-price">¥{{ formatMoney(order.price) }}</div>
          </div>
        </div>
      </div>

      <div class="detail-card">
        <div class="detail-row">
          <span class="detail-label">订单编号</span>
          <span class="detail-text">#{{ order.orderId }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">下单时间</span>
          <span class="detail-text">{{ formatTime(order.createdAt) }}</span>
        </div>
      </div>

      <div class="detail-card people-card">
        <div class="people-row">
          <AppAvatar :src="order.buyerAvatar" :name="order.buyerNickname" :size="40" />
          <div class="people-info">
            <div class="people-name">{{ order.buyerNickname }}<span class="role-tag">买家</span></div>
            <div class="people-time">下单用户</div>
          </div>
        </div>
        <div class="people-row">
          <AppAvatar :src="order.sellerAvatar" :name="order.sellerNickname" :size="40" />
          <div class="people-info">
            <div class="people-name">{{ order.sellerNickname }}<span class="role-tag role-tag--green">卖家</span></div>
            <div class="people-time">收款用户</div>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="order-loading">加载中…</div>

    <!-- 操作区（按订单状态 + 角色二分）：有操作 → 等分底栏；纯等待/终态 → 上面那张状态卡 -->
    <footer v-if="order && canAct" class="action-bar">
      <div class="action-row">
        <div v-if="actions?.amountLabel" class="action-seg action-amount">
          <div class="amount-value">¥{{ formatMoney(order.price) }}</div>
          <div class="amount-label">{{ actions.amountLabel }}</div>
        </div>
        <button
          v-if="actions?.secondary"
          class="action-seg btn-outline action-sec"
          :class="{ danger: actions.secondary.danger }"
          :disabled="busy"
          @click="onAction(actions?.secondary)"
        >
          {{ actions.secondary.label }}
        </button>
        <button
          v-if="actions?.primary"
          class="action-seg btn-primary action-main"
          :disabled="busy"
          @click="onAction(actions?.primary)"
        >
          {{ actions.primary.label }}
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.order-detail {
  min-height: 100vh;
  background: $bg-page;
  padding-bottom: calc(24px + env(safe-area-inset-bottom));

  &.has-bar {
    padding-bottom: calc(88px + env(safe-area-inset-bottom));
  }
}

.status-hero {
  padding: 20px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

// 纯等待 / 终态的状态卡：状态＋金额＋说明＋等待文案合并成一张顶卡
.status-head {
  padding: 16px 16px 14px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.status-head-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

// 状态卡里没有 .status-hero 的 gap，说明与状态行的 4px 间距要自己带
.status-head .status-note {
  margin-top: 4px;
}

.status-head-amount {
  display: flex;
  align-items: baseline;
  gap: 6px;

  small {
    font-size: 11px;
    color: $text-3;
  }

  b {
    font-size: 19px;
    font-weight: 600;
    color: $primary;
  }
}

.status-text {
  font-size: 20px;
  font-weight: 600;
}

.status-note {
  font-size: 12px;
  color: $text-3;
}

.status-wait {
  margin-top: 12px;
  font-size: 13px;
  color: $text-2;
}

// 等分底栏：金额 → 次操作 → 主操作；缺格均分
.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  margin: 0 auto;
  max-width: 480px;
  // 安全区写进自己的 padding——挂全局 .safe-bottom 会被 scoped 简写 padding 压掉
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 0.5px solid $line;
  z-index: 20;
}

.action-row {
  display: flex;
  align-items: stretch;
  gap: 8px;
}

// 不能叫 .cell：common.scss 有同名全局类会盖掉 .btn-primary 的红底
.action-seg {
  flex: 1;
  min-width: 0;
  height: 44px;
}

.action-amount {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.amount-value {
  font-size: 16px;
  font-weight: 600;
  color: $primary;
  line-height: 1.15;
}

.amount-label {
  font-size: 11px;
  color: $text-3;
  line-height: 1.3;
}

.action-sec,
.action-main {
  width: 100%;
  height: 44px;
  font-size: 14px;
  padding: 0 8px;
}

.action-sec {
  border-color: #ddd;
  color: $text-2;

  &.danger {
    border-color: rgba($danger, 0.4);
    color: $danger;
  }
}

.detail-card {
  margin: 10px 12px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
}

.product-title {
  font-size: 15px;
  font-weight: 500;
}

.product-price {
  margin-top: 6px;
  color: $primary;
  font-size: 16px;
  font-weight: 600;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 5px 0;
  font-size: 13px;
}

.detail-label {
  color: $text-3;
}

.people-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}

.people-name {
  font-size: 14px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}

.role-tag {
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: $primary-light;
  color: $primary;
  font-size: 10px;

  &--green {
    background: #e8f6e9;
    color: #2e9e44;
  }
}

.people-time {
  margin-top: 2px;
  font-size: 11px;
  color: $text-3;
}

.order-loading {
  padding: 80px 0;
  text-align: center;
  color: $text-3;
  font-size: 13px;
}
</style>
