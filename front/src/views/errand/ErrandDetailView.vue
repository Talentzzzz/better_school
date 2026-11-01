<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  abandonErrandOrder,
  acceptErrandOrder,
  cancelErrandOrder,
  confirmErrandOrder,
  deleteErrandOrder,
  deliverErrandOrder,
  disputeErrandOrder,
  getErrandOrder,
  grabErrandOrder,
  payErrandOrder,
  pickupErrandOrder,
  submitErrandDraft
} from '@/api/errand'
import { createDirectRoom } from '@/api/chat'
import type { ErrandOrder } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatMoney, formatTime } from '@/utils/format'
import { ERRAND_STATUS_TEXT, errandTypeText } from '@/utils/errand'
import NavBar from '@/components/NavBar.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import AppImage from '@/components/AppImage.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const orderId = Number(route.params.id)
const order = ref<ErrandOrder | null>(null)
const busy = ref(false)

const isPublisher = computed(() => !!auth.userId && order.value?.publisherId === auth.userId)
const isTaker = computed(() => !!auth.userId && order.value?.takerId === auth.userId)
const statusText = computed(() => ERRAND_STATUS_TEXT[order.value?.status ?? ''] ?? order.value?.status)
// 支付冻结金额 = 悬赏（支付后托管冻结，完成时划转给接单者）
const payAmount = computed(() => order.value?.reward ?? 0)

async function load() {
  order.value = await getErrandOrder(orderId)
}

function requireLogin(): boolean {
  if (auth.isLoggedIn) return true
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function doAction(fn: () => Promise<unknown>, successMsg: string, needConfirm?: string, afterSuccess?: () => void) {
  if (!requireLogin()) return
  if (needConfirm && !(await confirmDialog(needConfirm))) return
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    toast(successMsg)
    // 默认重载本页；软删这类"重载即 404"的动作改为跳走（afterSuccess）
    if (afterSuccess) afterSuccess()
    else await load()
  } catch {
    // toast 已处理
  } finally {
    busy.value = false
  }
}

const onPay = () => doAction(() => payErrandOrder(orderId), '支付成功，悬赏已冻结', '确认支付？支付后悬赏资金将托管冻结')
// 抢单只拿到锁单资格（抢 ≠ 开工），核对信息后再点「确认接单」
const onGrab = () => doAction(() => grabErrandOrder(orderId), '抢单成功，请确认接单', '确认接下这一单？抢到后需自行确认接单')
// 锁单中的两条出路：确认接单开工，或放弃让单（放弃不扣信用分）
const onAcceptLocked = () => doAction(() => acceptErrandOrder(orderId), '已确认接单，可开始跑腿')
const onAbandonLocked = () =>
  doAction(() => abandonErrandOrder(orderId), '已放弃，订单回到待接单', '确认放弃这一单？放弃后订单回到待接单，他人可再抢')
const onPickup = () => doAction(() => pickupErrandOrder(orderId), '取货成功')
const onDeliver = () => doAction(() => deliverErrandOrder(orderId), '已送达', '确认已送达？')
const onConfirm = () =>
  doAction(() => confirmErrandOrder(orderId), '订单完成，悬赏已划转', '确认已完成并支付悬赏？')
const onCancel = () => {
  const status = order.value?.status
  let msg = '确认取消订单？'
  if (status === 'PENDING_REVIEW') {
    msg += '\n待审期取消，冻结悬赏将全额退回'
  } else if (status === 'CONFIRMED' && isTaker.value) {
    msg += '\n接单后取消将扣除 20 信用分！'
  }
  const successMsg = status === 'PENDING_REVIEW' ? '订单已取消，冻结悬赏将全额退回' : '订单已取消'
  return doAction(() => cancelErrandOrder(orderId), successMsg, msg)
}
const onDispute = () =>
  doAction(() => disputeErrandOrder(orderId), '已提交争议，等待管理员处理', '确认发起争议？')
// 草稿：提交进支付流程（必填 + 同步初筛由后端把关，命中则停留草稿）
const onSubmitDraft = () =>
  doAction(() => submitErrandDraft(orderId), '草稿已提交，请完成支付', '确认提交？提交后进入待支付，15 分钟内需完成支付')
// 草稿：软删后连同详情一起消失，故成功后回「我的-跑腿」而不是重载本页
const onDeleteDraft = () =>
  doAction(() => deleteErrandOrder(orderId), '草稿已删除', '确认删除这份草稿？删除后不可恢复',
    () => router.replace('/me/orders?tab=errand'))

async function onContact(targetUserId: number | null | undefined) {
  if (!targetUserId) {
    toast('对方信息不可用')
    return
  }
  if (!requireLogin()) return
  const room = await createDirectRoom(targetUserId, 'ERRAND')
  chatRooms.scheduleRefresh()
  router.push(`/chat/${room.roomId}`)
}

onMounted(load)
</script>

