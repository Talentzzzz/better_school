// 主站跳转辅助（票 12：客户端路由守卫「非审核员/管理员跳主站登录」）
//
// 生产：本工程在主站同域名 /review/ 子路径下，主站登录页即同源 /login（origin 留空 = 同源）。
// 开发：独立端口 5174 与主前端 5173 的 localStorage 互不可见，需显式指主站 origin；
//       可用 VITE_MAIN_SITE_ORIGIN 覆盖，缺省 http://localhost:5173。

function mainSiteOrigin(): string {
  const fromEnv = import.meta.env.VITE_MAIN_SITE_ORIGIN as string | undefined
  if (fromEnv) return fromEnv.replace(/\/$/, '')
  if (import.meta.env.DEV) return 'http://localhost:5173'
  return ''
}

/** 主站登录页地址（登录成功后主站回到自身首页；审核员再回到本工程 URL 即可） */
export function mainSiteLoginUrl(): string {
  return `${mainSiteOrigin()}/login`
}

/** 主站首页地址（登出/无权限时的落脚点） */
export function mainSiteHomeUrl(): string {
  return `${mainSiteOrigin()}/`
}

// 「只跳一次」闸（http 拦截器与路由守卫共用，避免并发 401/守卫重复触发跳转）
let redirecting = false

/** 回主站登录（幂等：并发调用只跳一次） */
export function gotoMainLoginOnce() {
  if (redirecting) return
  redirecting = true
  window.location.href = mainSiteLoginUrl()
}
