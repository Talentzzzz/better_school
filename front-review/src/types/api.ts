// 审核中台 · 基础响应类型
// 手抄自后端 com.betterschool.api.response.Response / PageResult（唯一事实源在后端），
// 与 front/src/types/api.ts 同款——共享类型按 spec §5.4 口径「手抄 + 后端为唯一事实源 + 两侧注释互指」。

export interface Response<T = unknown> {
  code: string
  info: string
  data: T
}

export interface PageResult<T> {
  page: number
  pageSize: number
  total: number
  list: T[]
}

/** 登录用户信息（手抄自后端用户 VO；bs_user localStorage 与主站 front/src/stores/auth.ts 同 key 同形） */
export interface UserInfo {
  userId: number
  phone: string
  nickname: string
  avatar: string | null
  /** USER / AUDITOR / ADMIN（唯一事实源：后端 UserRoleVO） */
  role: string
  status: string
  creditScore: number
  lastLoginAt: string | null
}
