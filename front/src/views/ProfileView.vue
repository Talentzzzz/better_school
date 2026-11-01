<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useUiStateStore, type ProfileMainTab } from '@/stores/uiState'
import { getMyCredit } from '@/api/credit'
import { listMyPosts, listLikedPosts, listUserComments } from '@/api/community'
import { getPublicProfile } from '@/api/user'
import { listMyProducts } from '@/api/trade'
import { listMyErrandOrders } from '@/api/errand'
import type { AuthorProfileComment, ErrandOrder, PostSummary, Product, PublicUserProfile } from '@/types/api'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatCount, formatMoney, formatTime } from '@/utils/format'
import { ERRAND_STATUS_TEXT } from '@/utils/errand'
import { DRAFT_BOX_STATUSES, POST_STATUS_TEXT } from '@/utils/postStatus'
import AppAvatar from '@/components/AppAvatar.vue'
import AppImage from '@/components/AppImage.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProfileCommentRow from '@/components/ProfileCommentRow.vue'
import SettingsSheet from '@/components/SettingsSheet.vue'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStateStore()

const profile = ref<PublicUserProfile | null>(null)
const showSettings = ref(false)
const creditScore = ref<number | null>(null)
const posts = ref<PostSummary[]>([])
const likedPosts = ref<PostSummary[]>([])
const myComments = ref<AuthorProfileComment[]>([])
const myProducts = ref<Product[]>([])
const pubErrands = ref<ErrandOrder[]>([])
const errandsLoading = ref(false)
const loading = ref(true)
const tabLoading = ref(false)

const STATUS_TEXT: Record<string, string> = {
  NORMAL: '正常',
  ...POST_STATUS_TEXT,
  DELETED: '已删除'
}

const PRODUCT_STATUS_TEXT: Record<string, string> = {
  ON_SALE: '在售',
  LOCKED: '交易中',
  SOLD: '已出',
  DRAFT: '草稿'
}

const MAIN_TABS: { key: ProfileMainTab; label: string }[] = [
  { key: 'posts', label: '笔记' },
  { key: 'liked', label: '赞过' },
  { key: 'comments', label: '评论' },
  { key: 'products', label: '闲置' },
  { key: 'errands', label: '跑腿' }
]

/** 性别圆底标（与作者主页资料区同口径；未设置性别则不展示） */
const genderGlyph = computed(() => {
  const g = profile.value?.gender
  return g === 'FEMALE' ? '♀' : g === 'MALE' ? '♂' : ''
})

/** 草稿箱在存帖子数：草稿 + 待审 + 被拒（与草稿箱页同一口径） */
const draftBoxCount = computed(() => posts.value.filter((p) => DRAFT_BOX_STATUSES.includes((p.status ?? '') as never)).length)

/** 同一次停留内已加载过的 tab：按 store 恢复的 tab 首次进入也要拉一次 */
const loadedTabs = new Set<ProfileMainTab>()

async function loadTab(target: ProfileMainTab, showSpinner = false) {
  if (loadedTabs.has(target)) return
  loadedTabs.add(target)
  if (showSpinner) tabLoading.value = true
  try {
    await TAB_LOADERS[target]()
  } finally {
    if (showSpinner) tabLoading.value = false
  }
}

async function load() {
  if (!auth.isLoggedIn || !auth.userId) return
  loading.value = true
  try {
    const [credit, me] = await Promise.all([
      getMyCredit().catch(() => null),
      getPublicProfile(auth.userId).catch(() => null)
    ])
    creditScore.value = credit?.creditScore ?? null
    profile.value = me
    await loadTab(ui.profileTab, true)
  } finally {
    loading.value = false
  }
}

const TAB_LOADERS: Record<ProfileMainTab, () => Promise<void>> = {
  posts: async () => {
    posts.value = (await listMyPosts(1, 50).catch(() => null))?.list ?? posts.value
  },
  liked: async () => {
    likedPosts.value = (await listLikedPosts(1, 50).catch(() => null))?.list ?? likedPosts.value
  },
  comments: async () => {
    if (!auth.userId) return
    myComments.value = (await listUserComments(auth.userId, 1, 50).catch(() => null))?.list ?? myComments.value
  },
  errands: loadErrands,
  products: async () => {
    myProducts.value = (await listMyProducts(1, 50).catch(() => null))?.list ?? myProducts.value
  }
}

async function loadErrands() {
  errandsLoading.value = true
  try {
    pubErrands.value = (await listMyErrandOrders('publish', { pageSize: 50 }).catch(() => null))?.list ?? []
  } finally {
    errandsLoading.value = false
  }
}

