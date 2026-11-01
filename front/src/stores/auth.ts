import { defineStore } from 'pinia'
import type { UserInfo } from '@/types/api'

const TOKEN_KEY = 'bs_token'
const USER_KEY = 'bs_user'

function loadUser(): UserInfo | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: loadUser()
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    userId: (s) => s.userInfo?.userId ?? null
  },
  actions: {
    setAuth(token: string, userInfo: UserInfo) {
      this.token = token
      this.userInfo = userInfo
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(userInfo))
    },
    clearAuth() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
