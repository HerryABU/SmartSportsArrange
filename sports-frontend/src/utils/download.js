/**
 * 携带 Authorization 下载受保护的后端文件（导出 Excel / 下载模板 / 备份包等）。
 * window.open 打开需鉴权接口会 403（浏览器新窗口不带 Bearer），统一走 fetch + blob。
 * @param path 以 / 开头的 API 路径（不含 apiBase）
 * @param fallbackName 服务端未给文件名时的兜底名
 */
import { apiBase } from '@/utils/base'

export async function downloadApi(path, fallbackName = 'download') {
  const token = localStorage.getItem('token') || ''
  const res = await fetch(apiBase() + path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (!res.ok) {
    throw new Error(`下载失败（HTTP ${res.status}）`)
  }
  const blob = await res.blob()
  let name = fallbackName
  const cd = res.headers.get('Content-Disposition') || ''
  // 兼容 filename*=UTF-8''xxx 与 filename="xxx" 两种写法
  const m = cd.match(/filename\*=UTF-8''([^;]+)/i) || cd.match(/filename="?([^";]+)"?/i)
  if (m && m[1]) {
    try { name = decodeURIComponent(m[1].trim()) } catch { name = m[1].trim() }
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  return name
}