<template>
  <div class="page--plain errand-detail">
    <NavBar title="跑腿详情" />

    <template v-if="order">
      <div class="status-hero">
        <div class="status-text">{{ statusText }}</div>
        <div class="status-note">
          {{ order.status === 'DRAFT' ? '草稿未提交，提交后进入支付流程' : '' }}
          {{ order.status === 'LOCKED' ? '已被接单方锁定，等待其确认接单（抢到不等于开工）' : '' }}
          {{ order.status === 'DELIVERED' ? '等待发布者确认完成，确认后悬赏自动划转' : '' }}
          {{ order.status === 'PENDING_PAYMENT' ? '发布者还未支付悬赏资金' : '' }}
          {{ order.status === 'PENDING_REVIEW' ? '支付成功，悬赏已冻结托管，审核通过后对外可见' : '' }}
          {{ order.status === 'REJECTED' ? '订单未通过审核，冻结悬赏已全额退回' : '' }}
          {{ order.status === 'DISPUTED' ? '订单已进入仲裁，等待管理员处理' : '' }}
          {{ order.status === 'CANCELLED' ? '订单已关闭' : '' }}
        </div>
      </div>

      <div v-if="isPublisher && order.status === 'REJECTED' && order.rejectReason" class="detail-card reject-card">
        <div class="reject-title">审核未通过理由</div>
        <div class="reject-reason">{{ order.rejectReason }}</div>
        <div class="reject-refund">冻结悬赏已全额退回钱包，可修改后重新发布</div>
      </div>

      <div class="detail-card">
        <div class="reward-row">
          <span class="reward-label">悬赏金额</span>
          <span class="reward-value">{{ order.reward == null ? '—' : '¥' + formatMoney(order.reward) }}</span>
        </div>
        <div class="detail-row"><span class="detail-label">类型</span><span class="detail-text">{{ errandTypeText(order.type) }}</span></div>
        <div class="detail-row"><span class="detail-label">要求</span><span class="detail-text">{{ order.deadline ? '截止 ' + formatTime(order.deadline) : '—' }}</span></div>
        <!-- 浏览量（market-feed-uv 票 05）：按用户去重的 UV，与闲置详情「浏览 N」同一口径 -->
        <div class="detail-row"><span class="detail-label">浏览</span><span class="detail-text">{{ order.viewCount }}</span></div>
      </div>

      <div class="detail-card">
        <div class="desc-title">任务描述</div>
        <div class="desc-text">{{ order.description }}</div>
        <div class="loc-box">
          <div class="loc-line">
            <span class="loc-ico">取</span>
            <span class="loc-text">{{ order.pickupLocation }}</span>
          </div>
          <div class="loc-arrow">↓</div>
          <div class="loc-line">
            <span class="loc-ico loc-ico--end">送</span>
            <span class="loc-text">{{ order.deliveryLocation }}</span>
          </div>
        </div>
        <div v-if="order.images?.length" class="order-images">
          <AppImage
            v-for="(img, i) in order.images"
            :key="i"
            :src="img"
            :seed="order.orderId + '-' + i"
            ratio="1:1"
          />
        </div>
      </div>

      <div class="detail-card people-card">
        <div class="people-row">
          <button class="people-main" @click="router.push(`/user/${order.publisherId}`)">
            <AppAvatar :src="order.publisherAvatar" :name="order.publisherNickname" :size="40" />
            <div class="people-info">
              <div class="people-name">{{ order.publisherNickname }}<span class="role-tag">发布者</span></div>
              <div class="people-time">发布于 {{ formatTime(order.createdAt) }}</div>
            </div>
          </button>
          <button v-if="!isPublisher" class="btn-outline people-btn" @click="onContact(order.publisherId)">联系</button>
        </div>
        <div v-if="order.takerId" class="people-row">
          <button class="people-main" @click="router.push(`/user/${order.takerId}`)">
            <AppAvatar :src="order.takerAvatar" :name="order.takerNickname ?? '接单用户'" :size="40" />
            <div class="people-info">
              <div class="people-name">{{ order.takerNickname }}<span class="role-tag role-tag--green">接单者</span></div>
              <div class="people-time">悬赏将支付给接单者</div>
            </div>
          </button>
          <button v-if="!isTaker" class="btn-outline people-btn" @click="onContact(order.takerId)">联系</button>
        </div>
      </div>
    </template>

    <div v-else class="errand-loading">加载中…</div>

    <!-- 操作按钮区 -->
    <footer v-if="order" class="action-bar safe-bottom">
      <div v-if="order.status === 'DRAFT' && isPublisher" class="action-group">
        <button class="btn-outline" :disabled="busy" @click="onDeleteDraft">删除草稿</button>
        <button class="btn-primary" :disabled="busy" @click="onSubmitDraft">提交并去支付</button>
      </div>

      <div v-else-if="order.status === 'PENDING_PAYMENT' && isPublisher" class="action-group">
        <button class="btn-outline" :disabled="busy" @click="onCancel">取消</button>
        <button class="btn-primary" :disabled="busy" @click="onPay">立即支付 ¥{{ formatMoney(payAmount) }}</button>
      </div>

      <div v-else-if="order.status === 'PENDING_REVIEW' && isPublisher" class="action-group">
        <span class="pending-hint">人工审核中，通过后对外可见</span>
        <button class="btn-outline" :disabled="busy" @click="onCancel">取消并退款</button>
      </div>

      <div v-else-if="order.status === 'LOCKED'" class="action-group">
        <button v-if="isTaker" class="btn-outline" :disabled="busy" @click="onAbandonLocked">放弃</button>
        <button v-if="isTaker" class="btn-primary" :disabled="busy" @click="onAcceptLocked">确认接单</button>
        <button v-else-if="isPublisher" class="btn-outline" :disabled="busy" @click="onCancel">取消订单</button>
        <span v-else class="pending-hint">已被他人锁定，等待确认接单</span>
      </div>

      <div v-else-if="order.status === 'PUBLISHED'" class="action-group">
        <button v-if="isPublisher" class="btn-outline" :disabled="busy" @click="onCancel">取消订单</button>
        <button v-else class="btn-primary" :disabled="busy" @click="onGrab">立即抢单 ¥{{ formatMoney(order.reward) }}</button>
      </div>

      <div v-else-if="order.status === 'CONFIRMED'" class="action-group">
        <button class="btn-outline" :disabled="busy" @click="onCancel">取消</button>
        <button v-if="isTaker" class="btn-primary" :disabled="busy" @click="onPickup">确认取货</button>
        <button v-else class="btn-outline" :disabled="busy" @click="onDispute">发起争议</button>
      </div>

      <div v-else-if="order.status === 'PICKED_UP'" class="action-group">
        <button v-if="isTaker" class="btn-primary" :disabled="busy" @click="onDeliver">确认送达</button>
        <button v-else class="btn-outline" :disabled="busy" @click="onDispute">发起争议</button>
      </div>

      <div v-else-if="order.status === 'DELIVERED'" class="action-group">
        <button v-if="isPublisher" class="btn-primary" :disabled="busy" @click="onConfirm">确认完成并支付</button>
        <button v-else-if="isTaker" class="btn-text" disabled>等待发布者确认</button>
        <button v-else class="btn-outline" :disabled="busy" @click="onDispute">发起争议</button>
      </div>

      <div v-else-if="order.status === 'COMPLETED'" class="action-group">
        <span class="pending-hint">订单已完成</span>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.status-hero {
  padding: 20px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.status-text {
  font-size: 22px;
  font-weight: 700;
  color: $primary;
}

.status-note {
  font-size: 12px;
  color: $text-3;
}

.detail-card {
  margin: 10px;
  padding: 14px;
  border-radius: $radius;
  background: #fff;
}

.reward-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 0.5px solid $line;
}

