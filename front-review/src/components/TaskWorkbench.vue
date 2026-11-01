<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useConsoleStore } from '@/stores/console'
import { REJECT_REASONS, reasonLabel, typeMeta, type RejectReasonCode } from '@/types/review'
import { formatClock, formatMoney, formatWait } from '@/utils/format'

const store = useConsoleStore()

const rejecting = ref(false)
const reasonCode = ref<RejectReasonCode | null>(null)
const supplement = ref('')

const t = computed(() => store.current)
const d = computed(() => store.current?.detail ?? null)

/** 租约告急等级 */
const leaseLevel = computed(() => {
  const r = store.leaseRatio
  if (r <= 0.15) return 'critical'
  if (r <= 0.35) return 'warn'
  return 'normal'
})

function resetReject() {
  rejecting.value = false
  reasonCode.value = null
  supplement.value = ''
}

watch(t, () => resetReject())

function startReject() {
  rejecting.value = true
  reasonCode.value = null
  supplement.value = ''
}

function confirmReject() {
  if (!reasonCode.value) return
  store.decide(false, reasonCode.value, supplement.value)
}

function onKey(e: KeyboardEvent) {
  if (!t.value) return
  if (e.key === 'Escape') {
    if (rejecting.value) resetReject()
    return
  }
  const el = e.target as HTMLElement | null
  const typing = el?.tagName === 'TEXTAREA' || el?.tagName === 'INPUT'
  if (typing) return
  const k = e.key.toLowerCase()
  if (k === 'a') store.decide(true)
  else if (k === 'r') startReject()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <section class="workbench card">
    <!-- 空态：手中无单 -->
    <div v-if="!t" class="empty">
      <div class="empty-icon">◲</div>
      <div class="empty-title">手中无单</div>
      <p class="empty-desc">
        审核中台采用「推信号 + 拉工单」：推送只负责唤醒（顶部有新工单提示），<br />
        取活靠你自己拉，<b>一次只拉一条</b>，手里审完才能再拉下一条。
      </p>
      <button class="pull-btn" :disabled="store.bannerMode !== 'signal'" @click="store.pullOne()">
        {{ store.bannerMode === 'signal' ? `从队列拉一条（积压 ${store.backlogTotal}）` : '队列暂无可拉工单' }}
      </button>
      <div class="empty-tip">热键：A 通过 · R 不通过 · Esc 取消</div>
    </div>

    <!-- 单条聚焦视图 -->
    <template v-else-if="t && d">
      <div class="wb-head">
        <span
          class="type-badge"
          :style="{ color: typeMeta(d.targetType).color, background: typeMeta(d.targetType).soft }"
        >
          {{ typeMeta(d.targetType).label }}
        </span>
        <span class="tid">#{{ d.taskId }} · 目标 {{ d.targetType.toLowerCase() }}-{{ d.targetId }}</span>
        <span v-if="d.reviewRound > 1" class="round-tag">第 {{ d.reviewRound }} 轮审核</span>
        <span v-if="t.claim.recycleCount > 0" class="round-tag recycled">已回收 {{ t.claim.recycleCount }} 次</span>
        <span v-if="t.claim.escalated" class="round-tag escalated">⚠ 反复超时 · 需重点关注</span>
        <span class="wait-tag">入队已 {{ formatWait(Date.now() - Date.parse(d.enteredAt)) }}</span>
      </div>

      <!-- 租约倒计时 -->
      <div class="lease" :class="leaseLevel">
        <div class="lease-row">
          <span class="lease-label">租约剩余</span>
          <span class="lease-clock">{{ formatClock(store.leaseLeft) }}</span>
          <span class="lease-note">到点未裁决将自动回收回队列</span>
        </div>
        <div class="lease-bar">
          <div class="lease-fill" :style="{ width: store.leaseRatio * 100 + '%' }" />
        </div>
      </div>

      <div class="wb-body">
        <!-- 内容素材 -->
        <div class="block">
          <div class="block-title">内容素材</div>

          <!-- 评论：所属帖子 / 父评论上下文 -->
          <div v-if="d.contextPostTitle" class="context">
            <span class="ctx-tag">所属帖子</span>{{ d.contextPostTitle }}
          </div>
          <div v-if="d.contextParentContent" class="context">
            <span class="ctx-tag">回复对象</span>{{ d.contextParentContent }}
          </div>

          <div class="content-body">{{ d.content }}</div>

          <div v-if="d.reward != null" class="money-row">
            <span class="money" :class="{ reward: d.targetType === 'ERRAND' }">
              {{ d.targetType === 'ERRAND' ? '悬赏' : '售价' }} ¥{{ formatMoney(d.reward) }}
            </span>
          </div>

          <div v-if="d.images?.length" class="image-grid">
            <img v-for="(img, i) in d.images" :key="i" :src="img" alt="配图" loading="lazy" />
          </div>
        </div>

        <!-- 作者 + 历史裁决 -->
        <div class="side-blocks">
          <div class="block author-block">
            <div class="block-title">作者信息</div>
            <div class="author">
              <div class="author-avatar">
                <img v-if="d.authorAvatar" :src="d.authorAvatar" alt="" />
                <template v-else>{{ (d.authorNickname ?? '匿').slice(0, 1) }}</template>
              </div>
              <div class="author-main">
                <div class="author-name">{{ d.authorNickname ?? '匿名用户' }}</div>
                <div class="author-id">UID {{ d.authorId }}</div>
              </div>
            </div>
          </div>

          <div class="block history-block">
            <div class="block-title">历史裁决</div>
            <template v-if="d.targetReviews.length || d.authorReviews.length">
              <div v-if="d.targetReviews.length" class="history-group">
                <div class="history-group-title">该内容历轮</div>
                <ul class="history-list">
                  <li v-for="h in d.targetReviews" :key="h.reviewId" class="history-item">
                    <span class="h-round">R{{ h.reviewRound }}</span>
                    <span class="h-action" :class="h.action === 'APPROVE' ? 'pass' : 'reject'">
                      {{ h.action === 'APPROVE' ? '通过' : '不通过' }}
                    </span>
                    <span v-if="h.reasonCode" class="h-reason">· {{ reasonLabel(h.reasonCode) }}</span>
                    <div class="h-meta">
                      <span v-if="h.reason" class="h-note">{{ h.reason }}</span>
                      <span class="h-by">操作者 #{{ h.operatorId ?? '—' }} · {{ h.createdAt }}</span>
                    </div>
                  </li>
                </ul>
              </div>
              <div v-if="d.authorReviews.length" class="history-group">
                <div class="history-group-title">该作者近期（最多 20 条）</div>
                <ul class="history-list">
                  <li v-for="h in d.authorReviews" :key="h.reviewId" class="history-item">
                    <span class="h-round">{{ h.targetType.toLowerCase() }}-{{ h.targetId }}</span>
                    <span class="h-action" :class="h.action === 'APPROVE' ? 'pass' : 'reject'">
                      {{ h.action === 'APPROVE' ? '通过' : '不通过' }}
                    </span>
                    <span v-if="h.reasonCode" class="h-reason">· {{ reasonLabel(h.reasonCode) }}</span>
                    <div class="h-meta">
                      <span class="h-by">{{ h.createdAt }}</span>
                    </div>
                  </li>
                </ul>
              </div>
            </template>
            <div v-else class="history-empty">该作者 / 该内容暂无历史裁决记录</div>
          </div>
        </div>
      </div>

      <!-- 裁决区 -->
      <div class="actions">
        <template v-if="!rejecting">
          <div class="hotkey-hint">A 通过 · R 不通过</div>
          <button class="btn-release" @click="store.release()">放回</button>
          <button class="btn-reject" @click="startReject">不通过</button>
          <button class="btn-approve" @click="store.decide(true)">通过</button>
        </template>

        <div v-else class="reject-box">
          <div class="reject-label">不通过理由（必选，与全站口径统一 · 将通知作者）</div>
          <div class="reject-chips">
            <button
              v-for="r in REJECT_REASONS"
              :key="r.code"
              class="reject-chip"
              :class="{ active: reasonCode === r.code }"
              @click="reasonCode = r.code"
            >
              {{ r.label }}
            </button>
          </div>
          <textarea
            v-model="supplement"
            class="reject-input"
            maxlength="194"
            placeholder="补充说明（可选，如：删除二维码后可重新发布）"
          />
          <div class="reject-actions">
            <button class="btn-cancel" @click="resetReject">取消</button>
            <button class="btn-danger" :disabled="!reasonCode" @click="confirmReject">确认不通过</button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.workbench {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* ---- 空态 ---- */
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  text-align: center;
}

.empty-icon {
  font-size: 52px;
  color: $bg-3;
  line-height: 1;
}

.empty-title {
  margin-top: 14px;
  font-size: 17px;
  font-weight: 700;
}

.empty-desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.9;
  color: $text-3;

  b {
    color: $text-2;
  }
}

