import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/utils/request'

export const useAuthStore = defineStore('auth', () => {
  // State
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))
  const permissions = ref(JSON.parse(localStorage.getItem('permissions') || '[]'))

  /** 去掉 Spring Security ROLE_ 前缀，返回纯角色名 */
  function stripRolePrefix(role) {
    if (!role) return ''
    return role.replace(/^ROLE_/, '')
  }

  // Getters
  const isLoggedIn = computed(() => !!token.value)

  const userRole = computed(() => {
    return stripRolePrefix(user.value?.role || '')
  })

  const isTeacher = computed(() => {
    const r = userRole.value
    return r === 'TEACHER' || r === 'SUPER_ADMIN'
  })

  const isAdmin = computed(() => {
    return userRole.value === 'SUPER_ADMIN'
  })

  const isClassTeacher = computed(() => {
    return userRole.value === 'CLASS_TEACHER'
  })

  const isStudent = computed(() => {
    return userRole.value === 'STUDENT'
  })

  // Actions
  async function login(username, password) {
    try {
      const res = await request.post('/auth/login', {
        username,
        password
      })
      token.value = res.accessToken
      refreshToken.value = res.refreshToken || ''
      user.value = res.user || null
      permissions.value = res.permissions || []

      localStorage.setItem('token', token.value)
      localStorage.setItem('refreshToken', refreshToken.value)
      localStorage.setItem('user', JSON.stringify(user.value))
      localStorage.setItem('permissions', JSON.stringify(permissions.value))

      return { success: true, data: res }
    } catch (error) {
      return { success: false, message: error.message || '登录失败' }
    }
  }

  function logout() {
    token.value = ''
    refreshToken.value = ''
    user.value = null
    permissions.value = []

    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('user')
    localStorage.removeItem('permissions')
  }

  async function refreshTokenAction() {
    try {
      const res = await request.post('/auth/refresh', {
        refreshToken: refreshToken.value
      })
      token.value = res.accessToken
      localStorage.setItem('token', token.value)
      return true
    } catch (error) {
      logout()
      return false
    }
  }

  async function fetchProfile() {
    try {
      const res = await request.get('/auth/profile')
      user.value = res.user || res
      localStorage.setItem('user', JSON.stringify(user.value))
      return user.value
    } catch (error) {
      return null
    }
  }

  return {
    token,
    refreshToken,
    user,
    permissions,
    isLoggedIn,
    userRole,
    isTeacher,
    isClassTeacher,
    isStudent,
    isAdmin,
    login,
    logout,
    refreshTokenAction,
    fetchProfile
  }
})
