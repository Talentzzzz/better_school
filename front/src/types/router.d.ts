import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    tabbar?: boolean
    /** 允许访问的角色（如 ['ADMIN']），不匹配重定向首页 */
    roles?: string[]
  }
}