.empty-tip {
  margin-top: 16px;
  font-size: 11px;
  color: $text-4;
}

.pull-btn {
  margin-top: 20px;
  height: 40px;
  padding: 0 26px;
  border-radius: $radius-full;
  background: $brand;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 8px 22px rgba(255, 36, 66, 0.3);

  &:disabled {
    background: $bg-3;
    color: $text-3;
    box-shadow: none;
  }
}

/* ---- 头部 ---- */
.wb-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 18px 10px;
  flex-wrap: wrap;
}

.type-badge {
  height: 21px;
  padding: 0 9px;
  border-radius: $radius-sm;
  font-size: 12px;
  font-weight: 700;
  line-height: 21px;
}

.tid {
  font-size: 12px;
  color: $text-3;
  font-family: ui-monospace, monospace;
}

.round-tag {
  padding: 2px 8px;
  border-radius: $radius-full;
  background: $amber-soft;
  color: $amber;
  font-size: 11px;
  font-weight: 600;

  &.recycled {
    background: $bg-3;
    color: $text-2;
  }

  &.escalated {
    background: $red-soft;
    color: $red;
  }
}

.wait-tag {
  margin-left: auto;
  font-size: 11px;
  color: $text-3;
}

/* ---- 租约 ---- */
.lease {
  margin: 0 18px;
  padding: 10px 14px;
  border-radius: $radius;
  background: $bg-2;
  border: 1px solid $line-soft;

  &.warn {
    background: $amber-soft;
    border-color: rgba(245, 166, 35, 0.35);
    .lease-clock {
      color: $amber;
    }
  }

  &.critical {
    background: $red-soft;
    border-color: rgba(255, 92, 108, 0.4);
    .lease-clock {
      color: $red;
    }
  }
}