// 同页内切 tab：离开存本 tab 的 scrollY，切入恢复（数据留在这里，位置才自洽）
const scrollPos = {} as Record<ProfileMainTab, number>

watch(
  () => ui.profileTab,
  (next, prev) => {
    scrollPos[prev] = window.scrollY
    loadTab(next, true)
    nextTick(() => window.scrollTo(0, scrollPos[next] ?? 0))
  }
)

function creditLevel(score: number): { text: string; cls: string } {
  if (score >= 80) return { text: '优良', cls: 'good' }
  if (score >= 60) return { text: '正常', cls: 'normal' }
  if (score > 0) return { text: '警告', cls: 'warn' }
  return { text: '封禁', cls: 'bad' }
}

async function onLogout() {
  const ok = await confirmDialog('退出登录？')
  if (!ok) return
  auth.clearAuth()
  toast('已退出登录')
  router.replace('/home')
}

onMounted(load)
watch(() => auth.isLoggedIn, (v) => v && load())
</script>

<template>
  <div class="page profile-page">
    <header class="profile-hero">
      <div class="hero-bar">
        <button class="hero-set" @click="showSettings = true">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.6 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.6h.09A1.65 1.65 0 0010 3.09V3a2 2 0 014 0v.09A1.65 1.65 0 0015 4.6a1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9v.09A1.65 1.65 0 0021 10h.09a2 2 0 010 4H21a1.65 1.65 0 00-1.6 1z" />
          </svg>
          <span>设置</span>
        </button>
      </div>

      <div class="hero-id">
        <AppAvatar :src="auth.userInfo?.avatar" :name="auth.userInfo?.nickname ?? 'U'" :size="72" />
        <div class="hero-info">
          <div class="hero-name">{{ auth.userInfo?.nickname }}</div>
          <div class="hero-account">
            <span>账号 {{ auth.userInfo?.phone }}</span>
            <span v-if="genderGlyph" class="hero-gender">{{ genderGlyph }}</span>
          </div>
        </div>
      </div>

      <div v-if="profile?.signature" class="hero-sign">{{ profile.signature }}</div>

      <div class="hero-stats">
        <div class="stat">
          <div class="stat-num">{{ formatCount(profile?.receivedLikeCount ?? 0) }}</div>
          <div class="stat-label">获赞</div>
        </div>
      </div>

      <div class="hero-cards">
        <button class="hero-card" @click="router.push('/me/wallet')">
          <span class="card-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="6" width="18" height="13" rx="2" />
              <path d="M3 10h18M16 15h2" />
            </svg>
          </span>
          <span class="card-text">
            <span class="card-title">钱包</span>
            <span class="card-sub">查看余额</span>
          </span>
        </button>
        <button class="hero-card" @click="router.push('/me/credit')">
          <span class="card-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="8" r="4" />
              <path d="M5 20c1.5-3 4-4.5 7-4.5s5.5 1.5 7 4.5" />
            </svg>
          </span>
          <span class="card-text">
            <span class="card-title">信用分</span>
            <span class="card-sub">{{ creditScore === null ? '查看详情' : `当前 ${creditScore} · ${creditLevel(creditScore).text}` }}</span>
          </span>
        </button>
        <button class="hero-card" @click="router.push('/me/history')">
          <span class="card-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7v5l3 3" />
            </svg>
          </span>
          <span class="card-text">
            <span class="card-title">浏览历史</span>
            <span class="card-sub">最近看过</span>
          </span>
        </button>
      </div>
    </header>

    <div class="content-panel">
      <div class="panel-tabs">
        <button
          v-for="t in MAIN_TABS"
          :key="t.key"
          class="panel-tab"
          :class="{ active: ui.profileTab === t.key }"
          @click="ui.setProfileTab(t.key)"
        >
          {{ t.label }}
        </button>
        <button class="panel-search" aria-label="搜索" @click="router.push(`/search?scope=${ui.profileTab}`)">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-4-4" />
          </svg>
        </button>
      </div>

      <template v-if="ui.profileTab === 'posts'">
        <!-- 草稿箱入口：我的-发帖左上角第一位（票 07） -->
        <button class="draft-entry" @click="router.push('/me/post-drafts')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 8v11a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5z" />
            <path d="M17 8V5l4 3h-4z" />
            <path d="M9 13h6" />
          </svg>
          <span>草稿箱</span>
          <span v-if="draftBoxCount" class="draft-count">{{ draftBoxCount }}</span>
          <span class="draft-arrow">›</span>
        </button>
        <div v-if="posts.length" class="post-rows">
          <div v-for="p in posts" :key="p.postId" class="post-row" @click="router.push(`/post/${p.postId}`)">
            <AppImage v-if="p.cover" :src="p.cover" :seed="p.postId" ratio="1:1" class="post-thumb" />
            <div v-else class="post-thumb post-thumb-text">文</div>
            <div class="post-info">
              <div class="post-t ellipsis-2">{{ p.title || '（无标题）' }}</div>
              <div class="post-m">
                <span class="status" :class="{ rejected: p.status === 'REJECTED' }">{{ STATUS_TEXT[p.status || ''] || '' }}</span>
                <span>❤ {{ formatCount(p.likeCount) }}</span>
                <span v-if="p.viewCount !== null">浏览 {{ p.viewCount }}</span>
                <span>{{ formatTime(p.createdAt) }}</span>
              </div>
            </div>
          </div>
        </div>
        <EmptyState
          v-else-if="!loading"
          text="还没有发布过内容"
          button-text="去发第一条"
          @click="router.push('/publish/post')"
        />
      </template>

      <!-- 赞过：点赞者视角 -->
      <div v-else-if="ui.profileTab === 'liked' && likedPosts.length" class="post-rows">
        <div v-for="p in likedPosts" :key="p.postId" class="post-row" @click="router.push(`/post/${p.postId}`)">
          <AppImage :src="p.cover" :seed="p.postId" ratio="1:1" class="post-thumb" />
          <div class="post-info">
            <div class="post-t ellipsis-2">{{ p.title || '（无标题）' }}</div>
            <div class="post-m">
              <span>❤ {{ formatCount(p.likeCount) }}</span>
              <span>{{ p.nickname }}</span>
              <span>{{ formatTime(p.createdAt) }}</span>
            </div>
          </div>
        </div>
      </div>
      <EmptyState
        v-else-if="ui.profileTab === 'liked' && !tabLoading"
        text="还没有点赞过笔记"
        button-text="去发现"
        @click="router.push('/home')"
      />

      <!-- 评论：评论列表（评论之间不画分割线；与作者主页同接口同形态） -->
      <div v-else-if="ui.profileTab === 'comments'" class="rows">
        <EmptyState
          v-if="!tabLoading && !myComments.length"
          text="还没有评论过笔记"
          button-text="去发现"
          @click="router.push('/home')"
        />
        <ProfileCommentRow
          v-for="item in myComments"
          :key="item.commentId"
          :comment="item"
          :nickname="auth.userInfo?.nickname ?? ''"
          :avatar="auth.userInfo?.avatar ?? null"
        />
      </div>

      <!-- 闲置：只留「我发布的」（我收藏的 / 我购买的收进聚合订单页与收藏页） -->
      <div v-else-if="ui.profileTab === 'products'">
        <div v-if="myProducts.length" class="errand-rows">
          <button
            v-for="p in myProducts"
            :key="p.productId"
            class="errand-row"
            @click="router.push(`/product/${p.productId}`)"
          >
            <AppImage :src="p.images[0]" :seed="p.productId" ratio="1:1" class="post-thumb" />
            <div class="errand-l">
              <div class="errand-desc ellipsis">{{ p.title }}</div>
              <div class="errand-loc ellipsis">¥{{ formatMoney(p.price) }} · {{ p.tradeLocation }}</div>
            </div>
            <div class="errand-r">
              <span class="errand-status">{{ PRODUCT_STATUS_TEXT[p.status] || p.status }}</span>
              <span class="errand-status">❤ {{ p.favoriteCount }} 人想要</span>
            </div>
          </button>
        </div>
        <EmptyState
          v-else-if="!tabLoading"
          text="还没有发布过闲置"
          button-text="去发布"
          @click="router.push('/publish/product')"
        />
      </div>

      <!-- 跑腿：只留「我发布的」（我接的收进聚合订单页） -->
      <template v-else>
        <div v-if="pubErrands.length" class="errand-rows">
          <button
            v-for="o in pubErrands"
            :key="o.orderId"
            class="errand-row"
            @click="router.push(`/errand/${o.orderId}`)"
          >
            <div class="errand-l">
              <div class="errand-desc ellipsis">{{ o.description }}</div>
              <div class="errand-loc ellipsis">{{ o.pickupLocation }} → {{ o.deliveryLocation }}</div>
            </div>
            <div class="errand-r">
              <span class="errand-reward">¥{{ formatMoney(o.reward) }}</span>
              <span class="errand-status">{{ ERRAND_STATUS_TEXT[o.status] || o.status }}</span>
            </div>
          </button>
        </div>
        <EmptyState
          v-else-if="!errandsLoading"
          text="还没有发布过跑腿单"
          button-text="去发一单"
          @click="router.push('/publish/errand')"
        />
      </template>
    </div>

    <button class="logout-btn" @click="onLogout">退出登录</button>
    <div class="bottom-space" />
    <SettingsSheet v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped lang="scss">
