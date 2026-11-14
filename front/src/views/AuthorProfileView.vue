<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { createDirectRoom } from '@/api/chat'
import { getPublicProfile } from '@/api/user'
import { listUserComments, listUserLikedPosts, listUserPosts } from '@/api/community'
import { listUserProducts } from '@/api/trade'
import { listUserErrands } from '@/api/errand'
import type {
  AuthorProfileComment,
  ErrandOrder,
  PostSummary,
  Product,
  PublicUserProfile
} from '@/types/api'
import AppAvatar from '@/components/AppAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'
import PostCard from '@/components/PostCard.vue'
import ProductCard from '@/components/ProductCard.vue'
import ProfileCommentRow from '@/components/ProfileCommentRow.vue'
import SettingsSheet from '@/components/SettingsSheet.vue'
import { formatCount, formatMoney, formatTime } from '@/utils/format'
import { errandTypeText } from '@/utils/errand'

type Tab = 'posts' | 'liked' | 'comments' | 'products' | 'errands'

const TABS: { key: Tab; label: string }[] = [
  { key: 'posts', label: '笔记' },
  { key: 'liked', label: '赞过' },
  { key: 'comments', label: '评论' },
  { key: 'products', label: '闲置' },
  { key: 'errands', label: '跑腿' }
]

