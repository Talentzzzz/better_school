<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import NavBar from '@/components/NavBar.vue'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { ApiError } from '@/api/http'
import {
  draw,
  exchangeDrawCount,
  getActivity,
  getDrawChances,
  getPointsBalance,
  listPrizes,
  EXCHANGE_FAIL_TEXT,
  LOTTERY_CODE,
  prizeTint
} from '@/api/marketing'
import type { DrawResult, LotteryActivity, LotteryPrize } from '@/api/marketing'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const visitor = computed(() => !auth.isLoggedIn)
const ended = computed(() => activity.value?.status === 'ENDED')

// ---------- 各区块各自的加载 / 失败态（聚合读 = 前端并发调两家，不做 BFF） ----------

const activity = ref<LotteryActivity | null>(null)
const activityErr = ref(false)
const prizes = ref<LotteryPrize[] | null>(null)
const prizesErr = ref(false)
const chances = ref<{ available: number; todayDrawn: number } | null>(null)
const chancesErr = ref(false)
const points = ref<number | null>(null)
const pointsErr = ref(false)

async function loadActivity() {
  activityErr.value = false
  try {
    // 无活动时后端 data 为 null（旧模型缺行抛异常）——这里按「没有活动」呈现，不报错
    activity.value = await getActivity()
  } catch {
    activityErr.value = true
  }
}

async function loadPrizes() {
  prizesErr.value = false
  try {
    prizes.value = await listPrizes()
  } catch {
    prizesErr.value = true
  }
}

async function loadChances() {
  if (visitor.value) return
  chancesErr.value = false
  try {
    chances.value = await getDrawChances()
  } catch {
    chancesErr.value = true
  }
}

async function loadPoints() {
  if (visitor.value) return
  pointsErr.value = false
  try {
    points.value = (await getPointsBalance()).balance
  } catch {
    pointsErr.value = true
  }
}

onMounted(() => {
  loadActivity()
  loadPrizes()
  loadChances()
  loadPoints()
})

function goLogin() {
  router.push({ path: '/login', query: { redirect: '/marketing' } })
}

const daysLeft = computed(() => {
  if (!activity.value) return 0
  const end = new Date(activity.value.endAt).getTime()
  return Math.max(0, Math.ceil((end - Date.now()) / 86400000))
})

/** banner 右下角：访客提示登录；已登录显示今日已抽（次数没加载出来时不占位） */
const heroFootRight = computed(() => {
  if (visitor.value) return '登录后参与'
  return chances.value ? `今日已抽 ${chances.value.todayDrawn} 次` : ''
})

// ---------- 抽奖 ----------

const drawing = ref(false)
const drawOutcome = ref<'win' | 'thanks' | 'no-chance' | null>(null)
const drawResult = ref<DrawResult | null>(null)

const drawBtnDisabled = computed(
  () => ended.value || (!visitor.value && chances.value !== null && chances.value.available <= 0)
)
const drawBtnText = computed(() => {
  if (visitor.value) return '登录后参与'
  if (ended.value) return '活动已结束'
  if (chances.value !== null && chances.value.available <= 0) return '用积分购买次数'
  return '抽 一 次'
})

/** 中奖弹窗副文案：虚拟积分档把入账面额说清楚，实物档提示去「我的奖品」看进度 */
const winSub = computed(() => {
  const r = drawResult.value
  if (!r) return ''
  if (r.prizeType === 'VIRTUAL' && r.pointsAmount) return `${r.pointsAmount} 积分已入账`
  if (r.prizeType === 'PHYSICAL') return '实物奖请在「我的奖品」查看发货进度'
  return ''
})

async function onDraw() {
  if (visitor.value) return goLogin()
  if (ended.value || drawing.value || drawBtnDisabled.value) return
  drawing.value = true
  try {
    // token 幂等：同一次点击重试不会重复抽（服务端按 token 撞 uk 回放已存结果）
    const r = await draw(crypto.randomUUID())
    drawResult.value = r
    drawOutcome.value = r.prizeType === 'THANKS' ? 'thanks' : 'win'
    loadChances()
    if (r.prizeType === 'VIRTUAL' && r.pointsAmount) loadPoints()
  } catch (e) {
    if (e instanceof ApiError && e.code === LOTTERY_CODE.NO_CHANCE) {
      if (chances.value) chances.value.available = 0
      drawOutcome.value = 'no-chance'
    } else if (e instanceof ApiError) {
      toast(e.message)
    }
    // 网络/401 等非业务错误由 http 拦截器统一处理
  } finally {
    drawing.value = false
  }
}