.profile-page {
  padding-bottom: calc(#{$tabbar-h} + env(safe-area-inset-bottom) + 20px);
  background: $bg-page;
}

.profile-hero {
  padding: 10px 20px 26px;
  background: linear-gradient(168deg, #5b6862 0%, #3a423d 55%, #2b302c 100%);
}

.hero-bar {
  display: flex;
  justify-content: flex-end;
  height: 36px;
}

.hero-set {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 13px;
  border-radius: $radius-full;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-size: 13px;
  font-weight: 500;

  &:active {
    background: rgba(255, 255, 255, 0.26);
  }
}

.hero-id {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-info {
  flex: 1;
  min-width: 0;
}

.hero-name {
  font-size: 20px;
  font-weight: 600;
  color: #fff;
}

.hero-account {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 5px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
}

.hero-gender {
  padding: 1px 7px;
  border-radius: $radius-full;
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  font-size: 11px;
}

.hero-sign {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.86);
}

.hero-stats {
  display: flex;
  gap: 24px;
  margin-top: 18px;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.stat-num {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}

.stat-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

.hero-cards {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.hero-card {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.16);

  &:active {
    background: rgba(255, 255, 255, 0.26);
  }
}

.card-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  flex-shrink: 0;
}

.card-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  min-width: 0;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #fff;
}

.card-sub {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.65);
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.content-panel {
  border-radius: 14px;
  background: #fff;
  margin-top: -14px;
  padding-top: 4px;
  overflow: hidden;
}

.panel-tabs {
  display: flex;
  align-items: center;
  padding: 0 12px;
}

.panel-tab {
  position: relative;
  height: 44px;
  font-size: 16px;
  color: $text-1;
  margin-right: 26px;

  &.active {
    font-weight: 550;

    &::after {
      content: '';
      position: absolute;
      left: 50%;
      transform: translateX(-50%);
      bottom: 2px;
      width: 22px;
      height: 3px;
      border-radius: 2px;
      background: $primary;
    }
  }
}

.panel-search {
  margin-left: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  color: $text-1;

  &:active {
    background: $bg-secondary;
  }
}

.draft-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 12px 0;
  padding: 8px 12px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-1;
  font-size: 13px;
  font-weight: 500;
  text-align: left;

  &:active {
    background: $primary-light;
  }
}

