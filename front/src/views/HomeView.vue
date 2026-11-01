<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listPosts } from '@/api/community'
import { useWaterfall } from '@/composables/useWaterfall'
import PostCard from '@/components/PostCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()

const chips: { label: string; sort: 'time' | 'hot' }[] = [
  { label: '最热', sort: 'hot' },
  { label: '最新', sort: 'time' }
]
const chipIndex = ref(0)
const chip = computed(() => chips[chipIndex.value])

const { list, loading, finished, sentinel, load, reset } = useWaterfall((page, cursor) =>
  listPosts({
    sort: chip.value.sort,
    page,
    pageSize: 10,
    cursor
  })
)

watch(chipIndex, () => {
  reset()
  load()
})

onMounted(load)
</script>

<template>
  <div class="page home-page">
    <header class="home-nav">
      <div class="nav-left" />
      <div class="nav-tabs">
        <span class="nav-tab">发现</span>
      </div>
      <div class="nav-right">
        <button class="nav-search" aria-label="搜索" @click="router.push('/search?scope=post')">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-4-4" />
          </svg>
        </button>
      </div>
    </header>

    <div class="filter-bar">
      <div class="chips">
        <button
          v-for="(c, i) in chips"
          :key="c.label"
          class="chip"
          :class="{ active: chipIndex === i }"
          @click="chipIndex = i"
        >
          {{ c.label }}
        </button>
      </div>
    </div>

    <div v-if="list.length" class="waterfall">
      <PostCard v-for="p in list" :key="p.postId" :post="p" />
    </div>
    <EmptyState v-else-if="!loading" text="还没有内容，去发布第一条吧" />
    <div ref="sentinel" class="sentinel">{{ loading ? '加载中…' : finished ? '已经到底啦' : '' }}</div>
  </div>
</template>

<style scoped lang="scss">
.home-page {
  padding-bottom: calc(#{$tabbar-h} + env(safe-area-inset-bottom));
  background: $bg-page-alt;
}

.home-nav {
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

.filter-bar {
  position: sticky;
  top: 48px;
  z-index: 99;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 12px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
}

.chips {
  flex: 1;
  display: flex;
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
