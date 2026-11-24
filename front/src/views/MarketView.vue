<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { useNoticesStore } from '@/stores/notices'
import { useWaterfall } from '@/composables/useWaterfall'
import { listProducts } from '@/api/idle'
import type { IdleCategory } from '@/types/api'
import { IDLE_CATEGORIES, type SortOrder } from '@/utils/idle'
import ProductCard from '@/components/ProductCard.vue'
import MarketEntryRow, { type EntryKey } from '@/components/MarketEntryRow.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

/**
 * 两个 tab 共用的一份五档排序 chips（market-feed-uv 票 05 定稿文案）：两域文案一字不差，
 * 才不会在切 tab 时看到「价格↓」变成「悬赏↓」。图标不用 SVG——价格方向用 ↑ / ↓ 字形承载，
 * 一行里五个按钮都放得下，也不跟别的档位抢视觉
 */
const MARKET_SORTS: { value: SortOrder; label: string }[] = [
  { value: 'hot', label: '热度' },
  { value: 'time', label: '最新' },
  { value: 'time_asc', label: '最早' },
  { value: 'price_asc', label: '价格↑' },
  { value: 'price_desc', label: '价格↓' }
]

// 二手
const productCategory = ref<'' | IdleCategory>('')
const productSort = ref<SortOrder>('time')

/** 分类 chips（六类 + 全部） */
const categoryChips: { value: '' | IdleCategory; label: string }[] = [
  { value: '', label: '全部' },
  ...IDLE_CATEGORIES
]

const {
  list: productList,
  loading: productLoading,
  finished: productFinished,
  sentinel: productSentinel,
  load: loadProduct,
  reset: resetProduct
} = useWaterfall((page, cursor) =>
  listProducts({
    category: productCategory.value || undefined,
    sort: productSort.value,
    page,
    cursor,
    pageSize: 10
  })
)

watch([productCategory, productSort], () => {
  resetProduct()
  loadProduct()
})

// 入口行第二层吸顶：filter-bar 的 top 由入口行**实测高度**导出，两处不各写魔数
// （入口行整行受全局 body line-height 影响，12px 标签实测行盒 20.4px）
const entryRef = ref<ComponentPublicInstance | null>(null)
const filterTop = ref('48px')
let entryResizeObserver: ResizeObserver | null = null

function syncFilterTop() {
  const el = entryRef.value?.$el as HTMLElement | undefined
  filterTop.value = `${48 + (el?.offsetHeight ?? 0)}px`
}

/**
 * 消息格角标＝赞/评横幅通知未读（notices store，NOTIF 帧实时 +1）+ 市集对家会话未读（会话来源列非空者）。
 */
const notices = useNoticesStore()
const roomUnread = computed(() =>
  chatRooms.rooms.reduce((sum, r) => sum + (r.source ? r.unread : 0), 0)
)
const entryUnread = computed(() => notices.unread.INTERACTION_LIKE + notices.unread.INTERACTION_COMMENT + roomUnread.value)

function loadEntryUnread() {
  if (!auth.isLoggedIn) return
  chatRooms.loadRooms()
}

function onEntryGo(key: EntryKey) {
  const route =
    key === 'orders'
      ? '/me/orders'
      : key === 'favorites'
        ? '/me/favorites'
        : key === 'messages'
          ? '/market/messages'
          : '/marketing'
  router.push(route)
}

onMounted(() => {
  loadProduct()
  syncFilterTop()
  entryResizeObserver = new ResizeObserver(syncFilterTop)
  const el = entryRef.value?.$el as HTMLElement | undefined
  if (el) entryResizeObserver.observe(el)
  loadEntryUnread()
})

onBeforeUnmount(() => entryResizeObserver?.disconnect())
</script>

<template>
  <div class="page market-page">
    <header class="market-nav">
      <div class="nav-left" />
      <div class="nav-tabs">
        <button class="nav-tab active">闲置</button>
      </div>
      <div class="nav-right">
        <button class="nav-search" aria-label="搜索" @click="router.push('/search?scope=market')">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-4-4" />
          </svg>
        </button>
      </div>
    </header>

    <MarketEntryRow ref="entryRef" :unread="entryUnread" @go="onEntryGo" />

    <div class="filter-bar" :style="{ top: filterTop }">
      <div class="chips">
        <button
          v-for="chip in categoryChips"
          :key="chip.value"
          class="chip"
          :class="{ active: productCategory === chip.value }"
          @click="productCategory = chip.value"
        >
          {{ chip.label }}
        </button>
      </div>
      <div class="sorts">
        <button
          v-for="item in MARKET_SORTS"
          :key="item.value"
          class="sort-chip"
          :class="{ active: productSort === item.value }"
          @click="productSort = item.value"
        >
          {{ item.label }}
        </button>
      </div>
    </div>
    <div v-if="productList.length" class="waterfall">
      <ProductCard v-for="p in productList" :key="p.productId" :product="p" />
    </div>
    <EmptyState
      v-else-if="!productLoading"
      text="还没有闲置出售，发布第一件宝贝吧"
      button-text="去发布"
      @click="router.push('/publish/product')"
    />
    <div ref="productSentinel" class="sentinel">{{ productLoading ? '加载中…' : productFinished ? '已经到底啦' : '' }}</div>
  </div>
</template>

<style scoped lang="scss">
.market-page {
  padding-bottom: calc(#{$tabbar-h} + env(safe-area-inset-bottom));
  background: $bg-page-alt;
}

.market-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  display: grid;
  grid-template-columns: 64px 1fr 64px;
  align-items: center;
  height: 48px;
  padding: 0 12px 0 0;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
}

.nav-tabs {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 22px;
}

.nav-tab {
  position: relative;
  height: 48px;
  font-size: 17px;
  color: $text-1;

  &.active {
    color: $primary;
    font-weight: 550;

    &::after {
      content: '';
      position: absolute;
      left: 50%;
      transform: translateX(-50%);
      bottom: 6px;
      width: 22px;
      height: 3px;
      border-radius: 2px;
      background: $primary;
    }
  }
}

.nav-right {
  display: flex;
  justify-content: flex-end;
}

.nav-search {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  color: $text-1;

  &:active {
    background: $bg-secondary;
  }
}

/* top 由入口行实测高度下发（见 syncFilterTop）；z-index 低于入口行，防两层叠压时抢不到上层。
   两行结构：分类 / 类型 chips 一行，五档排序 chips 一行（两 tab 同一套排序口径，market-feed-uv 票 05） */
.filter-bar {
  position: sticky;
  z-index: 97;
  display: flex;
  flex-direction: column;
  padding: 2px 12px 4px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
}

.chips {
  display: flex;
  align-items: center;
  height: 34px;
  min-width: 0;
}

.chip {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: $text-2;
  text-align: center;

  &.active {
    color: $primary;
    font-weight: 600;
  }
}

.sorts {
  display: flex;
  align-items: center;
  height: 30px;
  border-top: 0.5px solid $line;
}

.sort-chip {
  flex: 1;
  min-width: 0;
  height: 24px;
  border-radius: 12px;
  font-size: 12px;
  color: $text-3;

  &.active {
    color: $primary;
    font-weight: 600;
    background: rgba(255, 36, 66, 0.08);
  }
}

.waterfall {
  columns: 2;
  column-gap: 8px;
  padding: 0 8px;

  > * {
    break-inside: avoid;
    margin-bottom: 8px;
  }
}

.sentinel {
  padding: 18px 0;
  text-align: center;
  color: $text-4;
  font-size: 12px;
}
</style>
