import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { gotoMainLoginOnce } from '@/utils/mainSite'

// 客户端路由守卫（票 12：非审核员/管理员 → 跳主站登录）+ 服务端 requireAuditor 双保险。
// 本工程单页单路由；base 跟随 vite base（生产 /review/ 子路径，开发 /）。

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'console', component: () => import('@/views/ConsoleView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/') return
  const auth = useAuthStore()
  // 未登录或角色不够（唯一事实源：后端 UserRoleVO / requireAuditor 口径）→ 跳主站登录。
  // 开发环境端口隔离拿不到主站登录态，同样走此跳转，见 README「开发环境登录态」。
  if (!auth.isLoggedIn || !auth.canReview) {
    gotoMainLoginOnce()
    return false
  }
})

export default router
