// 后端统一响应与分页结构
export interface Response<T = unknown> {
  code: string
  info: string
  data: T
}

export interface PageResult<T> {
  page: number
  pageSize: number
  /** -1 = 未知（feed 索引路径不再精确 COUNT，续页以 cursor 为准） */
  total: number
  list: T[]
  /** 非 null = 下一页必须带它走 keyset 续页（主页 feed / 市集推荐页）；null = 按 page 续页或已到末页 */
  cursor?: string | null
}

// ---------- search ----------
export interface UserSummary {
  userId: number
  nickname: string
  avatar: string | null
}

// ---------- auth ----------
export interface UserInfo {
  userId: number
  phone: string
  nickname: string
  avatar: string | null
  role: string
  status: string
  creditScore: number
  lastLoginAt: string | null
}

export interface UserAuthResponse {
  token: string
  userInfo: UserInfo
}

// ---------- 公开用户资料（作者主页 / 「我的」页资料区） ----------

/** 性别：MALE/FEMALE；null＝不展示 */
export type Gender = 'MALE' | 'FEMALE'

export interface PublicUserProfile {
  userId: number
  nickname: string
  avatar: string | null
  /** 个性签名（null＝不展示） */
  signature: string | null
  gender: Gender | null
  /** 获赞：该用户 NORMAL 帖 like_count 之和 */
  receivedLikeCount: number
  /** 隐私开关：公开我的赞过（仅他视生效） */
  showLiked: boolean
  /** 隐私开关：公开我的评论（仅他视生效） */
  showComments: boolean
}

/** 按用户列评论的列表项（作者主页 / 「我的」页「评论」tab） */
export interface AuthorProfileComment {
  commentId: number
  content: string
  likeCount: number
  createdAt: string
  postId: number
  /** 来源帖标题（可空） */
  postTitle: string | null
  /** 来源帖封面（无封面帖为 null） */
  postCover: string | null
}

// ---------- community ----------

export interface PostSummary {
  postId: number
  title: string
  cover: string | null
  userId: number
  nickname: string
  avatar: string | null
  likeCount: number
  createdAt: string
  viewCount: number | null
  /** 仅"我的"接口填充：NORMAL/DRAFT */
  status?: string | null
  /** 当前用户是否已点赞（未登录 null） */
  liked?: boolean | null
  /** 当前用户是否浏览过（主页 feed 已读灰显标记；仅 /community/posts 填充，其余列表 null） */
  viewed?: boolean | null
  /** 正文："我的"列表与搜索全量（客户端 contains 过滤用）；主页 feed 截断摘要（约前 50 字） */
  content?: string | null
}

/** 商品托管订单状态：PENDING_PAYMENT/PAID/DELIVERED/COMPLETED/CANCELLED */
export type ProductOrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'DELIVERED' | 'COMPLETED' | 'CANCELLED'

export interface ProductOrder {
  orderId: number
  productId: number
  productTitle: string
  productImage: string | null
  buyerId: number
  buyerNickname: string
  buyerAvatar: string | null
  sellerId: number
  sellerNickname: string
  sellerAvatar: string | null
  price: number
  status: ProductOrderStatus
  /** 支付单号（仅买家 + 待支付时有值；收银台支付凭据） */
  payNo?: string | null
  createdAt: string
}

export interface PostDetail {
  postId: number
  userId: number
  nickname: string
  avatar: string | null
  title: string
  content: string
  images: string[]
  /** 展示封面（独立封面 ?? 相册第一张，均无 null = 无封面帖） */
  cover: string | null
  /** 原始独立封面（未回退相册，可空）：草稿编辑回填用 */
  independentCover?: string | null
  status: string
  likeCount: number
  commentCount: number
  viewCount: number | null
  liked: boolean
  createdAt: string
}

/** 发帖返利状态：CLAIMED 已发放 / DENIED 今日已领 / NO_REBATE 无返利活动 / CLAIM_ERROR 领取异常待重放 */
export type RebateState = 'CLAIMED' | 'DENIED' | 'NO_REBATE' | 'CLAIM_ERROR'

/** 发帖（直接发布 / 草稿提交）返回的结构化返利结果 */
export interface PostPublishResult {
  postId: string
  rebateState: RebateState
  pointsRebate: number
  drawCountRebate: number
}

export interface PostComment {
  commentId: number
  postId: number
  userId: number
  nickname: string
  content: string
  /** 评论点赞数（根评论与子回复通用） */
  likeCount?: number
  /** 楼内可见子回复数（根评论才有意义） */
  replyCount?: number
  /** 当前用户是否已赞（访客 false） */
  liked?: boolean
  createdAt: string
}