const EMPTY_TEXT: Record<Tab, string> = {
  posts: '还没有笔记',
  liked: '还没有赞过的内容',
  comments: '还没有评论',
  products: '还没有闲置',
  errands: '还没有跑腿'
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const userId = computed(() => Number(route.params.userId))
const isSelf = computed(() => auth.userId === userId.value)

const profile = ref<PublicUserProfile | null>(null)
const notFound = ref(false)
const tab = ref<Tab>('posts')
const loading = ref(false)
const showSettings = ref(false)

const posts = ref<PostSummary[]>([])
const likedPosts = ref<PostSummary[]>([])
const comments = ref<AuthorProfileComment[]>([])
const products = ref<Product[]>([])
const errands = ref<ErrandOrder[]>([])
const loaded = ref<Record<Tab, boolean>>({
  posts: false,
  liked: false,
  comments: false,
  products: false,
  errands: false
})
const inflight = new Set<Tab>()

/** 隐私开关仅他视生效：本人看自己不受影响。开关只作用于「赞过」「评论」两个 tab，「笔记」不受影响 */
const likedHidden = computed(() => !isSelf.value && profile.value?.showLiked === false)
const commentsHidden = computed(() => !isSelf.value && profile.value?.showComments === false)
const likedList = computed(() => (tab.value === 'posts' ? posts.value : likedPosts.value))
/** 当前 tab 的内容是否因隐私开关被隐藏（笔记 tab 恒不隐藏） */
const currentTabHidden = computed(() => tab.value === 'liked' && likedHidden.value)

async function loadProfile() {
  notFound.value = false
  profile.value = null
  try {
    profile.value = await getPublicProfile(userId.value)
  } catch {
    notFound.value = true
  }
}

async function loadTab(target: Tab) {
  if (loaded.value[target] || inflight.has(target)) return
  inflight.add(target)
  loading.value = true
  try {
    if (target === 'posts') {
      posts.value = (await listUserPosts(userId.value)).list
    } else if (target === 'liked') {
      likedPosts.value = (await listUserLikedPosts(userId.value)).list
    } else if (target === 'comments') {
      comments.value = (await listUserComments(userId.value)).list
    } else if (target === 'products') {
      products.value = (await listUserProducts(userId.value)).list
    } else {
      errands.value = (await listUserErrands(userId.value)).list
    }
    loaded.value[target] = true
  } finally {
    inflight.delete(target)
    loading.value = false
  }
}

function resetContent() {
  posts.value = []
  likedPosts.value = []
  comments.value = []
  products.value = []
  errands.value = []
  loaded.value = { posts: false, liked: false, comments: false, products: false, errands: false }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/home')
}

function requireLogin(): boolean {
  if (auth.isLoggedIn) return true
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function onContact() {
  if (!requireLogin()) return
  const room = await createDirectRoom(userId.value)
  chatRooms.scheduleRefresh()
  router.push(`/chat/${room.roomId}`)
}

watch(tab, (target) => loadTab(target))

// 同页换作者（详情页头像/昵称互跳）时重置全部内容与 tab
watch(userId, () => {
  resetContent()
  tab.value = 'posts'
  loadProfile()
  loadTab('posts')
})

onMounted(() => {
  loadProfile()
  loadTab(tab.value)
})
</script>

<template>
  <div class="author-page">
    <header class="hero">
      <div class="nav">
        <button class="nav-btn" aria-label="返回" @click="goBack">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 5l-7 7 7 7" />
          </svg>
        </button>
        <button v-if="isSelf && !notFound" class="set-btn" @click="showSettings = true">
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.6 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.6h.09A1.65 1.65 0 0010 3.09V3a2 2 0 014 0v.09A1.65 1.65 0 0015 4.6a1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9v.09A1.65 1.65 0 0021 10h.09a2 2 0 010 4H21a1.65 1.65 0 00-1.6 1z" />
          </svg>
          设置
        </button>
      </div>

      <div v-if="!notFound" class="ident">
        <AppAvatar :src="profile?.avatar" :name="profile?.nickname" :size="72" />
        <div class="ident-main">
          <div class="name">{{ profile?.nickname }}</div>
          <span v-if="profile?.gender" class="gender">{{ profile.gender === 'FEMALE' ? '♀' : '♂' }}</span>
        </div>
        <button v-if="!isSelf" class="dm-chip" @click="onContact">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a8 8 0 01-11.6 7.1L4 21l1.9-5.4A8 8 0 1121 12z" />
          </svg>
          私信
        </button>
      </div>

      <div v-if="!notFound" class="stats">
        <span class="stat-num">{{ formatCount(profile?.receivedLikeCount ?? 0) }}</span>
        <span class="stat-label">获赞</span>
      </div>

      <div v-if="!notFound && profile?.signature" class="sig">「{{ profile.signature }}」</div>
    </header>

    <div class="panel">
      <EmptyState v-if="notFound" text="用户不存在" />

      <template v-else>
        <div class="tabs">
          <button
            v-for="t in TABS"
            :key="t.key"
            class="tab"
            :class="{ active: tab === t.key }"
            @click="tab = t.key"
          >
            {{ t.label }}
          </button>
        </div>

        <!-- 笔记 / 赞过：双列瀑布流（复用票 01 卡面规范） -->
        <template v-if="tab === 'posts' || tab === 'liked'">
          <EmptyState
            v-if="!loading && (currentTabHidden || !likedList.length)"
            :text="currentTabHidden ? '该用户已隐藏此内容' : EMPTY_TEXT[tab]"
          />
          <div v-else-if="likedList.length" class="grid-wrap">
            <div class="grid">
              <PostCard v-for="post in likedList" :key="post.postId" :post="post" />
            </div>
          </div>
        </template>

        <!-- 评论：评论列表（评论之间不画分割线） -->
        <div v-else-if="tab === 'comments'" class="rows">
          <EmptyState v-if="!loading && commentsHidden" text="该用户已隐藏此内容" />
          <EmptyState v-else-if="!loading && !comments.length" :text="EMPTY_TEXT.comments" />
          <ProfileCommentRow
            v-for="item in comments"
            :key="item.commentId"
            :comment="item"
            :nickname="profile?.nickname ?? ''"
            :avatar="profile?.avatar ?? null"
          />
        </div>

        <!-- 闲置：双列网格（复用票 01 商品卡） -->
        <template v-else-if="tab === 'products'">
          <EmptyState v-if="!loading && !products.length" :text="EMPTY_TEXT.products" />
          <div v-else-if="products.length" class="grid-wrap">
            <div class="grid">
              <ProductCard v-for="product in products" :key="product.productId" :product="product" />
            </div>
          </div>
        </template>

        <!-- 跑腿：列表（与评论同口径，不画分割线） -->
        <div v-else class="rows">
          <EmptyState v-if="!loading && !errands.length" :text="EMPTY_TEXT.errands" />
          <button v-for="order in errands" :key="order.orderId" class="errand" @click="router.push(`/errand/${order.orderId}`)">
            <div class="row-between">
              <span class="badge">{{ errandTypeText(order.type) }}</span>
              <span class="reward">
                ¥{{ formatMoney(order.reward) }}
              </span>
            </div>
            <div class="edesc ellipsis-3">{{ order.title ?? order.description }}</div>
            <div class="route">
              <span class="ellipsis">{{ order.pickupLocation }}</span>
              <span class="arrow">→</span>
              <span class="ellipsis">{{ order.deliveryLocation }}</span>
            </div>
            <div class="emeta">
              <AppAvatar :src="order.publisherAvatar" :name="order.publisherNickname" :size="16" />
              <span class="ellipsis pname">{{ order.publisherNickname }}</span>
              <span class="pfav">{{ formatTime(order.createdAt) }}</span>
            </div>
          </button>
        </div>
      </template>
    </div>

    <!-- 设置入口（仅本人视角）：编辑资料 / 隐私设置 -->
    <SettingsSheet v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped lang="scss">
.author-page {
  min-height: 100vh;
  background: $bg-page;
  padding-bottom: calc(#{$tabbar-h} + env(safe-area-inset-bottom) + 12px);
}

.hero {
  padding: 6px 20px 26px;
  background: linear-gradient(168deg, #5b6862 0%, #3a423d 55%, #2b302c 100%);
}

.nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 44px;
  margin: 0 -8px;
}

.nav-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 40px;
  border-radius: 50%;
  color: #fff;

  &:active {
    background: rgba(255, 255, 255, 0.16);
  }
}

.set-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 40px;
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

.ident {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
}

.ident-main {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 21px;
  font-weight: 600;
  color: #fff;
}

.gender {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 7px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  font-size: 11px;
}

.dm-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  height: 32px;
  padding: 0 15px;
  border-radius: $radius-full;
  background: $primary;
  color: #fff;
  font-size: 13px;
  font-weight: 600;

  &:active {
    opacity: 0.85;
  }
}

.stats {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: 20px;
}

.stat-num {
  font-size: 17px;
  font-weight: 600;
  color: #fff;
}

.stat-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

.sig {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.86);
}