.lease-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.lease-label {
  font-size: 12px;
  color: $text-2;
}

.lease-clock {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, monospace;
}

.lease-note {
  margin-left: auto;
  font-size: 11px;
  color: $text-3;
}

.lease-bar {
  margin-top: 8px;
  height: 4px;
  border-radius: $radius-full;
  background: $bg-3;
  overflow: hidden;
}

.lease-fill {
  height: 100%;
  background: $blue;
  transition: width 0.95s linear;

  .warn & {
    background: $amber;
  }
  .critical & {
    background: $red;
  }
}

/* ---- 主体 ---- */
.wb-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(300px, 400px);
  align-items: start;
  gap: 16px;
  padding: 16px 18px;
}

.block {
  padding: 14px;
  border-radius: $radius;
  background: $bg-2;
  border: 1px solid $line-soft;
}

.block-title {
  font-size: 12px;
  font-weight: 700;
  color: $text-2;
  letter-spacing: 0.5px;
  margin-bottom: 10px;
}

.context {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: $radius-sm;
  background: $bg-1;
  border-left: 2px solid $teal;
  font-size: 12px;
  color: $text-2;
  line-height: 1.5;
}

.ctx-tag {
  display: inline-block;
  margin-right: 6px;
  color: $teal;
  font-weight: 600;
}

.content-body {
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  color: $text-1;
}

.money-row {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.money {
  padding: 4px 12px;
  border-radius: $radius-full;
  background: $bg-1;
  border: 1px solid $line;
  font-size: 13px;
  font-weight: 700;
  color: $text-1;

  &.reward {
    color: $brand;
    border-color: rgba(255, 36, 66, 0.35);
    background: $brand-soft;
  }
}

.image-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;

  img {
    width: 100%;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: $radius-sm;
    border: 1px solid $line;
  }
}

