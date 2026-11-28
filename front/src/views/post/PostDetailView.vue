<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createComment,
  createReply,
  deleteComment,
  deletePost,
  getPost,
  likeComment,
  likePost,
  listComments,
  listReplies,
  unlikeComment,
  unlikePost,
  type CommentSort
} from '@/api/community'
import { createDirectRoom } from '@/api/chat'
import type { CommentReply, PostComment, PostDetail } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import { formatCount, formatTime } from '@/utils/format'
import AppAvatar from '@/components/AppAvatar.vue'
import ImageCarousel from '@/components/ImageCarousel.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()

const postId = String(route.params.id ?? '')
const post = ref<PostDetail | null>(null)

/** 独立封面（不在相册中）排在相册前展示；封面为相册图时相册自然包含，不重复 */
const displayImages = computed(() => {
  const p = post.value
  if (!p) return []
  return p.cover && !p.images.includes(p.cover) ? [p.cover, ...p.images] : p.images
})

const comments = ref<PostComment[]>([])
const commentPage = ref(1)
const hasMoreComments = ref(true)
const commentSort = ref<CommentSort>('hot')
const commentText = ref('')
const sending = ref(false)
const likeBusy = ref(false)

/** 楼中楼状态：key = 根评论 id */
interface ThreadState {
  expanded: boolean
  replies: CommentReply[]
  page: number
  total: number
  loading: boolean
}
const threadStates = reactive<Record<string, ThreadState>>({})

/** 回复目标：rootId 用于回复楼内子回复后刷新所在楼 */
const replyTarget = ref<{ id: string; nickname: string; rootId: string | null } | null>(null)

async function load() {
  post.value = await getPost(postId)
  await loadComments(true)
}

async function loadComments(reset = false) {
  if (reset) {
    commentPage.value = 1
    hasMoreComments.value = true
    comments.value = []
  }
  if (!hasMoreComments.value) return
  const res = await listComments(postId, commentPage.value, 20, commentSort.value)
  comments.value = [...comments.value, ...res.list]
  hasMoreComments.value = comments.value.length < res.total
  commentPage.value++
}

function switchCommentSort(sort: CommentSort) {
  if (commentSort.value === sort) return
  commentSort.value = sort
  loadComments(true)
}

