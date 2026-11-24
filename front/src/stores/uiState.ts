import { defineStore } from 'pinia'

/** 市集 tab（闲置） */
export type MarketTab = 'idle'
export type ProfileMainTab = 'posts' | 'liked' | 'comments' | 'products'

interface UiState {
  marketTab: MarketTab
  profileTab: ProfileMainTab
}

/**
 * 页面 tab 选择（不含列表数据）：跨底部 tabbar 与二级页往返保留 tab 位，
 * 刷新后随 store 重建回落默认值。列表数据活在组件内，卸载即丢。
 * 「我的」页的闲置只有「我发布的」单栏，订单类子栏已收进聚合订单页，故无子 tab 状态。
 */
export const useUiStateStore = defineStore('uiState', {
  state: (): UiState => ({
    marketTab: 'idle',
    profileTab: 'posts'
  }),
  actions: {
    setProfileTab(next: ProfileMainTab) {
      this.profileTab = next
    }
  }
})
