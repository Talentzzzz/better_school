<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getBalance, listTransactions, recharge } from '@/api/wallet'
import type { WalletTransaction } from '@/types/api'
import { toast } from '@/components/Toast'
import { formatTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'

const balance = ref(0)
const frozen = ref(0)
const list = ref<WalletTransaction[]>([])
const amount = ref('100')
const recharging = ref(false)
const loading = ref(true)

const quickAmounts = [50, 100, 200]

const TYPE_TEXT: Record<string, string> = {
  RECHARGE: '充值',
  FROZEN: '支付冻结',
  UNFROZEN: '解冻退回',
  INCOME: '跑腿收入',
  PRODUCT_FROZEN: '商品支付冻结',
  PRODUCT_UNFROZEN: '商品解冻退回',
  PRODUCT_INCOME: '商品售卖收入'
}

async function load() {
  try {
    const [bal, txns] = await Promise.all([getBalance(), listTransactions(1, 50)])
    balance.value = bal.balance
    frozen.value = bal.frozenAmount
    list.value = txns.list
  } finally {
    loading.value = false
  }
}

async function onRecharge() {
  const n = Number(amount.value)
  if (!n || n <= 0) {
    toast('请输入有效金额')
    return
  }
  recharging.value = true
  try {
    await recharge(n)
    toast(`充值成功 +${n}`)
    await load()
  } catch {
    // toast 已处理
  } finally {
    recharging.value = false
  }
}

const isIncome = (t: WalletTransaction) => t.type.endsWith('INCOME') || t.type.endsWith('UNFROZEN')

onMounted(load)
</script>

<template>
  <div class="page--plain wallet-page">
    <NavBar title="我的钱包" />

    <div class="balance-card">
      <div class="balance-label">可用余额（校园币）</div>
      <div class="balance-num">{{ balance.toFixed(2) }}</div>
      <div class="balance-frozen">托管冻结中：{{ frozen.toFixed(2) }}</div>
      <div class="balance-tip">演示环境：金额为虚拟币，跑腿交易全程模拟</div>
    </div>

    <div class="recharge-card">
      <div class="recharge-title">充值</div>
      <div class="recharge-quick">
        <button
          v-for="a in quickAmounts"
          :key="a"
          class="quick-btn"
          :class="{ active: Number(amount) === a }"
          @click="amount = String(a)"
        >
          {{ a }}
        </button>
      </div>
      <div class="recharge-input-row">
        <span class="recharge-symbol">¥</span>
        <input v-model="amount" class="recharge-input" type="number" min="0.01" placeholder="自定义金额" />
        <button class="btn-primary recharge-btn" :disabled="recharging" @click="onRecharge">
          充值
        </button>
      </div>
    </div>

    <div class="txn-section">
      <div class="txn-title">收支明细</div>
      <template v-if="list.length">
        <div v-for="t in list" :key="t.id" class="txn-row">
          <div class="txn-info">
            <div class="txn-type">{{ TYPE_TEXT[t.type] || t.type }}</div>
            <div class="txn-time">{{ formatTime(t.createdAt) }}</div>
          </div>
          <div class="txn-amount" :class="isIncome(t) ? 'in' : 'out'">
            {{ isIncome(t) ? '+' : '-' }}{{ t.amount.toFixed(2) }}
          </div>
        </div>
      </template>
      <EmptyState v-else-if="!loading" text="还没有收支记录" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.wallet-page {
  padding-bottom: 30px;
}

.balance-card {
  margin: 12px;
  padding: 20px 18px;
  border-radius: 12px;
  background: linear-gradient(135deg, #ff5d7e, #ff2442);
  color: #fff;
}

.balance-label {
  font-size: 12px;
  opacity: 0.9;
}

.balance-num {
  margin-top: 8px;
  font-size: 34px;
  font-weight: 700;
}

.balance-frozen {
  margin-top: 6px;
  font-size: 12px;
  opacity: 0.9;
}

.balance-tip {
  margin-top: 10px;
  font-size: 10px;
  opacity: 0.75;
}

.recharge-card {
  margin: 12px;
  padding: 14px;
  border-radius: $radius;
  background: #fff;
}

.recharge-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 12px;
}

.recharge-quick {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.quick-btn {
  flex: 1;
  height: 34px;
  border-radius: $radius-full;
  background: $bg-secondary;
  color: $text-2;
  font-size: 13px;

  &.active {
    background: $primary-light;
    color: $primary;
    font-weight: 600;
  }
}

.recharge-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 4px 4px 14px;
  border-radius: $radius;
  background: $bg-search;
}

.recharge-symbol {
  color: $primary;
  font-weight: 600;
}

.recharge-input {
  flex: 1;
  min-width: 0;
  font-size: 15px;
}

.recharge-btn {
  height: 34px;
  padding: 0 16px;
  font-size: 13px;
}

.txn-section {
  margin: 0 12px;
  padding: 0 12px 12px;
  border-radius: $radius;
  background: #fff;
}

.txn-title {
  padding: 14px 4px 4px;
  font-size: 13px;
  font-weight: 600;
}

.txn-row {
  display: flex;
  align-items: center;
  padding: 12px 4px;
  border-bottom: 0.5px solid $line;

  &:last-child {
    border-bottom: none;
  }
}

.txn-info {
  flex: 1;
}

.txn-type {
  font-size: 14px;
}

.txn-time {
  margin-top: 2px;
  font-size: 11px;
  color: $text-4;
}

.txn-amount {
  font-size: 15px;
  font-weight: 600;

  &.in {
    color: #2e9e44;
  }

  &.out {
    color: $text-1;
  }
}
</style>
