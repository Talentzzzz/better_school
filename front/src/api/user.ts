import { httpGet, httpPut } from './http'
import type { Gender, PublicUserProfile } from '@/types/api'

/** 公开资料（免登录；作者主页与「我的」页资料区共用） */
export function getPublicProfile(userId: string) {
  return httpGet<PublicUserProfile>(`/user/${userId}/profile`)
}

/** 编辑资料：整体覆盖可编辑的两字段（空串/null＝清空·不展示） */
export function updateProfile(body: { signature: string | null; gender: Gender | null }) {
  return httpPut<PublicUserProfile>('/user/profile', body)
}

/** 隐私设置：部分更新（缺省项不改） */
export function updatePrivacy(body: { showLiked?: boolean; showComments?: boolean }) {
  return httpPut<{ showLiked: boolean; showComments: boolean }>('/user/privacy', body)
}
