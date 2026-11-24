<script setup lang="ts">
import type { Product } from '@/types/api'
import { formatCount, formatMoney } from '@/utils/format'
import { tradeCategoryText } from '@/utils/trade'
import AppAvatar from '@/components/AppAvatar.vue'
import AppImage from '@/components/AppImage.vue'

const props = defineProps<{ product: Product }>()
</script>

<template>
  <router-link :to="`/product/${props.product.productId}`" class="feed-card" :class="{ viewed: props.product.viewed }">
    <div class="feed-thumb">
      <AppImage
        :src="props.product.images[0]"
        :seed="props.product.productId"
        :text="tradeCategoryText(props.product.category) || '二手'"
        ratio="1:1"
      />
    </div>
    <div class="product-title ellipsis-2">{{ props.product.title }}</div>
    <div class="product-price">
      <span class="price-symbol">¥</span>{{ formatMoney(props.product.price) }}
    </div>
    <div class="product-meta">
      <AppAvatar :src="props.product.sellerAvatar" :name="props.product.sellerNickname" :size="16" />
      <span class="product-name ellipsis">{{ props.product.sellerNickname }}</span>
      <!-- 浏览量 = 热度排序键（market-feed-uv 票 05）。
           0 不展示：ES 搜索结果不投影 UV（0 是占位不是真值），宁可不显示也不给错数 -->
      <span class="product-stats">
        <span v-if="props.product.viewCount > 0">浏览 {{ formatCount(props.product.viewCount) }}</span>
        <span>{{ formatCount(props.product.favoriteCount) }}人想要</span>
      </span>
    </div>
  </router-link>
</template>

<style scoped lang="scss">
// 已读灰显（market-feed-uv 票 05，照 PostCard）：整卡降不透明度，看过的商品退到视觉次层
.feed-card.viewed {
  opacity: 0.55;
}

.product-title {
  margin-top: 8px;
  padding: 0 2px;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.45;
  color: $text-1;
}

.product-price {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 0 2px;
  color: $primary;
  font-size: 15px;
  font-weight: 700;

  .price-symbol {
    font-size: 11px;
  }
}

.product-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0 2px;
  color: $text-3;
  font-size: 10px;
}

.product-name {
  max-width: 34%;
  min-width: 0;
}

.product-stats {
  display: inline-flex;
  gap: 6px;
  margin-left: auto;
  flex-shrink: 0;
  white-space: nowrap;
}
</style>