.draft-count {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.draft-arrow {
  margin-left: auto;
  color: $text-4;
  font-size: 15px;
}

/* 评论：不画分割线，靠 14px 行距自然分隔（与作者主页同形态） */
.rows {
  padding-bottom: 8px;
}

.post-rows {
  display: flex;
  flex-direction: column;
  border-top: 0.5px solid $line;
}

.post-row {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 0.5px solid $line;

  &:active {
    background: $bg-secondary;
  }
}

.post-thumb {
  width: 56px;
  height: 56px;
  border-radius: $radius-sm;
  flex-shrink: 0;
}

/* 无封面帖的形态标识，替代原渐变占位图 */
.post-thumb-text {
  display: flex;
  align-items: center;
  justify-content: center;
  background: $bg-secondary;
  color: $text-3;
  font-size: 18px;
  font-weight: 550;
}

.post-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.post-t {
  font-size: 15px;
  font-weight: 500;
}

.post-m {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: $text-3;
}

.status {
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: #e8f6e9;
  color: #2e9e44;

  &.rejected {
    background: #ffecec;
    color: #c0392b;
  }
}

.errand-rows {
  border-top: 0.5px solid $line;
}

.errand-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 16px;
  border-bottom: 0.5px solid $line;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.errand-l {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.errand-desc {
  font-size: 15px;
  font-weight: 500;
}

.errand-loc {
  font-size: 11px;
  color: $text-3;
}

.errand-r {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}

.errand-reward {
  color: $primary;
  font-size: 14px;
  font-weight: 600;
}

.errand-status {
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 10px;

  &.rejected {
    background: #ffecec;
    color: #c0392b;
  }
}

.logout-btn {
  display: block;
  width: calc(100% - 24px);
  margin: 20px 12px 0;
  height: 44px;
  border-radius: $radius-full;
  background: #fff;
  color: $text-2;
  font-size: 14px;

  &:active {
    background: $bg-secondary;
  }
}

.bottom-space {
  height: 20px;
}
</style>
