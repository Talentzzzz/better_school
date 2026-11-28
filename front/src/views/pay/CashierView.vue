<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPaymentOrder, payPaymentOrder } from '@/api/pay'
import type { PaymentOrder } from '@/types/api'
import NavBar from '@/components/NavBar.vue'
import { toast } from '@/components/Toast'
import { formatMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const payNo = String(route.params.payNo)
const order = ref<PaymentOrder | null>(null)
const loading = ref(true)
const paying = ref(false)
const paid = ref(false)

async function load() {
  try {
    order.value = await getPaymentOrder(payNo)
    paid.value = order.value.state === 'PAID'
  } catch {
    // toast 已处理
  } finally {
    loading.value = false
  }
}

async function onPay() {
  if (!order.value || paying.value) return
  paying.value = true
  try {
    await payPaymentOrder(payNo)
    paid.value = true
    toast('支付成功')
    setTimeout(backToOrder, 800)
  } catch {
    // toast 已处理
  } finally {
    paying.value = false
  }
}

function backToOrder() {
  // 有来路回订单页（返回后页面重新挂载即刷新）；直接打开收银台则回我的订单
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.replace('/me/orders')
}

onMounted(load)
</script>

<template>
  <div class="page--plain cashier">
    <NavBar title="收银台" />

    <div v-if="loading" class="cashier-loading">加载中…</div>

    <template v-else-if="order">
      <!-- 支付成功：仿微信支付结果页 -->
      <div v-if="paid" class="result-card">
        <div class="result-icon">✓</div>
        <div class="result-title">支付成功</div>
        <div class="result-amount">¥{{ formatMoney(order.amount) }}</div>
        <button class="result-back" @click="backToOrder">返回订单</button>
      </div>

      <!-- 支付单已关闭 -->
      <div v-else-if="order.state === 'CLOSED'" class="result-card">
        <div class="result-icon result-icon--muted">×</div>
        <div class="result-title">支付单已关闭</div>
        <div class="result-sub">订单已取消或已超时，请重新下单</div>
        <button class="result-back" @click="backToOrder">返回</button>
      </div>

      <!-- 待支付 -->
      <template v-else>
        <div class="merchant">
          <div class="merchant-name">{{ order.subject }}</div>
          <div class="merchant-tag">模拟微信支付</div>
        </div>

        <div class="amount-card">
          <div class="amount-label">支付金额</div>
          <div class="amount-value"><span class="amount-symbol">¥</span>{{ formatMoney(order.amount) }}</div>
        </div>

        <div class="pay-method">
          <div class="method-icon">¥</div>
          <div class="method-info">
            <div class="method-name">零钱</div>
            <div class="method-sub">余额支付</div>
          </div>
          <div class="method-check">✓</div>
        </div>

        <div class="pay-tip">支付成功后货款将托管冻结，确认收货后转给卖家</div>

        <footer class="pay-bar">
          <button class="pay-btn" :disabled="paying" @click="onPay">
            {{ paying ? '支付中…' : `确认支付 ¥${formatMoney(order.amount)}` }}
          </button>
        </footer>
      </template>
    </template>

    <div v-else class="cashier-loading">支付单不存在或已失效</div>
  </div>
</template>

<style scoped lang="scss">
// 仿微信支付：绿色确认按钮 / 大金额 / 单行支付方式
$wechat-green: #07c160;

.cashier {
  min-height: 100vh;
  background: $bg-page;
  padding-bottom: calc(96px + env(safe-area-inset-bottom));
}

.cashier-loading {
  padding: 80px 0;
  text-align: center;
  color: $text-3;
  font-size: 13px;
}

.merchant {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.merchant-name {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.merchant-tag {
  margin-left: 10px;
  padding: 2px 8px;
  border-radius: $radius-full;
  background: rgba($wechat-green, 0.1);
  color: $wechat-green;
  font-size: 11px;
  flex-shrink: 0;
}

.amount-card {
  padding: 28px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
  text-align: center;
}

.amount-label {
  font-size: 12px;
  color: $text-3;
}

.amount-value {
  margin-top: 8px;
  font-size: 38px;
  font-weight: 600;
  color: $text-1;
}

.amount-symbol {
  font-size: 22px;
  margin-right: 2px;
}

.pay-method {
  display: flex;
  align-items: center;
  margin: 12px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
}

.method-icon {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: $wechat-green;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.method-info {
  flex: 1;
  margin-left: 10px;
}

.method-name {
  font-size: 14px;
  font-weight: 500;
}

.method-sub {
  margin-top: 2px;
  font-size: 11px;
  color: $text-3;
}

.method-check {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: $wechat-green;
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.pay-tip {
  padding: 0 18px;
  color: $text-3;
  font-size: 11px;
  line-height: 1.6;
}

.pay-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  margin: 0 auto;
  max-width: 480px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 0.5px solid $line;
}

.pay-btn {
  width: 100%;
  height: 46px;
  border-radius: $radius;
  background: $wechat-green;
  color: #fff;
  font-size: 16px;
  font-weight: 500;

  &:disabled {
    opacity: 0.6;
  }
}

.result-card {
  margin: 60px 20px 0;
  padding: 36px 20px;
  border-radius: 16px;
  background: #fff;
  text-align: center;
}

.result-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto;
  border-radius: 50%;
  background: $wechat-green;
  color: #fff;
  font-size: 32px;
  line-height: 64px;

  &--muted {
    background: $text-4;
  }
}

.result-title {
  margin-top: 18px;
  font-size: 20px;
  font-weight: 600;
}

.result-amount {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 600;
  color: $text-1;
}

.result-sub {
  margin-top: 8px;
  font-size: 13px;
  color: $text-3;
}

.result-back {
  margin-top: 28px;
  min-width: 160px;
  height: 40px;
  padding: 0 20px;
  border-radius: $radius-full;
  border: 0.5px solid $line;
  background: #fff;
  color: $text-2;
  font-size: 14px;
}
</style>
