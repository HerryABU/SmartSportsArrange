import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    redirect: '/loading'
  },
  {
    path: '/loading',
    name: 'Loading',
    component: () => import('@/views/login/Loading.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/setup',
    name: 'Setup',
    component: () => import('@/views/setup/Setup.vue'),
    meta: { requiresAuth: false }
  },
  // Teacher routes
  {
    path: '/teacher',
    component: () => import('@/layouts/TeacherLayout.vue'),
    redirect: '/teacher/dashboard',
    meta: { requiresAuth: true, role: ['TEACHER', 'SUPER_ADMIN'] },
    children: [
      {
        path: 'dashboard',
        name: 'TeacherDashboard',
        component: () => import('@/views/teacher/Dashboard.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'classes',
        name: 'TeacherClasses',
        component: () => import('@/views/teacher/Classes.vue'),
        meta: { title: '班级管理' }
      },
      {
        path: 'athletes',
        name: 'TeacherAthletes',
        component: () => import('@/views/teacher/Athletes.vue'),
        meta: { title: '运动员管理' }
      },
      {
        path: 'events',
        name: 'TeacherEvents',
        component: () => import('@/views/teacher/Events.vue'),
        meta: { title: '项目管理' }
      },
      {
        path: 'registrations',
        name: 'TeacherRegistrations',
        component: () => import('@/views/teacher/Registration.vue'),
        meta: { title: '报名管理' }
      },
      {
        path: 'arrange',
        name: 'TeacherArrange',
        component: () => import('@/views/teacher/Arrange.vue'),
        meta: { title: '智能编排' }
      },
      {
        path: 'schedule',
        name: 'TeacherSchedule',
        component: () => import('@/views/teacher/Schedule.vue'),
        meta: { title: '项目编排' }
      },
      {
        path: 'scores',
        name: 'TeacherScores',
        component: () => import('@/views/teacher/Scores.vue'),
        meta: { title: '成绩管理' }
      },
      {
        path: 'ranking',
        name: 'TeacherRanking',
        component: () => import('@/views/teacher/Ranking.vue'),
        meta: { title: '排名积分' }
      },
      {
        path: 'reports',
        name: 'TeacherReports',
        component: () => import('@/views/teacher/Reports.vue'),
        meta: { title: '统计报表' }
      },
      {
        path: 'settings',
        name: 'TeacherSettings',
        component: () => import('@/views/teacher/Settings.vue'),
        meta: { title: '系统设置' }
      }
    ]
  },
  // Class teacher routes
  {
    path: '/class-teacher',
    component: () => import('@/layouts/ClassTeacherLayout.vue'),
    redirect: '/class-teacher/dashboard',
    meta: { requiresAuth: true, role: ['CLASS_TEACHER'] },
    children: [
      {
        path: 'dashboard',
        name: 'ClassTeacherDashboard',
        component: () => import('@/views/class-teacher/Dashboard.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'athletes',
        name: 'ClassTeacherAthletes',
        component: () => import('@/views/class-teacher/Athletes.vue'),
        meta: { title: '班级名单' }
      },
      {
        path: 'registration',
        name: 'ClassTeacherRegistration',
        component: () => import('@/views/class-teacher/Registration.vue'),
        meta: { title: '运动会报名' }
      },
      {
        path: 'schedule',
        name: 'ClassTeacherSchedule',
        component: () => import('@/views/class-teacher/Schedule.vue'),
        meta: { title: '赛程查看' }
      },
      {
        path: 'results',
        name: 'ClassTeacherResults',
        component: () => import('@/views/class-teacher/Results.vue'),
        meta: { title: '成绩查看' }
      }
    ]
  },
  // Student routes
  {
    path: '/student',
    component: () => import('@/layouts/StudentLayout.vue'),
    redirect: '/student/home',
    meta: { requiresAuth: true, role: ['STUDENT'] },
    children: [
      {
        path: 'home',
        name: 'StudentHome',
        component: () => import('@/views/student/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'schedule',
        name: 'StudentSchedule',
        component: () => import('@/views/student/Schedule.vue'),
        meta: { title: '我的赛程' }
      },
      {
        path: 'results',
        name: 'StudentResults',
        component: () => import('@/views/student/Results.vue'),
        meta: { title: '我的成绩' }
      },
      {
        path: 'events',
        name: 'StudentEvents',
        component: () => import('@/views/student/Events.vue'),
        meta: { title: '项目浏览' }
      },
      {
        path: 'profile',
        name: 'StudentProfile',
        component: () => import('@/views/student/Profile.vue'),
        meta: { title: '个人中心' }
      }
    ]
  },
  // 404 catch-all
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: '/login'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 安装状态缓存（null=未检查）
let setupStatus = null

async function checkInstalled() {
  if (setupStatus !== null) return setupStatus
  try {
    const res = await fetch('/api/setup/status')
    const data = await res.json()
    setupStatus = data?.data?.installed ?? true
  } catch (e) {
    // 检查失败时按已安装处理，避免卡死在向导
    setupStatus = true
  }
  return setupStatus
}

// Navigation guard
router.beforeEach(async (to, from, next) => {
  // ===== 建站向导守卫（严防死守）=====
  if (to.path === '/setup') {
    const installed = await checkInstalled()
    if (installed) { next('/login'); return }
    next()
    return
  }
  // 未安装时，任何页面一律跳转安装向导
  const installed = await checkInstalled()
  if (!installed) { next('/setup'); return }

  const authStore = useAuthStore()

  // Allow access to login page without auth
  if (to.meta.requiresAuth === false) {
    // If already logged in, redirect to dashboard
    if (authStore.isLoggedIn && to.path === '/login') {
      if (authStore.isTeacher) {
        next('/teacher/dashboard')
      } else if (authStore.isClassTeacher) {
        next('/class-teacher/dashboard')
      } else if (authStore.isStudent) {
        next('/student/home')
      } else {
        next()
      }
      return
    }
    next()
    return
  }

  // Check authentication
  if (to.meta.requiresAuth !== false && !authStore.isLoggedIn) {
    next('/login')
    return
  }

  // Check role
  if (to.meta.role && Array.isArray(to.meta.role)) {
    if (!to.meta.role.includes(authStore.userRole)) {
      // Redirect to appropriate dashboard based on role
      if (authStore.isTeacher) {
        next('/teacher/dashboard')
      } else if (authStore.isClassTeacher) {
        next('/class-teacher/dashboard')
      } else if (authStore.isStudent) {
        next('/student/home')
      } else {
        next('/login')
      }
      return
    }
  }

  next()
})

export default router
