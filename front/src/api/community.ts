import { httpGet, httpPost, httpPut, httpDelete } from './http'
import type {
  PageResult,
  PostSummary,
  PostDetail,
  PostPublishResult,
  PostComment,
  CommentReply,
  AuthorProfileComment
} from '@/types/api'

export interface PostListParams {
  sort?: 'time' | 'hot'
  page?: number
  pageSize?: number
  /** keyset 续页游标（响应 data.cursor 非 null 时必带，此时 page 被忽略） */
  cursor?: string
}

export function listPosts(params: PostListParams) {
  return httpGet<PageResult<PostSummary>>('/community/posts', params)
}

export function listMyPosts(page = 1, pageSize = 10) {
  return httpGet<PageResult<PostSummary>>('/community/posts/mine', { page, pageSize })
}

export function listLikedPosts(page = 1, pageSize = 10) {
  return httpGet<PageResult<PostSummary>>('/community/posts/liked', { page, pageSize })
}

export function listCommentedPosts(page = 1, pageSize = 10) {
  return httpGet<PageResult<PostSummary>>('/community/posts/commented', { page, pageSize })
}

/** 我的浏览历史（最近浏览倒序；已删的他人帖过滤，feed-index-uv 票 05） */
export function listViewHistory(page = 1, pageSize = 10) {
  return httpGet<PageResult<PostSummary>>('/community/posts/history', { page, pageSize })
}

// ---------- 按用户查询（作者主页，免登录，一律公开口径） ----------

/** 某用户的公开帖（「笔记」tab）：仅 NORMAL */
export function listUserPosts(userId: number, page = 1, pageSize = 50) {
  return httpGet<PageResult<PostSummary>>(`/community/users/${userId}/posts`, { page, pageSize })
}

/** 某用户赞过的帖（「赞过」tab）：源帖仅 NORMAL，按点赞时间倒序 */
export function listUserLikedPosts(userId: number, page = 1, pageSize = 50) {
  return httpGet<PageResult<PostSummary>>(`/community/users/${userId}/liked`, { page, pageSize })
}

/** 某用户的评论（「评论」tab）：评论与源帖均 NORMAL，按评论时间倒序 */
export function listUserComments(userId: number, page = 1, pageSize = 50) {
  return httpGet<PageResult<AuthorProfileComment>>(`/community/users/${userId}/comments`, { page, pageSize })
}

export interface PostCreateBody {
  title: string
  content: string
  images: string[]
  /** 独立封面（可空：不传回退相册第一张，均无即无封面帖） */
  cover?: string | null
}

export function createPost(body: PostCreateBody) {
  return httpPost<PostPublishResult>('/community/posts', body)
}

export interface PostDraftBody {
  /** 全字段可缺省（草稿可留空），发布时才校验正文必填 */
  title?: string
  content?: string
  images?: string[]
  cover?: string | null
}

/** 保存帖子草稿（可全空），返回 postId */
export function savePostDraft(body: PostDraftBody) {
  return httpPost<number>('/community/posts/draft', body)
}

/** 编辑草稿（仅 DRAFT 可改，全量覆盖） */
export function updatePostDraft(postId: number, body: PostDraftBody) {
  return httpPut<null>(`/community/posts/${postId}/draft`, body)
}

/** 发布草稿：正文必填，缺项停留草稿 */
export function submitPostDraft(postId: number) {
  return httpPost<PostPublishResult>(`/community/posts/${postId}/submit`)
}

export function getPost(postId: number) {
  return httpGet<PostDetail>(`/community/posts/${postId}`)
}

export function deletePost(postId: number) {
  return httpDelete<null>(`/community/posts/${postId}`)
}

export function likePost(postId: number) {
  return httpPost<null>(`/community/posts/${postId}/like`)
}

export function unlikePost(postId: number) {
  return httpDelete<null>(`/community/posts/${postId}/like`)
}

/** 点赞评论（根评论与子回复通用） */
export function likeComment(commentId: number) {
  return httpPost<null>(`/community/comments/${commentId}/like`)
}

export function unlikeComment(commentId: number) {
  return httpDelete<null>(`/community/comments/${commentId}/like`)
}

/** 根评论排序：hot 最热（like_count 降序），new 最新（id 升序），默认最热 */
export type CommentSort = 'hot' | 'new'

export function listComments(postId: number, page = 1, pageSize = 20, sort: CommentSort = 'hot') {
  return httpGet<PageResult<PostComment>>(`/community/posts/${postId}/comments`, { page, pageSize, sort })
}

/** 发布评论：响应即返回完整新评论 DTO（乐观插顶用） */
export function createComment(postId: number, content: string) {
  return httpPost<PostComment>(`/community/posts/${postId}/comments`, { content })
}

/** 楼内子回复平铺：不传 pageSize 时后端按首页 3 条、之后每页 10 条分页 */
export function listReplies(commentId: number, page: number) {
  return httpGet<PageResult<CommentReply>>(`/community/comments/${commentId}/replies`, { page })
}

/** 回复评论（根评论或楼内子回复），归属由后端计算；响应含完整新回复 DTO */
export function createReply(commentId: number, content: string) {
  return httpPost<CommentReply>(`/community/comments/${commentId}/replies`, { content })
}

export function deleteComment(commentId: number) {
  return httpDelete<null>(`/community/comments/${commentId}`)
}
