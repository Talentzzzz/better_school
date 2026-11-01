<script setup lang="ts">
import type { PostSummary } from '@/types/api'
import { formatCount } from '@/utils/format'
import AppAvatar from '@/components/AppAvatar.vue'
import AppImage from '@/components/AppImage.vue'

defineProps<{ post: PostSummary }>()
</script>

<template>
  <router-link :to="`/post/${post.postId}`" class="feed-card" :class="{ 'text-card': !post.cover, viewed: post.viewed }">
    <template v-if="post.cover">
      <div class="feed-thumb">
        <AppImage :src="post.cover" :seed="post.postId" :text="''" ratio="1:1" />
      </div>
      <div class="post-title ellipsis-2">{{ post.title || '（无标题）' }}</div>
    </template>
    <template v-else>
      <!-- 无封面帖：摘要在上、标题在底部；无标题则把摘要当 lead 排，不渲染标题行 -->
      <div v-if="post.content" class="text-snippet" :class="{ lead: !post.title }">
        <span :class="post.title ? 'ellipsis-3' : 'ellipsis-5'">{{ post.content }}</span>
      </div>
      <div v-if="post.title" class="text-title ellipsis-2">{{ post.title }}</div>
      <div v-else-if="!post.content" class="text-title ellipsis-2">（无标题）</div>
    </template>
    <div class="post-meta">
      <AppAvatar :src="post.avatar" :name="post.nickname" :size="16" />
      <span class="post-name ellipsis">{{ post.nickname }}</span>
      <span class="post-meta-right">
        <span class="post-likes" :class="{ liked: post.liked }">
          <svg viewBox="0 0 24 24" width="12" height="12" :fill="post.liked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2">
            <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
          </svg>
          {{ formatCount(post.likeCount) }}
        </span>
      </span>
    </div>
  </router-link>
</template>

<style scoped lang="scss">
// 已读灰显（feed-index-uv 票 05）：整卡降不透明度，读过的内容退到视觉次层
.feed-card.viewed {
  opacity: 0.55;
}

.post-title {
  margin-top: 8px;
  padding: 0 2px;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.45;
  color: $text-1;
}

.text-title {
  margin-top: 8px;
  font-size: 15px;
  font-weight: 550;
  line-height: 1.45;
  color: $text-1;
}

.text-snippet {
  font-size: 12px;
  line-height: 1.55;
  color: #6b6b70;

  &.lead {
    font-size: 14px;
    line-height: 1.55;
    color: $text-1;
  }
}

.post-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0 2px;
  color: $text-3;
  font-size: 10px;
}

.post-name {
  max-width: 55%;
}

.post-meta-right {
  margin-left: auto;
  display: inline-flex;
}

.post-likes {
  display: inline-flex;
  align-items: center;
  gap: 2px;

  &.liked {
    color: $primary;
  }
}

// 无封面帖：柔和渐变卡面，其余为白卡
.text-card {
  padding: 14px 12px;
  background: linear-gradient(135deg, #f3f0ff 0%, #eef4ff 100%);

  &:active {
    background: linear-gradient(135deg, #ece8fb 0%, #e8eefe 100%);
  }
}
</style>
