<script setup lang="ts">
import { useRouter } from 'vue-router'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()

const entries = [
  {
    path: '/publish/post',
    title: '发帖子',
    desc: '校园墙：失物招领、求助互助、日常分享',
    icon: '📝'
  },
  {
    path: '/publish/product',
    title: '卖闲置',
    desc: '发布二手商品，快速出手',
    icon: '🛍️'
  }
]
</script>

<template>
  <div class="page--plain publish-page">
    <NavBar title="发布" />
    <div class="publish-list">
      <button v-for="e in entries" :key="e.path" class="publish-cell" @click="router.push(e.path)">
        <span class="publish-icon">{{ e.icon }}</span>
        <span class="publish-info">
          <span class="publish-title">{{ e.title }}</span>
          <span class="publish-desc">{{ e.desc }}</span>
        </span>
        <span class="publish-arrow">›</span>
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.publish-list {
  margin-top: 12px;
}

.publish-cell {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 18px 20px;
  background: #fff;
  border-bottom: 0.5px solid $line;

  &:active {
    background: $bg-secondary;
  }
}

.publish-icon {
  font-size: 24px;
}

.publish-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
}

.publish-title {
  font-size: 16px;
  font-weight: 600;
}

.publish-desc {
  font-size: 12px;
  color: $text-3;
}

.publish-arrow {
  color: $text-4;
  font-size: 18px;
}
</style>
