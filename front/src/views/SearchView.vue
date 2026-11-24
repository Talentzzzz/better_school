<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listLikedPosts, listMyPosts, listCommentedPosts } from '@/api/community'
import { listMyProducts } from '@/api/idle'
import { searchPosts, searchProducts, searchUsers } from '@/api/search'
import type { PostSummary, Product, UserSummary } from '@/types/api'
import PostCard from '@/components/PostCard.vue'
import ProductCard from '@/components/ProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import { toast } from '@/components/Toast'

const route = useRoute()
const router = useRouter()

type Scope = 'post' | 'market' | 'posts' | 'liked' | 'comments' | 'products' | 'users'

const SCOPES: Scope[] = ['post', 'market', 'posts', 'liked', 'comments', 'products', 'users']

const scope = computed<Scope>(() => {
  const s = String(route.query.scope || '')
  return SCOPES.includes(s as Scope) ? (s as Scope) : 'post'
})

const PLACEHOLDER: Record<Scope, string> = {
  post: '搜索帖子',
  market: '搜索闲置',
  posts: '搜索我发布的笔记',
  liked: '搜索我赞过的笔记',
  comments: '搜索我评论的笔记',
  products: '搜索我发布的闲置',
  users: '搜索用户'
}

const kw = ref('')
const searching = ref(false)
const searched = ref(false)

const posts = ref<PostSummary[]>([])
const products = ref<Product[]>([])
const users = ref<UserSummary[]>([])

const emptyText = computed(() => {
  const s = scope.value
  if (s === 'post') return '没有匹配的帖子'
  if (s === 'posts') return '没有找到相关的笔记'
  if (s === 'liked') return '暂无赞过的笔记'
  if (s === 'comments') return '暂无评论过的笔记'
  if (s === 'products') return '暂无我发布的闲置'
  if (s === 'users') return '没有找到相关用户'
  return '没有找到相关内容，换个关键词试试'
})

const hotWords = [
  '失物招领',
  '考研资料',
  '校园卡',
  '二手教材',
  '自行车',
  '演唱会门票',
  '闲置出手'
]

/** 客户端匹配（"我的"范围的 50 条内筛选，服务端搜索不用） */
function match(a: string, b: string): boolean {
  const k = kw.value.trim().toLowerCase()
  if (!k) return false
  return (a || '').toLowerCase().includes(k) || (b || '').toLowerCase().includes(k)
}

async function doSearch(word?: string) {
  if (word !== undefined) kw.value = word
  const k = kw.value.trim()
  if (!k) {
    toast('请输入搜索内容')
    return
  }
  searching.value = true
  searched.value = true
  const s = scope.value
  try {
    if (s === 'post') {
      const res = await searchPosts(k, 1, 20)
      posts.value = res.list
    } else if (s === 'market') {
      const res = await searchProducts(k, 1, 20)
      products.value = res.list
    } else if (s === 'users') {
      const res = await searchUsers(k, 1, 20)
      users.value = res.list
    } else if (s === 'posts') {
      const res = await listMyPosts(1, 50)
      posts.value = res.list.filter((x) => match(x.title, x.content || ''))
    } else if (s === 'liked') {
      const res = await listLikedPosts(1, 50)
      posts.value = res.list.filter((x) => match(x.title, x.content || ''))
    } else if (s === 'comments') {
      const res = await listCommentedPosts(1, 50)
      posts.value = res.list.filter((x) => match(x.title, x.content || ''))
    } else if (s === 'products') {
      const res = await listMyProducts(1, 50)
      products.value = res.list.filter((x) => match(x.title, x.description))
    }
  } finally {
    searching.value = false
  }
}
</script>

