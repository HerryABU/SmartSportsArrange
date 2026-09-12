/**
 * 应用部署前缀工具 —— 兼容反向代理子路径部署（如 http://host/sportmg/ 的「帽子」）
 *
 * 核心原则：严禁硬编码前缀（sportmg 仅仅是例子，任意帽子都支持）。
 * 部署形态：主站根 :port/{name}/【正常业务路径】，反向代理把 {name} 帽子转发给本应用，
 * 应用自身无法也不应感知具体帽子是什么，而是根据当前访问 URL 智能推断。
 *
 * 推断原理：
 *   - 应用自己的顶层路径（/login、/teacher/**、/student/** 等首级路由）是已知的；
 *   - 当前 URL 第一段若「不属于」已知顶层路径 → 它便是反向代理帽子（前缀）；
 *   - 例如 http://host/sportmg/login → 第一段 sportmg 不在已知集合 → base = '/sportmg'
 *   - 例如 http://host/login          → 第一段 login 在已知集合 → base = ''（无帽子）
 *
 * 该推断同时适用于两种反向代理形态：
 *   A. 保留帽子转发（后端收到 /sportmg/...）：由后端智能剥离前缀；
 *   B. 剥掉帽子转发（后端收到 /login）：前端资源相对路径天然适配。
 */
export const KNOWN_TOP_LEVEL = [
  'loading',
  'login',
  'setup',
  'teacher',
  'class-teacher',
  'student'
]

/** 根据当前访问 URL 推断应用部署前缀，返回 ''（无前缀）或 '/xxx' */
export function detectAppBase() {
  if (typeof window === 'undefined') return ''
  const seg = (window.location.pathname.split('/')[1] || '').trim()
  if (!seg) return ''
  if (KNOWN_TOP_LEVEL.includes(seg)) return ''
  return '/' + seg
}

/** 应用部署前缀（运行时计算，模块加载后恒定） */
export const APP_BASE = detectAppBase()

/** 后端 API 前缀（绝对路径，任何页面深度下均正确） */
export const API_BASE = APP_BASE ? APP_BASE + '/api' : '/api'

export function appBase() {
  return APP_BASE
}

export function apiBase() {
  return API_BASE
}

/**
 * 入口归一化 —— hash 模式下 SPA 必须始终运行在「根路径」(/ 或 /帽子/)，路由全部写在 hash 里。
 *
 * 但反向代理 / 书签可能直接命中 /login、/teacher/xxx 等子路径，服务器 SPA 回退会把
 * index.html 落到该子路径，使文档 pathname 被钉死在 /login。此后 router.push 用
 * history.pushState('#/xxx', …) 会被浏览器解析到当前 pathname 之下，最终 URL 变成
 * /login#/teacher/dashboard，且之后永远保留错误的 /login 文档路径。
 *
 * 这里在应用启动最早期做一次纠正：把子路径整段搬进 hash 并重定向到根路径。
 *   /login#/teacher/dashboard  →  /#/teacher/dashboard
 *   /login                     →  /#/login
 *   /sportmg/login             →  /sportmg/#/login
 * 返回 true 表示已触发重定向（当前页面将重新加载）。
 */
export function normalizeHashEntry() {
  if (typeof window === 'undefined') return false
  const path = window.location.pathname // /sportmg/login | /login | /teacher/dashboard
  const seg = (path.split('/')[1] || '').trim()
  // 第一段若是已知顶层路由 → 无帽子；否则第一段就是反向代理帽子前缀
  let hat = ''
  if (seg && !KNOWN_TOP_LEVEL.includes(seg)) hat = '/' + seg
  const afterHat = hat ? path.slice(hat.length) : path // /login | /teacher/dashboard
  const routeSeg = (afterHat.split('/')[1] || '').trim()
  // 仅当子路径本身是已知顶层路由时才纠正，避免误伤 /assets、/api 等
  if (routeSeg && KNOWN_TOP_LEVEL.includes(routeSeg)) {
    let hash = window.location.hash
    if (!hash || hash === '#') hash = '#' + afterHat // 把整段子路径搬进 hash
    const target = window.location.origin + hat + '/' + hash + window.location.search
    window.location.replace(target)
    return true
  }
  return false
}

export default { APP_BASE, API_BASE, appBase, apiBase, detectAppBase, normalizeHashEntry }
