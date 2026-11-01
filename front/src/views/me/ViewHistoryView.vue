<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listViewHistory } from '@/api/community'
import type { PostSummary } from '@/types/api'
import { useWaterfall } from '@/composables/useWaterfall'
import PostCard from '@/components/PostCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()

const { list, loading, finished, sentinel, load } = useWaterfall<PostSummary>((page) =>
  listViewHistory(page, 10)
)

onMounted(load)
</script>

<template>
  <div class="page--plain history-page">
    <NavBar title="浏览历史" />
    <div v-if="list.length" class="waterfall">
      <PostCard v-for="p in list" :key="p.postId" :post="p" />
    </div>
    <EmptyState
      v-else-if="!loading"
      text="还没有浏览记录"
      button-text="去逛逛"
      @click="router.push('/home')"
    />
    <div ref="sentinel" class="sentinel">{{ loading ? '加载中…' : finished ? '已经到底啦' : '' }}</div>
  </div>
</template>

<style scoped lang="scss">
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