.side-blocks {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

/* ---- 作者 ---- */
.author {
  display: flex;
  align-items: center;
  gap: 10px;
}

.author-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3a4a66, #22303f);
  display: grid;
  place-items: center;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.author-main {
  flex: 1;
  min-width: 0;
}

.author-name {
  font-size: 14px;
  font-weight: 600;
}

.author-id {
  font-size: 11px;
  color: $text-3;
  font-family: ui-monospace, monospace;
}

/* ---- 历史 ---- */
.history-group + .history-group {
  margin-top: 12px;
}

.history-group-title {
  font-size: 11px;
  color: $text-3;
  margin-bottom: 6px;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-item {
  padding: 9px 10px;
  border-radius: $radius-sm;
  background: $bg-1;
  font-size: 12px;
  line-height: 1.6;
}

.h-round {
  font-weight: 700;
  color: $text-3;
  margin-right: 6px;
  font-family: ui-monospace, monospace;
}

.h-action {
  font-weight: 700;

  &.pass {
    color: $green;
  }
  &.reject {
    color: $red;
  }
}

.h-reason {
  color: $text-2;
}

.h-meta {
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.h-note {
  color: $text-3;
}

.h-by {
  color: $text-4;
  font-size: 11px;
}

.history-empty {
  font-size: 12px;
  color: $text-3;
  padding: 6px 0;
}

/* ---- 裁决 ---- */
.actions {
  padding: 12px 18px 16px;
  border-top: 1px solid $line-soft;
  background: $bg-1;
}

.actions > .hotkey-hint {
  float: left;
  line-height: 40px;
  font-size: 11px;
  color: $text-3;
  font-family: ui-monospace, monospace;
}

.btn-approve,
.btn-reject,
.btn-release {
  float: right;
  height: 40px;
  padding: 0 30px;
  border-radius: $radius-full;
  font-size: 14px;
  font-weight: 600;
  margin-left: 10px;
  transition: 0.15s;
}

.btn-approve {
  background: $green;
  color: #06231a;

  &:hover {
    filter: brightness(1.08);
  }
}

.btn-reject {
  background: $bg-3;
  color: $red;
  border: 1px solid rgba(255, 92, 108, 0.35);

  &:hover {
    background: $red-soft;
  }
}

.btn-release {
  background: transparent;
  color: $text-2;
  border: 1px solid $line;
  padding: 0 18px;
  font-size: 13px;

  &:hover {
    background: $bg-2;
    color: $text-1;
  }
}

.actions::after {
  content: '';
  display: table;
  clear: both;
}

/* ---- 不通过面板 ---- */
.reject-box {
  width: 100%;
}

.reject-label {
  font-size: 12px;
  color: $text-2;
  margin-bottom: 9px;
}

.reject-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.reject-chip {
  height: 30px;
  padding: 0 14px;
  border-radius: $radius-full;
  background: $bg-2;
  border: 1px solid $line;
  color: $text-2;
  font-size: 12px;
  transition: 0.15s;

  &:hover {
    background: $bg-3;
  }

  &.active {
    background: $red-soft;
    border-color: rgba(255, 92, 108, 0.5);
    color: $red;
    font-weight: 600;
  }
}

.reject-input {
  width: 100%;
  min-height: 62px;
  resize: vertical;
  padding: 10px 12px;
  border-radius: $radius-sm;
  background: $bg-2;
  border: 1px solid $line;
  color: $text-1;
  font-size: 13px;
  outline: none;

  &:focus {
    border-color: rgba(255, 92, 108, 0.5);
  }
}

.reject-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.btn-cancel {
  height: 38px;
  padding: 0 22px;
  border-radius: $radius-full;
  background: $bg-2;
  border: 1px solid $line;
  color: $text-2;
  font-size: 13px;
}

.btn-danger {
  height: 38px;
  padding: 0 26px;
  border-radius: $radius-full;
  background: $red;
  color: #2a0509;
  font-size: 13px;
  font-weight: 700;

  &:disabled {
    background: $bg-3;
    color: $text-3;
  }
}
</style>
