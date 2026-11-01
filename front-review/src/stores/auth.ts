import { defineStore } from 'pinia'
import type { UserInfo } from '@/types/api'

// 登录态沿用主站既有 JWT（spec §5.4「不新建账号体系/SSO」）：
// localStorage key 与 front/src/stores/auth.ts 完全同 key（bs_token / bs_user）——
// 生产环境本工程部署在主站同域名 /review/ 子路径，天然共享登录态；
// 开发环境端口不同（5173/5174）localStorage 隔离，见 README「开发环境登录态」一节。
const TOKEN_KEY = 'bs_token'
const USER_KEY = 'bs_user'

function loadUser(): UserInfo | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null') as UserInfo | null
  } catch {
    return null
  }
}

/** 可用审核中台的角色（唯一事实源：后端 UserRoleVO；与 ModerationController.requireAuditor 同口径） */
export const REVIEW_ROLES = ['AUDITOR', 'ADMIN']

export function canReview(user: UserInfo | null): boolean {
  return !!user && REVIEW_ROLES.includes(user.role)
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: loadUser()
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    canReview: (s) => canReview(s.userInfo),
    nickname: (s) => s.userInfo?.nickname ?? '未登录',
    role: (s) => s.userInfo?.role ?? ''
  },
  actions: {
    clearAuth() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
