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
const KNOWN_TOP_LEVEL = [
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

export default { APP_BASE, API_BASE, appBase, apiBase, detectAppBase }
