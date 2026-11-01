<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deletePost, listMyPosts, moveRejectedPostToDraft, withdrawPost } from '@/api/community'
import type { PostSummary } from '@/types/api'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatTime } from '@/utils/format'
import { POST_STATUS_TEXT, isInDraftBox } from '@/utils/postStatus'
import AppImage from '@/components/AppImage.vue'
import EmptyState from '@/components/EmptyState.vue'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()

const list = ref<PostSummary[]>([])
const loading = ref(true)
const actingId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await listMyPosts(1, 50)
    list.value = res.list.filter(isInDraftBox)
  } finally {
    loading.value = false
  }
}

function rowTitle(p: PostSummary) {
  if (p.title) return p.title
  if (p.content) return p.content
  return '（无标题）'
}

function openRow(p: PostSummary) {
  if (p.status === 'DRAFT') router.push(`/publish/post?draft=${p.postId}`)
  else router.push(`/post/${p.postId}`)
}

async function withLock(postId: number, action: () => Promise<void>) {
  if (actingId.value !== null) return
  actingId.value = postId
  try {
    await action()
  } finally {
    actingId.value = null
  }
}

function toDraft(p: PostSummary) {
  withLock(p.postId, async () => {
    await moveRejectedPostToDraft(p.postId)
    toast('已转入草稿箱，可编辑后重新提交')
    await load()
  })
}

function withdraw(p: PostSummary) {
  withLock(p.postId, async () => {
    const ok = await confirmDialog('撤回这条待审帖？', '撤回后回到草稿箱，可编辑后重新提交')
    if (!ok) return
    await withdrawPost(p.postId)
    toast('已撤回草稿箱')
    await load()
  })
}

function remove(p: PostSummary) {
  withLock(p.postId, async () => {
    const ok = await confirmDialog('删除这条帖子？', '删除后不可恢复')
    if (!ok) return
    await deletePost(p.postId)
    toast('已删除')
    await load()
  })
}

onMounted(load)
</script>

<template>
  <div class="page--plain draft-box-page">
    <NavBar title="草稿箱" />

    <div v-if="list.length" class="draft-rows">
      <div v-for="p in list" :key="p.postId" class="draft-row" @click="openRow(p)">
        <AppImage v-if="p.cover" :src="p.cover" :seed="p.postId" ratio="1:1" class="row-thumb" />
        <div v-else class="row-thumb row-thumb-text">文</div>

        <div class="row-main">
          <div class="row-top">
            <div class="row-title ellipsis-2">{{ rowTitle(p) }}</div>
            <span class="row-status" :class="{ rejected: p.status === 'REJECTED' }">
              {{ POST_STATUS_TEXT[p.status || ''] || '' }}
            </span>
          </div>
          <div v-if="p.status === 'REJECTED' && p.rejectReason" class="row-reason">拒绝理由：{{ p.rejectReason }}</div>
          <div class="row-time">{{ formatTime(p.createdAt) }}</div>

          <div class="row-actions">
            <template v-if="p.status === 'DRAFT'">
              <button class="btn-outline action-btn" @click.stop="openRow(p)">编辑</button>
              <button class="btn-text action-btn danger" @click.stop="remove(p)">删除</button>
            </template>
            <template v-else-if="p.status === 'REJECTED'">
              <button class="btn-outline action-btn" :disabled="actingId === p.postId" @click.stop="toDraft(p)">转草稿</button>
              <button class="btn-text action-btn danger" @click.stop="remove(p)">删除</button>
            </template>
            <template v-else>
              <button
                class="btn-outline action-btn"
                :disabled="actingId === p.postId"
                @click.stop="withdraw(p)"
              >
                撤回
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <EmptyState
      v-else-if="!loading"
      text="草稿箱空空的"
      button-text="去写帖子"
      @click="router.push('/publish/post')"
    />
  </div>
</template>

<style scoped lang="scss">
.draft-rows {
  border-top: 0.5px solid $line;
}

.draft-row {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 0.5px solid $line;
  cursor: pointer;

  &:active {
    background: $bg-secondary;
  }
}

.row-thumb {
  width: 56px;
  height: 56px;
  border-radius: $radius-sm;
  flex-shrink: 0;
}

.row-thumb-text {
  display: flex;
  align-items: center;
  justify-content: center;
  background: $bg-secondary;
  color: $text-3;
  font-size: 18px;
  font-weight: 550;
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.row-top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.row-title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
}

.row-status {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-2;
  font-size: 11px;

  &.rejected {
    background: #ffecec;
    color: #c0392b;
  }
}

.row-reason {
  padding: 6px 8px;
  border-radius: $radius-sm;
  background: #fff7ed;
  color: #b45309;
  font-size: 12px;
  line-height: 1.5;
}

.row-time {
  font-size: 11px;
  color: $text-4;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 2px;
}

.action-btn {
  height: 28px;
  padding: 0 12px;
  font-size: 12px;

  &.danger {
    color: #c0392b;
  }
}
</style>