const winTint = computed(() => {
  const i = prizes.value?.findIndex((p) => p.prizeId === drawResult.value?.prizeId) ?? -1
  const hit = i >= 0 ? prizes.value?.[i] : undefined
  return hit ? prizeTint(hit.prizeId) : '#fff0f3'
})

// ---------- 购买次数（唯一一条积分→次数的边：抽奖本身只扣次数） ----------

const exchangeFail = ref<{ title: string; sub: string } | null>(null)

function showExchangeFail(e: ApiError) {
  const t = EXCHANGE_FAIL_TEXT[e.code]
  if (t) exchangeFail.value = t
  else toast(e.message)
}

function guardExchange(): boolean {
  if (visitor.value) {
    goLogin()
    return false
  }
  if (ended.value) {
    toast('活动已结束')
    return false
  }
  return true
}

async function onExchangeChances() {
  if (!guardExchange()) return
  const price = activity.value?.drawCountPrice ?? 100
  const ok = await confirmDialog('购买抽奖次数', `用 ${price} 积分购买 1 次抽奖机会？`)
  if (!ok) return
  try {
    // token 幂等：重试带同一个，服务端不会重复扣积分（单号由 token 确定性映射）
    await exchangeDrawCount(crypto.randomUUID())
    toast('购买成功，抽奖次数 +1')
    loadChances()
    loadPoints()
  } catch (e) {
    if (e instanceof ApiError) showExchangeFail(e)
  }
}

// ---------- 去向 ----------
// 色块按 prizeId 取（同一奖品在目录与我的奖品里同色；按 id 取而非目录序号，页面顺序变了颜色也不跳）
// prizeId 是 UUID，取色走 prizeTint 的字符串哈希，不能对 id 取模
const tintAt = (p: LotteryPrize) => prizeTint(p.prizeId)
</script>