/** 楼内子回复（平铺展示）：parentId ≠ 楼根评论 id 时展示"▶ 被回复人昵称"指向标签 */
export interface CommentReply {
  commentId: number
  postId: number
  parentId: number
  userId: number
  nickname: string
  content: string
  likeCount?: number
  /** 当前用户是否已赞（访客 false） */
  liked?: boolean
  replyToUserId: number
  replyToNickname: string
  createdAt: string
}

// ---------- idle ----------
export type IdleCategory =
  | 'DIGITAL'
  | 'BOOKS'
  | 'DAILY'
  | 'SPORTS'
  | 'TICKETS'
  | 'OTHER'

export interface Product {
  productId: number
  userId: number
  sellerNickname: string
  sellerAvatar: string | null
  title: string
  description: string
  images: string[]
  price: number
  tradeLocation: string
  category: IdleCategory
  status: string
  viewCount: number
  favoriteCount: number
  favorited: boolean
  /** 当前用户是否浏览过（仅推荐列表接口填充，其余列表与详情为 null；未登录 false） */
  viewed?: boolean | null
  createdAt: string
}

// ---------- pay（模拟微信支付收银台） ----------
export type PaymentOrderState = 'WAIT_PAY' | 'PAID' | 'CLOSED'

export interface PaymentOrder {
  payNo: string
  /** 商户订单业务 ID（支付成功后跳回订单详情） */
  bizOrderId: string
  subject: string
  amount: number
  state: PaymentOrderState
  createdAt: string
}

// ---------- credit ----------
export interface CreditInfo {
  creditScore: number
  status: 'NORMAL' | 'BANNED'
}

export interface CreditLog {
  id: number
  change: number
  reasonType: string
  reasonDesc: string
  refId: number | null
  createdAt: string
}

// ---------- chat ----------

/** 会话来源（市集「联系卖家」入口首建时写入，首来源钉死） */
export type RoomSource = 'PRODUCT'

/** 对家角色标签（服务端按来源 + 本人是否首建发起人现算） */
export type RoomPeerRole = 'SELLER' | 'BUYER'

export interface ChatRoomSummary {
  roomId: number
  peerUserId: number | null
  peerNickname: string | null
  peerAvatar: string | null
  peerRole: RoomPeerRole | null
  source: RoomSource | null
  unread: number
  lastMsgType: string | null
  lastMsgPreview: string | null
  lastMsgAt: string | null
}

export interface ChatRoom {
  roomId: number
}

export interface ChatMessage {
  roomId: number
  messageId: number
  seq: number
  fromUserId: number | null
  fromNickname: string | null
  fromAvatar: string | null
  /** SYSTEM=系统通知卡片（系统助手会话，content 为 JSON 载荷，用户不可回复） */
  msgType: 'TEXT' | 'IMAGE' | 'CARD' | 'SYSTEM'
  status: string
  content: string
  refType: string | null
  refId: number | null
  clientMsgId: string | null
  deliverySeq: number | null
  createdAt: string
}

/**
 * 增量补拉一页：nextCursor 是服务端「本次扫描边界」（不是最后一条消息的位置），
 * hasMore 为显式「还有没有」——不再用 list.length < limit 推断追平
 */
export interface ChatTimelinePage {
  list: ChatMessage[]
  nextCursor: number
  hasMore: boolean
}

/** 会话成员水位线（room_member）：1v1 已读回执的水位初值来源 */export interface RoomMember {
  userId: number
  nickname: string
  avatar: string | null
  lastReadSeq: number
}

export interface RoomMembers {
  memberCount: number
  members: RoomMember[]
}

export type NotificationType =
  | 'ANNOUNCEMENT'
  | 'ORDER_EVENT'
  | 'CREDIT_CHANGE'
  | 'POST_COMMENTED'
  | 'COMMENT_REPLIED'
  | 'POST_LIKED'
  | 'COMMENT_LIKED'
  | 'LOTTERY_WON'
  | 'POINTS_CHANGED'

export interface Notification {
  id: number
  /** per-(user, scope) 收件序号：横幅补拉游标锚点（seq > 本地水位 = 没弹过） */
  seq: number
  senderId: number | null
  type: NotificationType
  title: string
  content: string
  read: boolean
  refType: string | null
  refId: number | null
  createdAt: string
}
