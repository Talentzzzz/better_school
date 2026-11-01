<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { AuthorProfileComment } from '@/types/api'
import AppAvatar from '@/components/AppAvatar.vue'
import { formatCount, formatTime } from '@/utils/format'

defineProps<{
  comment: AuthorProfileComment
  nickname: string
  avatar: string | null
}>()

const router = useRouter()
</script>

<template>
  <button class="comment" @click="router.push(`/post/${comment.postId}?commentId=${comment.commentId}`)">
    <AppAvatar :src="avatar" :name="nickname" :size="34" />
    <div class="comment-main">
      <div class="comment-name">{{ nickname }}</div>
      <div class="comment-text">{{ comment.content }}</div>
      <div class="comment-src">
        <span class="from">来自笔记</span>
        <span class="dot">·</span>
        <span class="src-title ellipsis">{{ comment.postTitle }}</span>
      </div>
      <div class="comment-foot">
        <span>{{ formatTime(comment.createdAt) }}</span>
        <span class="like">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
          </svg>
          {{ formatCount(comment.likeCount) }}
        </span>
      </div>
    </div>
  </button>
</template>

<style scoped lang="scss">
.comment {
  display: flex;
  gap: 10px;
  width: 100%;
  padding: 14px 16px;
  text-align: left;

  &:active {
    background: $bg-secondary;
  }
}

.comment-main {
  flex: 1;
  min-width: 0;
}

.comment-name {
  font-size: 13px;
  color: $text-2;
}

.comment-text {
  margin-top: 4px;
  font-size: 15px;
  line-height: 1.5;
  color: $text-1;
}

.comment-src {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: 11px;
  color: $text-3;

  .from {
    flex-shrink: 0;
    color: $blue;
  }

  .dot {
    color: $text-4;
  }

  .src-title {
    flex: 1;
    min-width: 0;
  }
}

.comment-foot {
  display: flex;
  align-items: center;
  margin-top: 8px;
  font-size: 11px;
  color: $text-3;
}

.like {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
</style>
