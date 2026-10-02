/**
 * 秩序册设计器的接口封装。
 *
 * 单独成文件而不是让三个组件各自手写 request：目录、细则、预览三块要调同一批
 * /api/order-book/** 接口，路径散在各处改起来容易漏；这里把路径和行为收在一处，
 * 组件只管传参。响应统一是 ApiResponse 解包后的 data。
 */
import axios from 'axios'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

/** 整本秩序册的结构（含未启用的，设计器要能看见并改回来）。 */
export function fetchLayout (meetId) {
  return request.get('/api/order-book/layout', { params: meetId ? { meetId } : {} })
}

/** 首次打开时铺好五个默认章节。 */
export function ensureDefaults (meetId) {
  return request.post('/api/order-book/sections/ensure-default', null, { params: meetId ? { meetId } : {} })
}

// ==================== 目录 ====================

export function createSection (payload) {
  return request.post('/api/order-book/sections', payload)
}

export function updateSection (id, payload) {
  return request.put(`/api/order-book/sections/${id}`, payload)
}

export function moveSection (id, dir, meetId) {
  return request.post(`/api/order-book/sections/${id}/move`, { dir }, { params: meetId ? { meetId } : {} })
}

export function deleteSection (id) {
  return request.delete(`/api/order-book/sections/${id}`)
}

export function restoreSection (id) {
  return request.post(`/api/order-book/sections/${id}/restore`)
}

// ==================== 细则 ====================

export function createEntry (payload) {
  return request.post('/api/order-book/entries', payload)
}

export function updateEntry (id, payload) {
  return request.put(`/api/order-book/entries/${id}`, payload)
}

export function moveEntry (id, dir) {
  return request.post(`/api/order-book/entries/${id}/move`, { dir })
}

export function deleteEntry (id) {
  return request.delete(`/api/order-book/entries/${id}`)
}

export function restoreEntry (id) {
  return request.post(`/api/order-book/entries/${id}/restore`)
}

// ==================== 预览 ====================

/**
 * 整本秩序册的 HTML（供 iframe 内联预览）。
 *
 * 走 srcdoc 而不是把 iframe src 指到后端：预览接口同样要鉴权，而 iframe 的子请求
 * 不会带 localStorage 里的 Authorization，直接指过去只会拿到 401 空页。
 */
export async function fetchPreviewHtml (grade) {
  const res = await request.get('/api/order-book/preview', {
    params: grade ? { grade } : {},
    responseType: 'text'
  })
  // 该接口返回裸 HTML（不在 ApiResponse 包装里），拦截器原样放回 axios response
  return typeof res === 'string' ? res : (res && res.data) || ''
}

/**
 * 下载 Word 版秩序册。
 *
 * 这里<b>绕开</b> request 实例、直接用 axios + Authorization 头：拦截器在 blob 分支
 * 只回传二进制、丢掉响应头，而文件名要从后端 Content-Disposition 里取。
 */
export async function downloadOrderBookDocx () {
  const token = localStorage.getItem('token')
  const res = await axios.get(apiBase() + '/api/order-book/preview/docx', {
    responseType: 'blob',
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  const disp = res.headers && res.headers['content-disposition'] || ''
  const m = /filename\*=UTF-8''(.+)$/.exec(disp) || /filename=(.+)$/.exec(disp)
  const name = m ? safeName(m[1]) : '秩序册.docx'
  saveBlob(res.data, name)
}

function saveBlob (blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/** Content-Disposition 里可能被双引号或百分号转义包住，一律先解码再用。 */
function safeName (raw) {
  const s = raw.replace(/"/g, '').trim()
  try {
    return decodeURIComponent(s)
  } catch (e) {
    return s
  }
}