<template>
  <div class="page">
    <NavBar title="营销活动" />
    <div class="m-wrap">
      <!-- 1 活动 banner -->
      <div v-if="activityErr" class="m-card m-retry" @click="loadActivity">活动信息加载失败，点击重试</div>
      <div v-else-if="!activity" class="m-card m-empty">
        <div class="m-empty-title">当前没有进行中的抽奖活动</div>
        <div class="m-empty-sub">活动上线后这里会出现抽奖入口</div>
      </div>
      <div v-else class="m-hero">
        <div class="m-hero-tag">抽奖活动</div>
        <div class="m-hero-title">{{ activity.name }}</div>
        <div class="m-hero-foot">
          <span>{{ ended ? '已结束' : `剩 ${daysLeft} 天` }}</span>
          <span>{{ heroFootRight }}</span>
        </div>
      </div>

      <!-- 2 抽奖主卡 -->
      <div class="m-card m-draw">
        <div class="m-draw-label">我的抽奖次数</div>
        <div v-if="visitor" class="m-visitor" @click="goLogin">登录后参与，赢抽奖机会 ›</div>
        <div v-else-if="chancesErr" class="m-retry m-retry--inline" @click="loadChances">
          次数加载失败，点击重试
        </div>
        <template v-else>
          <div class="m-draw-num">{{ chances === null ? '—' : chances.available }}</div>
          <button class="m-draw-btn" :disabled="drawBtnDisabled || drawing" @click="onDraw">
            {{ drawing ? '抽奖中…' : drawBtnText }}
          </button>
          <div class="m-draw-foot">
            <span>{{ activity?.drawCountPrice ?? '…' }} 积分 = 1 次</span>
            <button class="m-link" @click="onExchangeChances">用积分购买次数 ›</button>
          </div>
          <div v-if="activity?.drawCountValidDays" class="m-draw-hint">
            购买的次数 {{ activity.drawCountValidDays }} 天内有效
          </div>
        </template>
      </div>

      <!-- 3 积分卡 -->
      <div class="m-card m-points">
        <div v-if="visitor" class="m-visitor" @click="goLogin">登录后参与 ›</div>
        <div v-else-if="pointsErr" class="m-retry m-retry--inline" @click="loadPoints">加载失败，点击重试</div>
        <div v-else>
          <div class="m-sec-title">我的积分</div>
          <div class="m-points-num">{{ points === null ? '—' : points }}</div>
        </div>
        <button class="m-btn-ghost" @click="onExchangeChances">兑抽奖次数 ›</button>
      </div>

      <!-- 4 奖品目录 -->
      <div ref="catalogRef" class="m-sec-head">奖品目录</div>
      <div v-if="prizesErr" class="m-card m-retry" @click="loadPrizes">奖品目录加载失败，点击重试</div>
      <div v-else-if="!prizes" class="m-card m-skeleton">奖品加载中…</div>
      <div v-else-if="!prizes.length" class="m-card m-empty">
        <div class="m-empty-title">奖品还在准备中</div>
      </div>
      <div v-else class="m-prizes">
        <div v-for="p in prizes" :key="p.prizeId" class="m-prize">
          <div class="m-prize-art" :style="{ background: tintAt(p) }">
            <span class="m-prize-tier">{{ p.tier }} 等奖</span>
            <span v-if="p.unlimited" class="m-prize-kind">不限量</span>
          </div>
          <div class="m-prize-name ellipsis">{{ p.name }}</div>
          <div class="m-prize-meta">
            <span v-if="p.unlimited">不限量</span>
            <span v-else>剩 {{ p.remaining }}/{{ p.total }}</span>
            <span v-if="p.pointsAmount" class="m-redeem">{{ p.pointsAmount }} 分</span>
            <span v-else-if="p.type === 'PHYSICAL'" class="m-redeem">实物</span>
          </div>
        </div>
      </div>

      <!-- 5 底部 -->
      <button class="m-foot-link" @click="router.push('/marketing/prizes')">我的奖品 / 中奖记录 ›</button>
    </div>

    <!-- 抽奖结果：居中弹窗 -->
    <div v-if="drawOutcome" class="m-mask" @click.self="drawOutcome = null">
      <div class="m-modal">
        <template v-if="drawOutcome === 'win' && drawResult">
          <div class="m-modal-art" :style="{ background: winTint }">
            <span class="m-modal-tier">第 {{ drawResult.tier }} 等</span>
          </div>
          <div class="m-modal-name">{{ drawResult.name }}</div>
          <div v-if="winSub" class="m-modal-sub">{{ winSub }}</div>
          <button class="m-modal-btn" @click="drawOutcome = null">开心收下</button>
        </template>
        <template v-else-if="drawOutcome === 'thanks'">
          <div class="m-modal-art m-modal-art--gray">🍀</div>
          <div class="m-modal-name">谢谢参与</div>
          <div class="m-modal-sub">再攒几次，好运在路上</div>
          <button class="m-modal-btn" @click="drawOutcome = null">知道了</button>
        </template>
        <template v-else>
          <div class="m-modal-art m-modal-art--gray">🎟️</div>
          <div class="m-modal-name">抽奖次数不足</div>
          <div class="m-modal-sub">可用积分购买次数，攒够再来</div>
          <button class="m-modal-btn" @click="drawOutcome = null; onExchangeChances()">用积分购买次数</button>
          <button class="m-modal-btn-ghost" @click="drawOutcome = null">知道了</button>
        </template>
      </div>
    </div>

    <!-- 购买次数失败：居中弹窗 -->
    <div v-if="exchangeFail" class="m-mask" @click.self="exchangeFail = null">
      <div class="m-modal">
        <div class="m-modal-art m-modal-art--gray">🪙</div>
        <div class="m-modal-name">{{ exchangeFail.title }}</div>
        <div class="m-modal-sub">{{ exchangeFail.sub }}</div>
        <button class="m-modal-btn" @click="exchangeFail = null">知道了</button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.page {
  min-height: 100vh;
  background: $bg-page-alt;
}

.m-wrap {
  padding: 10px 12px 24px;
}

