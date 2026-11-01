import { httpPost } from './http'
import type { UserAuthResponse } from '@/types/api'

export function sendCode(phone: string) {
  return httpPost<null>('/user/send-code', { phone })
}

export function register(phone: string, code: string, nickname: string) {
  return httpPost<UserAuthResponse>('/user/register', { phone, code, nickname })
}

export function login(phone: string, code: string) {
  return httpPost<UserAuthResponse>('/user/login', { phone, code })
}
