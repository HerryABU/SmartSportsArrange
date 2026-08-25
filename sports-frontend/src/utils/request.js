import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { apiBase } from '@/utils/base'

const request = axios.create({
  baseURL: apiBase(),
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

let isRefreshing = false
let failedQueue = []

function processQueue(error, token) {
  failedQueue.forEach(p => {
    if (error) p.reject(error)
    else p.resolve(token)
  })
  failedQueue = []
}

// Request interceptor - attach JWT token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// Response interceptor
request.interceptors.response.use(
  (response) => {
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const res = response.data
    if (res.code !== undefined) {
      if (res.code === 200 || res.code === 0) {
        return res.data !== undefined ? res.data : res
      } else if (res.code === 401) {
        ElMessage.error('登录已过期，请重新登录')
        localStorage.removeItem('token')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('user')
        router.push('/login')
        return Promise.reject(new Error(res.message || '认证失败'))
      } else {
        ElMessage.error(res.message || '请求失败')
        return Promise.reject(new Error(res.message || '请求失败'))
      }
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status } = error.response
      if (status === 401) {
        const originalRequest = error.config
        const refreshToken = localStorage.getItem('refreshToken')

        if (refreshToken && !originalRequest._retry) {
          if (isRefreshing) {
            return new Promise((resolve, reject) => {
              failedQueue.push({ resolve, reject })
            }).then(token => {
              originalRequest.headers.Authorization = `Bearer ${token}`
              return request(originalRequest)
            })
          }

          originalRequest._retry = true
          isRefreshing = true

          return new Promise((resolve, reject) => {
            axios.post(apiBase() + '/auth/refresh', { refreshToken })
              .then(res => {
                const data = res.data?.data || res.data
                const newToken = data?.accessToken
                if (newToken) {
                  localStorage.setItem('token', newToken)
                  processQueue(null, newToken)
                  originalRequest.headers.Authorization = `Bearer ${newToken}`
                  resolve(request(originalRequest))
                } else {
                  processQueue(new Error('refresh failed'), null)
                  reject(error)
                }
              })
              .catch(err => {
                processQueue(err, null)
                localStorage.removeItem('token')
                localStorage.removeItem('refreshToken')
                localStorage.removeItem('user')
                router.push('/login')
                reject(err)
              })
              .finally(() => { isRefreshing = false })
          })
        }

        ElMessage.error('登录已过期，请重新登录')
        localStorage.removeItem('token')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('user')
        router.push('/login')
      } else if (status === 403) {
        ElMessage.error('没有权限访问')
      } else if (status === 404) {
        ElMessage.error('请求的资源不存在')
      } else if (status >= 500) {
        ElMessage.error('服务器错误，请稍后重试')
      } else {
        ElMessage.error(error.response.data?.message || '请求失败')
      }
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络连接')
    } else {
      ElMessage.error('网络错误，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export default request
