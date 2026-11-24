<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createProductOrder,
  deleteProduct,
  favoriteProduct,
  getProduct,
  relistProduct,
  unfavoriteProduct
} from '@/api/idle'
import { createDirectRoom } from '@/api/chat'
import type { Product } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatCount, formatMoney, formatTime } from '@/utils/format'
import { idleCategoryText } from '@/utils/idle'
import NavBar from '@/components/NavBar.vue'
import AppAvatar from '@/components/AppAvatar.vue'
import AppImage from '@/components/AppImage.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const productId = Number(route.params.id)
const product = ref<Product | null>(null)
const favBusy = ref(false)

const isSeller = computed(() => !!auth.userId && product.value?.userId === auth.userId)

async function load() {
  product.value = await getProduct(productId)
}

function requireLogin(): boolean {
  if (auth.isLoggedIn) return true
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function toggleFav() {
  if (!product.value) return
  if (!requireLogin()) return
  if (favBusy.value) return
  favBusy.value = true
  const faved = product.value.favorited
  product.value.favorited = !faved
  product.value.favoriteCount += faved ? -1 : 1
  try {
    if (faved) await unfavoriteProduct(productId)
    else await favoriteProduct(productId)
    toast(faved ? '已取消收藏' : '已收藏')
  } catch {
    product.value.favorited = faved
    product.value.favoriteCount += faved ? 1 : -1
  } finally {
    favBusy.value = false
  }
}

async function onContact() {
  if (!product.value) return
  if (!requireLogin()) return
  const room = await createDirectRoom(product.value.userId, 'PRODUCT')
  chatRooms.scheduleRefresh()
  router.push(`/chat/${room.roomId}?card=PRODUCT:${productId}`)
}

async function onBuy() {
  if (!requireLogin()) return
  const ok = await confirmDialog('确认下单？下单后请在 15 分钟内支付，资金将托管冻结')
  if (!ok) return
  const orderId = await createProductOrder(productId)
  toast('下单成功')
  router.push(`/idle/order/${orderId}`)
}

async function onRelist() {
  await relistProduct(productId)
  toast('商品已重新上架')
  await load()
}

async function onDelete() {
  const ok = await confirmDialog('删除该商品？删除后不可恢复')
  if (!ok) return
  await deleteProduct(productId)
  toast('已删除')
  router.replace('/market')
}

onMounted(load)
</script>

<template>
  <div class="page--plain product-detail">
    <NavBar title="商品详情" />

    <template v-if="product">
      <div class="gallery">
        <AppImage :src="product.images[0]" :seed="product.productId" :text="idleCategoryText(product.category)" ratio="1:1" dynamic />
      </div>

      <div class="price-banner">
        <div class="price-big">
          <span class="price-symbol">¥</span>{{ formatMoney(product.price) }}
          <span class="price-favs">{{ formatCount(product.favoriteCount) }}人想要</span>
        </div>
        <div class="title-big">{{ product.title }}</div>
        <div class="tag-line">
          <span class="meta-tag">{{ idleCategoryText(product.category) }}</span>
          <span class="view-count">浏览 {{ product.viewCount }}</span>
        </div>
      </div>

      <div class="detail-card">
        <div class="desc-title">宝贝描述</div>
        <div class="desc-text">{{ product.description }}</div>
        <div class="loc-line">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 21s-7-5.2-7-11a7 7 0 0 1 14 0c0 5.8-7 11-7 11z" />
            <circle cx="12" cy="10" r="2.5" />
          </svg>
          面交地点：{{ product.tradeLocation }}
        </div>
      </div>

      <div class="detail-card">
        <div class="seller-row">
          <button class="seller-main" @click="router.push(`/user/${product.userId}`)">
            <AppAvatar :src="product.sellerAvatar" :name="product.sellerNickname" :size="40" />
            <div class="seller-info">
              <div class="seller-name">{{ product.sellerNickname }}</div>
              <div class="seller-time">发布于 {{ formatTime(product.createdAt) }}</div>
            </div>
          </button>
          <span class="status-tag" :class="product.status === 'ON_SALE' ? '' : 'off'">
            {{ product.status === 'ON_SALE' ? '在售' : product.status === 'LOCKED' ? '交易中' : product.status === 'SOLD' ? '已售出' : product.status === 'DRAFT' ? '草稿' : '' }}
          </span>
        </div>
      </div>
    </template>

    <div v-else class="loading">加载中…</div>

    <footer v-if="product" class="action-bar safe-bottom">
      <div v-if="isSeller" class="action-group">
        <button v-if="product.status === 'SOLD'" class="btn-outline" @click="onRelist">重新上架</button>
        <button class="btn-outline danger" @click="onDelete">删除</button>
      </div>
      <div v-else class="action-group">
        <button class="btn-outline large" @click="toggleFav">
          {{ product.favorited ? '已收藏 ♥' : '收藏 ♡' }}
        </button>
        <button v-if="product.status === 'ON_SALE'" class="btn-primary" @click="onBuy">立即购买</button>
        <button v-else class="btn-primary" disabled>交易中</button>
        <button class="btn-outline" @click="onContact">联系卖家</button>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.gallery {
  background: #fff;
}

.price-banner {
  padding: 14px 16px;
  background: #fff;
  border-bottom: 0.5px solid $line;
}

.price-big {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: $primary;
  font-size: 26px;
  font-weight: 700;
}

.price-symbol {
  font-size: 15px;
}

.price-favs {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: $text-3;
}

.title-big {
  margin-top: 6px;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.45;
}

.tag-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.meta-tag {
  padding: 2px 8px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 11px;
}

.view-count {
  margin-left: auto;
  color: $text-3;
  font-size: 11px;
}

.detail-card {
  margin: 10px;
  padding: 14px;
  border-radius: $radius;
  background: #fff;
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

.loc-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 0.5px solid $line;
  color: $text-2;
  font-size: 12px;
}

.seller-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.seller-main {
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

.seller-info {
  flex: 1;
  min-width: 0;
}

.seller-name {
  font-size: 14px;
  font-weight: 600;
}

.seller-time {
  margin-top: 2px;
  font-size: 11px;
  color: $text-3;
}

.status-tag {
  padding: 2px 10px;
  border-radius: $radius-full;
  background: #e8f6e9;
  color: #2e9e44;
  font-size: 11px;

  &.off {
    background: $bg-secondary;
    color: $text-3;
  }
}

.loading {
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
  gap: 12px;

  .btn-outline {
    flex: 1;

    &.danger {
      border-color: #e0a0a8;
      color: #c0392b;
    }

    &.large {
      height: 40px;
      font-size: 14px;
    }
  }

  .btn-primary {
    flex: 1.5;
  }
}
</style>