.m-hero {
  padding: 18px 16px 14px;
  border-radius: $radius-card;
  background: linear-gradient(135deg, #ff5573 0%, #ff2442 55%, #d81b3c 100%);
  color: #fff;
}

.m-hero-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: $radius-full;
  background: rgba(255, 255, 255, 0.22);
  font-size: 11px;
}

.m-hero-title {
  margin-top: 10px;
  font-size: 21px;
  font-weight: 700;
}

.m-hero-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
  font-size: 11px;
  opacity: 0.85;
}

.m-card {
  margin-top: 10px;
  padding: 14px;
  border-radius: $radius-card;
  background: #fff;
  box-shadow: $shadow-card;
}

.m-sec-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-1;
}

.m-sec-head {
  margin: 18px 2px 8px;
  font-size: 15px;
  font-weight: 600;
}

.m-visitor {
  margin-top: 12px;
  font-size: 14px;
  color: $primary;
}

.m-empty {
  text-align: center;
}

.m-empty-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-2;
}

.m-empty-sub {
  margin-top: 6px;
  font-size: 12px;
  color: $text-3;
}

.m-draw {
  text-align: center;
}

.m-draw-label {
  font-size: 13px;
  color: $text-3;
}

.m-draw-num {
  margin-top: 2px;
  font-size: 44px;
  font-weight: 800;
  line-height: 1.1;
  color: $primary;
}

.m-draw-btn {
  width: 100%;
  height: 46px;
  margin-top: 12px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 2px;

  &:active {
    background: $primary-dark;
  }

  &:disabled {
    background: $text-4;
  }
}

.m-draw-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 12px;
  color: $text-3;
}

.m-draw-hint {
  margin-top: 6px;
  font-size: 11px;
  color: $text-4;
}

.m-btn-ghost {
  padding: 7px 12px;
  border-radius: $radius-full;
  border: 1px solid $line;
  font-size: 13px;
  color: $text-2;
  white-space: nowrap;
}

.m-link {
  font-size: 12px;
  color: $primary;
}

.m-points {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.m-points-num {
  margin-top: 2px;
  font-size: 26px;
  font-weight: 800;
  color: $primary;
}

.m-prizes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.m-prize {
  padding: 8px;
  border-radius: $radius-card;
  background: #fff;
  box-shadow: $shadow-card;
}

.m-prize-art {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 84px;
  border-radius: $radius;
}

.m-prize-tier {
  font-size: 17px;
  font-weight: 800;
  color: rgba(0, 0, 0, 0.62);
}

.m-prize-kind {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 7px;
  border-radius: $radius-full;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 10px;
}

.m-prize-name {
  margin-top: 7px;
  font-size: 13px;
  font-weight: 500;
}

.m-prize-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: $text-3;
}

.m-redeem {
  color: $primary;
}

.m-foot-link {
  display: block;
  width: 100%;
  margin-top: 16px;
  padding: 12px;
  font-size: 13px;
  color: $text-3;
}

.m-retry {
  color: $text-3;
  font-size: 13px;
  text-align: center;

  &--inline {
    margin-top: 10px;
    text-align: left;
  }
}

.m-skeleton {
  color: $text-4;
  font-size: 13px;
  text-align: center;
}

.m-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
}

.m-modal {
  width: 260px;
  padding: 24px 20px 18px;
  border-radius: 18px;
  background: #fff;
  text-align: center;
}

.m-modal-art {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  margin: 0 auto 12px;
  border-radius: 24px;
  font-size: 48px;

  &--gray {
    background: $bg-secondary;
  }
}

.m-modal-tier {
  font-size: 22px;
  font-weight: 800;
  color: rgba(0, 0, 0, 0.62);
}

.m-modal-name {
  margin-top: 2px;
  font-size: 18px;
  font-weight: 700;
}

.m-modal-sub {
  margin-top: 6px;
  font-size: 12px;
  color: $text-3;
}

.m-modal-btn {
  width: 100%;
  height: 42px;
  margin-top: 16px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.m-modal-btn-ghost {
  width: 100%;
  height: 38px;
  margin-top: 8px;
  font-size: 13px;
  color: $text-3;
}
</style>
