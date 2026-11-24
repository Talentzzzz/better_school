import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    {
      path: '/home',
      component: () => import('@/views/HomeView.vue'),
      meta: { tabbar: true }
    },
    {
      path: '/market',
      component: () => import('@/views/MarketView.vue'),
      meta: { tabbar: true }
    },
    {
      path: '/messages',
      component: () => import('@/views/MessagesView.vue'),
      meta: { tabbar: true, requiresAuth: true }
    },
    {
      path: '/profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { tabbar: true, requiresAuth: true }
    },
    { path: '/publish', component: () => import('@/views/PublishIndexView.vue'), meta: { requiresAuth: true } },
    { path: '/publish/post', component: () => import('@/views/post/PostPublishView.vue'), meta: { requiresAuth: true } },
    { path: '/publish/product', component: () => import('@/views/trade/ProductPublishView.vue'), meta: { requiresAuth: true } },
    { path: '/login', component: () => import('@/views/auth/LoginView.vue') },
    { path: '/search', component: () => import('@/views/SearchView.vue') },
    { path: '/post/:id(\\d+)', component: () => import('@/views/post/PostDetailView.vue') },
    { path: '/product/:id(\\d+)', component: () => import('@/views/trade/ProductDetailView.vue') },
    { path: '/user/:userId(\\d+)', component: () => import('@/views/AuthorProfileView.vue'), meta: { tabbar: true } },
    { path: '/settings/profile', component: () => import('@/views/settings/ProfileEditView.vue'), meta: { requiresAuth: true } },
    { path: '/settings/privacy', component: () => import('@/views/settings/PrivacySettingsView.vue'), meta: { requiresAuth: true } },
    { path: '/chat/:roomId(\\d+)', component: () => import('@/views/chat/ChatDetailView.vue'), meta: { requiresAuth: true } },
    { path: '/messages/contacts', component: () => import('@/views/chat/ContactSearchView.vue'), meta: { requiresAuth: true } },
    { path: '/notifications', component: () => import('@/views/msg/NotificationsView.vue'), meta: { requiresAuth: true } },
    // 市集消息页：入口行「消息」格的去向（入口行由票 04 接）
    { path: '/market/messages', component: () => import('@/views/msg/MarketMessagesView.vue'), meta: { requiresAuth: true } },
    // 营销活动页：入口行「活动」格的去向；不加 requiresAuth（访客看 banner + 奖品目录）
    { path: '/marketing', component: () => import('@/views/marketing/MarketingView.vue') },
    { path: '/marketing/prizes', component: () => import('@/views/marketing/MyPrizesView.vue'), meta: { requiresAuth: true } },
    { path: '/me/wallet', component: () => import('@/views/me/WalletView.vue'), meta: { requiresAuth: true } },
    { path: '/me/credit', component: () => import('@/views/me/CreditView.vue'), meta: { requiresAuth: true } },
    // 聚合订单页：订单类唯一入口（?tab=idle）；收藏仍走 /me/favorites
    { path: '/me/orders', component: () => import('@/views/me/MyOrdersView.vue'), meta: { requiresAuth: true } },
    { path: '/me/favorites', component: () => import('@/views/me/MyFavoritesView.vue'), meta: { requiresAuth: true } },
    { path: '/me/post-drafts', component: () => import('@/views/post/PostDraftBoxView.vue'), meta: { requiresAuth: true } },
    { path: '/me/history', component: () => import('@/views/me/ViewHistoryView.vue'), meta: { requiresAuth: true } },
    { path: '/trade/order/:id(\\d+)', component: () => import('@/views/trade/ProductOrderView.vue'), meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.includes(auth.userInfo?.role ?? '')) {
    return '/home'
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return '/home'
  }
})

export default router
