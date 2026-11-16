<script setup lang="ts">
import { onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNoticesStore, type NoticeBanner } from '@/stores/notices'

/**
 * 顶部通知横幅（BANNER 通道）：「app 下滑通知栏」式瞬态提醒。
 * 队列最多同时展示 3 条，单条 4s 自动消失，点击跳对应通知中心 tab 并立即消失
 */
const notices = useNoticesStore()
const router = useRouter()

const AUTO_DISMISS_MS = 4000
const MAX_VISIBLE = 3

// 已入场的横幅 id：只对每个横幅起一次自动消失计时
const shown = new Set<string>()
const timers = new Map<string, number>()

function visibleOf(all: NoticeBanner[]): NoticeBanner[] {
  return all.slice(0, MAX_VISIBLE)
}

function scheduleDismiss(banner: NoticeBanner) {
  if (shown.has(banner.id)) return
  shown.add(banner.id)
  const timer = window.setTimeout(() => {
    timers.delete(banner.id)
    notices.dismissBanner(banner.id)
  }, AUTO_DISMISS_MS)
  timers.set(banner.id, timer)
}

// 队列一有新横幅（NOTIF 帧/补放重放）就起它的自动消失计时
watch(
  () => notices.banners,
  (all) => {
    for (const banner of all.slice(0, MAX_VISIBLE)) scheduleDismiss(banner)
  },
  { deep: true, immediate: true }
)

function onClick(banner: NoticeBanner) {
  const timer = timers.get(banner.id)
  if (timer) window.clearTimeout(timer)
  timers.delete(banner.id)
  notices.dismissBanner(banner.id)
  const tab = banner.scope === 'INTERACTION_COMMENT' ? 'comment' : 'like'
  void router.push({ path: '/notifications', query: { tab } })
}

onUnmounted(() => {
  for (const timer of timers.values()) window.clearTimeout(timer)
  timers.clear()
})
</script>

<template>
  <TransitionGroup name="notif" tag="div" class="notif-stack">
    <button
      v-for="banner in visibleOf(notices.banners)"
      :key="banner.id"
      class="notif-banner"
      @click="onClick(banner)"
    >
      <span class="notif-dot"></span>
      <span class="notif-body">
        <span class="notif-title">{{ banner.title }}</span>
        <span class="notif-content ellipsis">{{ banner.content }}</span>
      </span>
      <span class="notif-arrow">›</span>
    </button>
  </TransitionGroup>
</template>

<style scoped>
.notif-stack {
  position: fixed;
  top: calc(env(safe-area-inset-top, 0px) + 8px);
  left: 12px;
  right: 12px;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
}

.notif-banner {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 12px;
  background: rgba(30, 30, 32, 0.92);
  color: #fff;
  text-align: left;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
}

.notif-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-danger, #e24b4a);
}

.notif-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.notif-title {
  font-size: 13px;
  font-weight: 500;
}

.notif-content {
  font-size: 12px;
  opacity: 0.75;
}

.notif-arrow {
  flex: none;
  font-size: 16px;
  opacity: 0.5;
}

.notif-enter-active,
.notif-leave-active {
  transition: all 0.25s ease;
}

.notif-enter-from {
  opacity: 0;
  transform: translateY(-16px);
}

.notif-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