.reward-label {
  font-size: 13px;
  color: $text-2;
}

.reward-value {
  font-size: 24px;
  font-weight: 700;
  color: $primary;
}

.detail-row {
  display: flex;
  margin-top: 10px;
  gap: 12px;
}

.detail-label {
  flex-shrink: 0;
  width: 48px;
  color: $text-3;
  font-size: 13px;
}

.detail-text {
  font-size: 13px;
}

.desc-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.desc-text {
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.loc-box {
  margin-top: 12px;
  padding: 10px;
  border-radius: $radius-sm;
  background: $bg-secondary;
}

.loc-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.loc-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: $primary;
  color: #fff;
  font-size: 10px;
  flex-shrink: 0;

  &--end {
    background: $blue;
  }
}

.loc-text {
  word-break: break-all;
}

.loc-arrow {
  padding-left: 6px;
  color: $text-4;
  font-size: 11px;
  line-height: 1.2;
}

.order-images {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.reject-card {
  border: 1px solid #ffd6d6;
  background: #fff5f5;
}

.reject-title {
  font-size: 13px;
  font-weight: 600;
  color: #c0392b;
}

.reject-reason {
  margin-top: 6px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.reject-refund {
  margin-top: 8px;
  font-size: 11px;
  color: $text-3;
}

.people-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.people-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.people-main {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  text-align: left;

  &:active {
    opacity: 0.7;
  }
}

.people-info {
  flex: 1;
  min-width: 0;
}

.people-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
}

.role-tag {
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: $primary-light;
  color: $primary;
  font-size: 10px;
  font-weight: 400;

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

.people-btn {
  height: 30px;
  font-size: 12px;
}

.errand-loading {
  padding: 48px 0;
  text-align: center;
  color: $text-3;
}

.action-bar {
  position: sticky;
  bottom: 0;
  padding: 10px 12px;
  background: #fff;
  border-top: 0.5px solid $line;
}

.action-group {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;

  .btn-primary {
    flex: 1;
  }

  .btn-outline {
    flex: 1;
  }

  .pending-hint {
    flex: 1;
    text-align: center;
    font-size: 12px;
    color: $text-3;
  }
}
</style>
