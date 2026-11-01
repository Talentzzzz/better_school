<script setup lang="ts">
import type { ErrandOrder } from '@/types/api'
import { formatCount, formatMoney, formatTime } from '@/utils/format'
import { errandTypeText } from '@/utils/errand'
import AppAvatar from '@/components/AppAvatar.vue'

defineProps<{ order: ErrandOrder }>()
</script>

<template>
  <router-link :to="`/errand/${order.orderId}`" class="feed-card" :class="{ viewed: order.viewed }">
    <div class="errand-head">
      <span class="errand-type">{{ errandTypeText(order.type) }}</span>
      <span class="errand-reward">
        ¥{{ formatMoney(order.reward) }}
      </span>
    </div>
    <div class="errand-desc">
      <span class="ellipsis-4">{{ order.description }}</span>
    </div>
    <div class="errand-loc">
      <span class="ellipsis">{{ order.pickupLocation }}</span>
      <span class="loc-arrow">→</span>
      <span class="ellipsis">{{ order.deliveryLocation }}</span>
    </div>
    <div class="errand-meta">
      <AppAvatar :src="order.publisherAvatar" :name="order.publisherNickname" :size="16" />
      <span class="errand-name ellipsis">{{ order.publisherNickname }}</span>
      <!-- 浏览量 = 热度排序键（market-feed-uv 票 05）；与闲置卡片同一文案，两域口径对齐。
           0 不展示：ES 搜索结果不投影 UV（0 是占位不是真值），宁可不显示也不给错数 -->
      <span class="errand-meta-right">
        <span v-if="order.viewCount > 0">浏览 {{ formatCount(order.viewCount) }}</span>
        <span>{{ formatTime(order.createdAt) }}</span>
      </span>
    </div>
  </router-link>
</template>

<style scoped lang="scss">
// 已读灰显（market-feed-uv 票 05，照 PostCard）：整卡降不透明度，看过的单子退到视觉次层
.feed-card.viewed {
  opacity: 0.55;
}

.errand-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 0 2px;
}

.errand-type {
  padding: 2px 7px;
  border-radius: 5px;
  background: rgba(255, 36, 66, 0.1);
  color: $primary;
  font-size: 10px;
  font-weight: 600;
}

.errand-reward {
  color: $primary;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.errand-desc {
  margin-top: 6px;
  padding: 0 2px;
  font-size: 12px;
  line-height: 1.55;
  color: $text-2;
}

.errand-loc {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-top: 6px;
  padding: 5px 7px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 11px;

  .loc-arrow {
    color: $text-4;
  }
}

.errand-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0 2px;
  color: $text-3;
  font-size: 10px;
}

.errand-name {
  min-width: 0;
  max-width: 38%;
}

.errand-meta-right {
  display: inline-flex;
  gap: 6px;
  margin-left: auto;
  flex-shrink: 0;
  white-space: nowrap;
}
</style>