async function toggleLike() {
  if (!post.value) return
  if (!auth.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (likeBusy.value) return
  likeBusy.value = true
  const liked = post.value.liked
  // 乐观更新
  post.value.liked = !liked
  post.value.likeCount += liked ? -1 : 1
  try {
    if (liked) await unlikePost(postId)
    else await likePost(postId)
  } catch {
    // 回滚
    post.value.liked = liked
    post.value.likeCount += liked ? 1 : -1
  } finally {
    likeBusy.value = false
  }
}

/** 评论/回复点赞（根评论与子回复通用）：乐观更新 + 失败回滚，按评论 id 防抖 */
const commentLikeBusyIds = reactive(new Set<string>())

async function toggleCommentLike(item: PostComment | CommentReply) {
  if (!auth.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (commentLikeBusyIds.has(item.commentId)) return
  commentLikeBusyIds.add(item.commentId)
  const liked = !!item.liked
  item.liked = !liked
  item.likeCount = Math.max(0, (item.likeCount ?? 0) + (liked ? -1 : 1))
  try {
    if (liked) await unlikeComment(item.commentId)
    else await likeComment(item.commentId)
  } catch {
    item.liked = liked
    item.likeCount = Math.max(0, (item.likeCount ?? 0) + (liked ? 1 : -1))
  } finally {
    commentLikeBusyIds.delete(item.commentId)
  }
}

async function loadReplies(rootId: string) {
  const thread = threadStates[rootId]
  if (!thread) return
  thread.loading = true
  try {
    const res = await listReplies(rootId, thread.page)
    thread.replies = [...thread.replies, ...res.list]
    thread.total = res.total
    thread.page++
  } finally {
    thread.loading = false
  }
}

async function toggleThread(c: PostComment) {
  const thread = threadStates[c.commentId]
  if (thread?.expanded) {
    delete threadStates[c.commentId]
    return
  }
  threadStates[c.commentId] = { expanded: true, replies: [], page: 1, total: 0, loading: false }
  await loadReplies(c.commentId)
}

const hasMoreReplies = (rootId: string) => {
  const thread = threadStates[rootId]
  return !!thread && thread.replies.length < thread.total
}

function startReply(id: string, nickname: string, rootId: string | null) {
  if (!auth.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  replyTarget.value = { id, nickname, rootId }
}

function cancelReply() {
  replyTarget.value = null
}

async function refreshThread(rootId: string) {
  const thread = threadStates[rootId]
  if (!thread?.expanded) return
  // 重放到用户已加载的页数，避免回复后把"展开更多"翻过的页重置掉
  const loadedPages = thread.page - 1
  thread.replies = []
  thread.page = 1
  thread.total = 0
  for (let i = 0; i < loadedPages; i++) {
    await loadReplies(rootId)
  }
}

async function submitComment() {
  const content = commentText.value.trim()
  if (!content) {
    toast('说点什么吧')
    return
  }
  if (!auth.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  sending.value = true
  try {
    if (replyTarget.value) {
      const { id, rootId } = replyTarget.value
      await createReply(id, content)
      replyTarget.value = null
      toast('回复成功')
      await loadComments(true)
      if (rootId !== null) await refreshThread(rootId)
    } else {
      const created = await createComment(postId, content)
      // 乐观插顶：响应 DTO 直接插顶，刷新后按服务端查询自然归位
      comments.value = [created, ...comments.value]
      toast('评论成功')
    }
    commentText.value = ''
  } catch {
    // toast 已处理
  } finally {
    sending.value = false
  }
}

async function onDeleteComment(c: PostComment) {
  if (!auth.isLoggedIn) return
  const ok = await confirmDialog((c.replyCount ?? 0) > 0 ? '删除这条评论？该楼所有回复将一并隐藏' : '删除这条评论？')
  if (!ok) return
  try {
    await deleteComment(c.commentId)
    // 删根 = 整楼消失：楼内已加载回复与状态一并清理
    comments.value = comments.value.filter((x) => x.commentId !== c.commentId)
    delete threadStates[c.commentId]
    await syncCommentCount()
  } catch {
    // toast 已处理
  }
}

async function onDeleteReply(r: CommentReply, rootId: string) {
  if (!auth.isLoggedIn) return
  const ok = await confirmDialog('删除这条回复？')
  if (!ok) return
  try {
    await deleteComment(r.commentId)
    const thread = threadStates[rootId]
    if (thread) {
      thread.replies = thread.replies.filter((x) => x.commentId !== r.commentId)
      thread.total = Math.max(0, thread.total - 1)
    }
    // 收起状态的"展开 N 条回复"标签同步
    const root = comments.value.find((x) => x.commentId === rootId)
    if (root) root.replyCount = Math.max(0, (root.replyCount ?? 0) - 1)
    await syncCommentCount()
  } catch {
    // toast 已处理
  }
}

/**
 * 删除后以服务端详情为准同步总数：删根扣 1+楼内可见数，前端无法精确本地推算；
 * 删除链路已失效详情缓存（计数变化时），重新拉取即得权威值
 */
async function syncCommentCount() {
  if (!post.value) return
  try {
    const fresh = await getPost(postId)
    post.value.commentCount = fresh.commentCount
  } catch {
    // 保持本地值
  }
}

async function onDeletePost() {
  const ok = await confirmDialog('删除这条帖子？删除后不可恢复')
  if (!ok) return
  await deletePost(postId)
  toast('已删除')
  router.replace('/home')
}

async function onContact() {
  if (!post.value) return
  const room = await createDirectRoom(post.value.userId)
  chatRooms.scheduleRefresh()
  router.push(`/chat/${room.roomId}?card=POST:${postId}`)
}

const isMine = () => !!auth.userId && post.value?.userId === auth.userId

const canDeleteComment = (c: PostComment | CommentReply) =>
  auth.isLoggedIn && (c.userId === auth.userId || isMine())

/** 「作者」标：评论者即帖子作者 */
const isAuthorComment = (userId: string) => userId === post.value?.userId

/** 作者主页（票 04）尚未落地，路由暂落首页兜底 */
function openAuthor(userId: string) {
  router.push(`/user/${userId}`)
}

/** 顶栏返回沿用 NavBar 的 goBack 语义 */
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/home')
}

onMounted(load)
</script>

<template>
  <div class="page--plain post-detail">
    <header class="topbar">
      <button class="topbar-back" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M15 5l-7 7 7 7" />
        </svg>
      </button>
      <button v-if="post" class="topbar-author" @click="openAuthor(post.userId)">
        <AppAvatar :src="post.avatar" :name="post.nickname" :size="32" />
        <span class="topbar-name">{{ post.nickname }}</span>
      </button>
      <template v-if="post">
        <button v-if="!isMine()" class="btn-outline topbar-contact" @click="onContact">联系</button>
        <button v-else class="topbar-del" @click="onDeletePost">删除</button>
      </template>
      <!-- 分享位：纯装饰图标（票 09 定，不做分享链路，不挂交互） -->
      <span class="topbar-share" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14 4h6v6" />
          <path d="M20 4l-9 9" />
          <path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
        </svg>
      </span>
    </header>

    <template v-if="post">
      <ImageCarousel :images="displayImages" :seed="post.postId" ratio="4:3" />

      <section class="post-body">
        <h1 v-if="post.title" class="post-title">{{ post.title }}</h1>
        <p class="post-content">{{ post.content }}</p>
      </section>

      <section class="comments">
        <div class="comment-head">
          <span class="comment-head-title">全部评论 {{ formatCount(post.commentCount) }}</span>
          <div class="comment-sorts">
            <button class="sort" :class="{ on: commentSort === 'hot' }" @click="switchCommentSort('hot')">最热</button>
            <button class="sort" :class="{ on: commentSort === 'new' }" @click="switchCommentSort('new')">最新</button>
          </div>
        </div>

        <template v-if="comments.length">
          <div v-for="c in comments" :key="c.commentId" class="comment-item">
            <button class="comment-avatar" @click="openAuthor(c.userId)">
              <AppAvatar :name="c.nickname" :size="36" />
            </button>
            <div class="comment-main">
              <button class="comment-name" @click="openAuthor(c.userId)">
                {{ c.nickname }}
                <span v-if="isAuthorComment(c.userId)" class="author-badge">作者</span>
              </button>
              <div class="comment-content">{{ c.content }}</div>
              <div class="comment-meta">
                <span class="comment-time">{{ formatTime(c.createdAt) }}</span>
                <button class="meta-act" @click="startReply(c.commentId, c.nickname, c.commentId)">回复</button>
                <button v-if="canDeleteComment(c)" class="meta-act" @click="onDeleteComment(c)">删除</button>
                <button class="meta-like" :class="{ liked: c.liked }" @click="toggleCommentLike(c)">
                  <svg v-if="c.liked" viewBox="0 0 24 24" width="15" height="15" fill="currentColor">
                    <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7">
                    <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
                  </svg>
                  {{ formatCount(c.likeCount ?? 0) }}
                </button>
              </div>

              <div v-if="threadStates[c.commentId]?.expanded" class="thread">
                <div v-for="r in threadStates[c.commentId].replies" :key="r.commentId" class="reply-item">
                  <button class="reply-avatar" @click="openAuthor(r.userId)">
                    <AppAvatar :name="r.nickname" :size="26" />
                  </button>
                  <div class="reply-main">
                    <button class="comment-name reply-name" @click="openAuthor(r.userId)">
                      {{ r.nickname }}
                      <span v-if="isAuthorComment(r.userId)" class="author-badge">作者</span>
                      <span v-if="r.parentId !== c.commentId && r.replyToNickname" class="reply-to">▶ {{ r.replyToNickname }}</span>
                    </button>
                    <div class="comment-content reply-content">{{ r.content }}</div>
                    <div class="comment-meta">
                      <span class="comment-time">{{ formatTime(r.createdAt) }}</span>
                      <button class="meta-act" @click="startReply(r.commentId, r.nickname, c.commentId)">回复</button>
                      <button v-if="canDeleteComment(r)" class="meta-act" @click="onDeleteReply(r, c.commentId)">删除</button>
                      <button class="meta-like" :class="{ liked: r.liked }" @click="toggleCommentLike(r)">
                        <svg v-if="r.liked" viewBox="0 0 24 24" width="13" height="13" fill="currentColor">
                          <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
                        </svg>
                        <svg v-else viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.7">
                          <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
                        </svg>
                        {{ formatCount(r.likeCount ?? 0) }}
                      </button>
                    </div>
                  </div>
                </div>
                <div class="thread-actions">
                  <button v-if="hasMoreReplies(c.commentId)" class="thread-more" :disabled="threadStates[c.commentId].loading" @click="loadReplies(c.commentId)">
                    展开更多回复
                  </button>
                  <button class="thread-more" @click="toggleThread(c)">收起</button>
                </div>
              </div>
              <button v-else-if="(c.replyCount ?? 0) > 0" class="thread-toggle" @click="toggleThread(c)">
                展开 {{ c.replyCount }} 条回复
              </button>
            </div>
          </div>
          <button v-if="hasMoreComments" class="load-more" @click="loadComments()">加载更多评论</button>
        </template>
        <EmptyState v-else text="还没有评论，抢个沙发～" />
      </section>
    </template>

    <div v-else class="post-loading">加载中…</div>

    <footer class="bottom-bar">
      <div v-if="replyTarget" class="reply-target-bar">
        <span class="reply-target-text">回复 @{{ replyTarget.nickname }}</span>
        <button class="reply-cancel" @click="cancelReply">取消</button>
      </div>
      <div class="bottom-bar-row">
        <input
          v-model="commentText"
          class="comment-input"
          maxlength="500"
          :placeholder="replyTarget ? `回复 @${replyTarget.nickname}…` : '说点什么…'"
          @keyup.enter="submitComment"
        />
        <button v-if="post" class="bar-btn" :class="{ on: post.liked }" @click="toggleLike">
          <svg v-if="post.liked" viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
            <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M12 20.5C7 16.5 3.5 13 3.5 9.6 3.5 7 5.6 5 8.2 5c1.6 0 3.1.8 3.8 2 .7-1.2 2.2-2 3.8-2 2.6 0 4.7 2 4.7 4.6 0 3.4-3.5 6.9-8.5 10.9z" />
          </svg>
          <span>{{ formatCount(post.likeCount) }}</span>
        </button>
        <button v-if="post" class="bar-btn">
          <svg viewBox="0 0 24 24" width="23" height="23" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a8 8 0 0 1-11.7 7.1L4 21l1.9-5.3A8 8 0 1 1 21 12z" />
          </svg>
          <span>{{ formatCount(post.commentCount) }}</span>
        </button>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.post-detail {
  padding-top: 48px;
  padding-bottom: calc(60px + env(safe-area-inset-bottom));
}

/* ---------- 顶栏 = 作者栏 ---------- */
.topbar {
  position: fixed;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  max-width: 480px;
  height: 48px;
  padding: 0 8px;
  background: #fff;
}

.topbar-back {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  color: $text-1;
}

.topbar-author {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 1;
  min-width: 0;
}

.topbar-name {
  font-size: 15px;
  font-weight: 600;
  color: $text-1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.topbar-contact {
  height: 28px;
  padding: 0 12px;
  font-size: 12px;
  flex-shrink: 0;
}

.topbar-del {
  padding: 5px 8px;
  font-size: 13px;
  color: $text-2;
  flex-shrink: 0;
}

.topbar-share {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  color: $text-1;
}

/* ---------- 正文 ---------- */
.post-body {
  padding: 12px 14px 14px;
}

.post-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
}

.post-content {
  margin-top: 6px;
  font-size: 15px;
  line-height: 1.65;
  color: $text-1;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 评论流 ---------- */
.comments {
  padding: 0 14px 12px;
}

.comment-head {
  position: sticky;
  top: 48px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  background: #fff;
}

.comment-head-title {
  font-size: 13px;
  color: $text-3;
}

.comment-sorts {
  display: flex;
  gap: 4px;
}

.sort {
  padding: 2px 10px;
  font-size: 12px;
  color: $text-3;
  border-radius: $radius-full;

  &.on {
    color: $primary;
    font-weight: 600;
    background: $bg-search;
  }
}

.comment-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  /* 评论之间不画分割线，靠留白自然分隔 */
  padding: 14px 0;
}

.comment-avatar {
  flex-shrink: 0;
}

.comment-main {
  flex: 1;
  min-width: 0;
}

.comment-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: $text-3;
  text-align: left;

  &:active {
    opacity: 0.6;
  }
}

.author-badge {
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
  color: $primary;
  background: $primary-light;
  border-radius: 4px;
}

.reply-to {
  font-size: 11px;
  font-weight: 400;
  color: $primary;
}

.comment-content {
  margin-top: 3px;
  font-size: 15px;
  line-height: 1.55;
  color: $text-1;
  word-break: break-word;
}

.comment-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 5px;
}

.comment-time {
  font-size: 12px;
  color: $text-3;
}

.meta-act {
  font-size: 12px;
  color: $text-3;
}

.meta-like {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
  font-size: 12px;
  color: $text-3;

  &.liked {
    color: $primary;
  }
}

/* 楼中楼：不画竖线，靠根评论头像列自然缩进 */
.thread {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.reply-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.reply-avatar {
  flex-shrink: 0;
}

.reply-main {
  flex: 1;
  min-width: 0;
}

.reply-name {
  font-size: 12px;
}

.reply-content {
  font-size: 14px;
}

.thread-actions {
  display: flex;
  gap: 14px;
}

.thread-more {
  align-self: flex-start;
  margin-top: 2px;
  font-size: 12px;
  color: $primary;
}

.thread-toggle {
  margin-top: 4px;
  font-size: 12px;
  color: $primary;
}

.load-more {
  display: block;
  width: 100%;
  padding: 14px 0;
  font-size: 13px;
  color: $text-3;
}

.post-loading {
  padding: 48px 0;
  text-align: center;
  color: $text-3;
}

/* ---------- 底部固定栏 ---------- */
.bottom-bar {
  position: fixed;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 480px;
  padding: 8px 14px calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 0.5px solid $line;
}

.reply-target-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: $primary;
}

.reply-cancel {
  font-size: 12px;
  color: $text-3;
}

.bottom-bar-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.comment-input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 14px;
  border-radius: $radius-full;
  background: $bg-search;
  font-size: 13px;
  color: $text-1;

  &::placeholder {
    color: $text-3;
  }
}

.bar-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: $text-1;
  font-size: 13px;

  &.on {
    color: $primary;
  }
}
</style>