<template>
  <div class="page--plain search-page">
    <header class="search-nav">
      <button class="back-btn" aria-label="返回" @click="router.back()">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M15 5l-7 7 7 7" />
        </svg>
      </button>
      <div class="search-box">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" class="search-ico">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-4-4" />
        </svg>
        <input
          v-model="kw"
          class="search-input"
          :placeholder="PLACEHOLDER[scope]"
          @keyup.enter="doSearch()"
        />
        <button v-if="kw" class="clear-btn" @click="kw = ''">×</button>
      </div>
      <button class="search-submit" @click="doSearch()">搜索</button>
    </header>

    <template v-if="!searched">
      <div class="guess-section">
        <div class="guess-title">猜你想搜</div>
        <div class="guess-grid">
          <button
            v-for="w in hotWords"
            :key="w"
            class="guess-word"
            @click="doSearch(w)"
          >
            {{ w }}
          </button>
        </div>
      </div>
    </template>

    <template v-else>
      <div v-if="searching" class="searching">搜索中…</div>

      <!-- 市集入口：闲置服务端结果 -->
      <template v-else-if="scope === 'market'">
        <div v-if="products.length" class="worm">
          <div class="waterfall">
            <ProductCard v-for="p in products" :key="p.productId" :product="p" />
          </div>
          <button class="btn-text more" @click="router.push('/market')">查看更多闲置 ›</button>
        </div>
        <EmptyState v-else text="没有匹配的闲置" />
      </template>

      <!-- 首页-帖子：服务端搜索结果 -->
      <template v-else-if="scope === 'post'">
        <div v-if="posts.length" class="worm">
          <div class="waterfall">
            <PostCard v-for="p in posts" :key="p.postId" :post="p" />
          </div>
        </div>
        <EmptyState v-else :text="emptyText" />
      </template>

      <!-- 我的-笔记（我发布的/赞过/评论过）：客户端在"我的"列表前 50 条中筛选 -->
      <template v-else-if="scope === 'posts' || scope === 'liked' || scope === 'comments'">
        <div v-if="posts.length">
          <div class="search-hint">已在前 50 条中匹配到结果，更多内容请点击「查看更多」</div>
          <div class="worm">
            <div class="waterfall">
              <PostCard v-for="p in posts" :key="p.postId" :post="p" />
            </div>
            <button class="btn-text more" @click="router.push('/profile')">查看更多笔记 ›</button>
          </div>
        </div>
        <EmptyState v-else :text="emptyText" />
      </template>

      <!-- 我的-闲置：客户端在"我发布的"前 50 条中筛选 -->
      <template v-else-if="scope === 'products'">
        <div v-if="products.length">
          <div class="search-hint">已在前 50 条中匹配到结果，更多内容请点击「查看更多」</div>
          <div class="worm">
            <div class="waterfall">
              <ProductCard v-for="p in products" :key="p.productId" :product="p" />
            </div>
            <button class="btn-text more" @click="router.push('/profile')">查看更多闲置 ›</button>
          </div>
        </div>
        <EmptyState v-else :text="emptyText" />
      </template>

      <!-- 用户：服务端搜索结果 -->
      <template v-else-if="scope === 'users'">
        <div v-if="users.length" class="user-list">
          <div v-for="u in users" :key="u.userId" class="user-card">
            <AppAvatar :src="u.avatar" :name="u.nickname" :size="44" />
            <div class="user-meta">
              <div class="user-name">{{ u.nickname }}</div>
              <div class="user-sub">在校同学</div>
            </div>
          </div>
        </div>
        <EmptyState v-else :text="emptyText" />
      </template>
    </template>
  </div>
</template>

<style scoped lang="scss">
.search-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 52px;
  padding: 0 12px;
  background: #fff;
}

.back-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  flex-shrink: 0;

  &:active {
    background: $bg-secondary;
  }
}

.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 12px;
  border-radius: $radius-full;
  background: $bg-search;

  .search-ico {
    color: $text-3;
    flex-shrink: 0;
  }
}

.search-input {
  flex: 1;
  min-width: 0;
  font-size: 14px;
}

.clear-btn {
  color: $text-4;
  font-size: 16px;
  padding: 0 2px;
}

.search-submit {
  flex-shrink: 0;
  color: $text-1;
  font-size: 15px;
  font-weight: 600;
}

.guess-section {
  padding: 24px 16px;
}

.guess-title {
  font-size: 16px;
  font-weight: 500;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;

  &::after {
    content: '···';
    color: $text-3;
    font-weight: 400;
    letter-spacing: 2px;
  }
}

.guess-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px 16px;
}

.guess-word {
  text-align: left;
  font-size: 16px;
  color: $text-1;

  &:active {
    color: $primary;
  }
}

.searching {
  padding: 48px 0;
  text-align: center;
  color: $text-3;
}

.result-tabs {
  display: flex;
  gap: 18px;
  padding: 12px 16px 4px;
  font-size: 14px;
}

.result-tab {
  color: $text-2;

  &.active {
    color: $primary;
    font-weight: 600;
  }
}

.search-hint {
  padding: 2px 16px 8px;
  font-size: 11px;
  color: $text-4;
}

.worm {
  padding: 4px 0 20px;
}

.waterfall {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  padding: 8px;
}

.more {
  display: block;
  margin: 8px auto 0;
}

.user-list {
  padding: 4px 16px 20px;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid $line;
}

.user-meta {
  min-width: 0;
}

.user-name {
  font-size: 15px;
  font-weight: 500;
  color: $text-1;
}

.user-sub {
  margin-top: 2px;
  font-size: 12px;
  color: $text-3;
}
</style>