.panel {
  margin-top: -14px;
  border-radius: 14px 14px 0 0;
  background: #fff;
}

/* 圆角挂在 tab 栏自身：面板若用 overflow:hidden 裁圆角，会成为滚动容器，
   子元素 position: sticky 失效（吸顶 tab 就不会吸顶） */
.tabs {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  padding: 0 12px;
  border-radius: 14px 14px 0 0;
  background: #fff;
}

.tab {
  position: relative;
  height: 46px;
  margin-right: 26px;
  font-size: 16px;
  color: $text-1;

  &.active {
    font-weight: 550;

    &::after {
      content: '';
      position: absolute;
      left: 50%;
      transform: translateX(-50%);
      bottom: 1px;
      width: 22px;
      height: 3px;
      border-radius: 2px;
      background: $primary;
    }
  }
}

/* 双列瀑布流/网格：沿用票 01 定稿的灰底白卡与间距 */
.grid-wrap {
  padding: 8px;
  background: $bg-page-alt;
}

.grid {
  columns: 2;
  column-gap: 8px;

  > * {
    break-inside: avoid;
    margin-bottom: 8px;
  }
}

/* 评论与跑腿：不画分割线，靠 14px 行距自然分隔 */
.rows {
  padding-bottom: 8px;
}

.errand {
  display: block;
  width: 100%;
  padding: 14px 16px;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.badge {
  padding: 2px 7px;
  border-radius: 5px;
  background: rgba(255, 36, 66, 0.1);
  color: $primary;
  font-size: 10px;
  font-weight: 600;
}

.reward {
  color: $primary;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.edesc {
  margin-top: 8px;
  font-size: 14px;
  line-height: 1.5;
  color: $text-1;
}

.route {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 11px;

  .arrow {
    flex-shrink: 0;
    color: $text-4;
  }
}

.emeta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  color: $text-3;
  font-size: 10px;
}

.pname {
  max-width: 55%;
}

.pfav {
  margin-left: auto;
}
</style>
