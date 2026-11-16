<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listNotifications, markNotificationsRead } from '@/api/chat'
import type { Notification } from '@/types/api'
import { useNoticesStore } from '@/stores/notices'
import { formatTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()

const TAB_TITLE: Record<string, string> = {
  like: '收到的赞',
  comment: '收到的评论',
  system: '系统通知'
}

const EMPTY_TEXT: Record<string, string> = {
  like: '暂无消息，多发笔记可以收获点赞～',
  comment: '暂无消息，多发笔记可以收获评论～'
}

const active = ref<'like' | 'comment' | 'system'>('like')
const list = ref<Notification[]>([])
const loading = ref(false)

const TYPE_TEXT: Record<string, string> = {
  ANNOUNCEMENT: '公告',
  ORDER_EVENT: '订单',
  CREDIT_CHANGE: '信用',
  POST_LIKED: '赞',
  COMMENT_LIKED: '赞',
  POST_COMMENTED: '评',
  COMMENT_REPLIED: '评',
  LOTTERY_WON: '中奖',
  POINTS_CHANGED: '积分'
}

/** 每个 tab 对应一个后端收件范围（scope + 水位线读模型），服务端过滤、按范围推已读水位 */
const TAB_SCOPE: Record<'like' | 'comment' | 'system', string> = {
  like: 'INTERACTION_LIKE',
  comment: 'INTERACTION_COMMENT',
  system: 'BOTTOM_SYSTEM'
}

function syncFromQuery() {
  const t = route.query.tab as string
  if (t === 'like' || t === 'comment' || t === 'system') active.value = t
}

const PAGE_SIZE = 50

async function load() {
  loading.value = true
  try {
    const scope = TAB_SCOPE[active.value]
    const all: Notification[] = []
    for (let page = 1; ; page++) {
      const res = await listNotifications(page, PAGE_SIZE, scope)
      all.push(...res.list)
      if (all.length >= res.total) break
    }
    list.value = all
    await markNotificationsRead(scope)
    // 已读水位推进后同步呈现层未读角标（NOTIF 帧累加的本地值归服务端口径）
    void useNoticesStore().refreshUnread()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  syncFromQuery()
  load()
})

watch(
  () => route.query.tab,
  () => {
    syncFromQuery()
    load()
  }
)
</script>

<template>
  <div class="page--plain notif-page">
    <NavBar :title="TAB_TITLE[active] || '通知'">
      <template #right>
        <button v-if="active === 'system'" class="read-all-btn" @click="load">全部已读</button>
      </template>
    </NavBar>

    <!-- 赞/评论：互动通知（后端 issue 06 起提供四类） -->
    <template v-if="active !== 'system'">
      <div v-if="list.length" class="notif-list">
        <div v-for="n in list" :key="n.id" class="notif-row" :class="{ unread: !n.read }">
          <span class="notif-icon">{{ TYPE_TEXT[n.type] || '通知' }}</span>
          <div class="notif-main">
            <div class="notif-title">{{ n.title }}</div>
            <div class="notif-content">{{ n.content }}</div>
          </div>
          <div class="notif-time">{{ formatTime(n.createdAt) }}</div>
        </div>
      </div>
      <EmptyState
        v-else-if="!loading"
        class="notif-empty"
        :text="EMPTY_TEXT[active]"
        button-text="去逛逛"
        @click="$router.push('/home')"
      />
    </template>

    <template v-else>
      <div v-if="list.length" class="notif-list">
        <div v-for="n in list" :key="n.id" class="notif-row" :class="{ unread: !n.read }">
          <span class="notif-icon">{{ TYPE_TEXT[n.type] || '通知' }}</span>
          <div class="notif-main">
            <div class="notif-title">{{ n.title }}</div>
            <div class="notif-content">{{ n.content }}</div>
          </div>
          <div class="notif-time">{{ formatTime(n.createdAt) }}</div>
        </div>
      </div>
      <EmptyState v-else-if="!loading" text="暂无系统通知" />
    </template>
  </div>
</template>

<style scoped lang="scss">
.notif-page {
  min-height: 100vh;
}

.notif-empty {
  padding-top: 90px;
}

.read-all-btn {
  padding: 6px 10px;
  font-size: 13px;
  color: $text-2;

  &:active {
    opacity: 0.7;
  }
}

.notif-list {
  background: #fff;
}

.notif-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 0.5px solid $line;

  &.unread {
    background: #fffbf0;

    .notif-title {
      font-weight: 600;
    }
  }
}

.notif-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: $bg-secondary;
  font-size: 12px;
  color: $text-2;
  flex-shrink: 0;
}

.notif-main {
  flex: 1;
  min-width: 0;
}

.notif-title {
  font-size: 14px;
}

.notif-content {
  margin-top: 2px;
  font-size: 12px;
  color: $text-3;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.notif-time {
  align-self: flex-start;
  margin-top: 4px;
  font-size: 11px;
  color: $text-4;
}
</style>
