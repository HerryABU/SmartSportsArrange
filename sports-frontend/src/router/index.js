import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { appBase, apiBase } from '@/utils/base'

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
        meta: { title: '运动员名单' }
      },
      {
        path: 'events',
        name: 'TeacherEvents',
        component: () => import('@/views/teacher/Events.vue'),
        meta: { title: '比赛项目' }
      },
      {
        path: 'venues',
        name: 'TeacherVenues',
        component: () => import('@/views/teacher/VenueManage.vue'),
        meta: { title: '场地管理' }
      },
      {
        path: 'registrations',
        name: 'TeacherRegistrations',
        component: () => import('@/views/teacher/Registration.vue'),
        meta: { title: '报名表导入·审核' }
      },
      {
        path: 'arrange',
        name: 'TeacherArrange',
        component: () => import('@/views/teacher/Arrange.vue'),
        meta: { title: '道次编排' }
      },
      {
        path: 'schedule',
        name: 'TeacherSchedule',
        component: () => import('@/views/teacher/Schedule.vue'),
        meta: { title: '赛程编排' }
      },
      {
        path: 'scores',
        name: 'TeacherScores',
        component: () => import('@/views/teacher/Scores.vue'),
        meta: { title: '成绩录入' }
      },
      {
        path: 'ranking',
        name: 'TeacherRanking',
        component: () => import('@/views/teacher/Ranking.vue'),
        meta: { title: '合分排行' }
      },
      {
        path: 'reports',
        name: 'TeacherReports',
        component: () => import('@/views/teacher/Reports.vue'),
        meta: { title: '报表中心' }
      },
      {
        path: 'settings',
        name: 'TeacherSettings',
        component: () => import('@/views/teacher/Settings.vue'),
        meta: { title: '系统设置' }
      },
      {
        path: 'referees',
        name: 'TeacherReferees',
        component: () => import('@/views/teacher/Referees.vue'),
        meta: { title: '裁判管理', role: ['SUPER_ADMIN'] }
      },
      {
        path: 'referee-board',
        name: 'TeacherRefereeBoard',
        component: () => import('@/views/teacher/RefereeBoard.vue'),
        meta: { title: '裁判工作安排' }
      },
      {
        path: 'help',
        name: 'TeacherHelp',
        component: () => import('@/views/teacher/Help.vue'),
        meta: { title: '说明书' }
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
  // 裁判工作台（裁判可登录：角色 REFEREE；管理员/体育老师亦可进入查看或投屏）
  {
    path: '/referee',
    component: () => import('@/layouts/RefereeLayout.vue'),
    redirect: '/referee/dashboard',
    meta: { requiresAuth: true, role: ['REFEREE', 'TEACHER', 'SUPER_ADMIN'] },
    children: [
      {
        path: 'dashboard',
        name: 'RefereeDashboard',
        component: () => import('@/views/referee/Dashboard.vue'),
        meta: { title: '执裁看板' }
      },
      {
        path: 'board',
        name: 'RefereeBoardAll',
        component: () => import('@/views/teacher/RefereeBoard.vue'),
        // 全体裁判安排为管理端视图，裁判本人只看看板（自己的安排）
        meta: { title: '全体裁判安排', role: ['TEACHER', 'SUPER_ADMIN'] }
      }
    ]
  },
  // 现场大屏（数据大屏 / 排行榜大屏，全屏投屏用）
  {
    path: '/screen',
    name: 'ScreenBoard',
    component: () => import('@/views/screen/MeetBoard.vue'),
    meta: { requiresAuth: true, role: ['TEACHER', 'SUPER_ADMIN'] }
  },
  // 404 catch-all
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: '/login'
  }
]

const router = createRouter({
  // 使用 hash 模式：URL 形如 /#/login、/sportmg/#/login。
  // 反向代理（cpolar/ngrok/nginx 子路径等）下服务器永远只收到「/」或「/sportmg/」，
  // 不会把前端路由路径（如 /teacher/dashboard）发往后端，从而彻底规避：
  //   1) 历史模式深链刷新时 ./assets 相对到 /sportmg/teacher/assets 被误判为 SPA 路由而回退成 index.html（白屏）；
  //   2) 路由基准与反代帽子前缀不一致导致的整页空白。
  // 同时兼容「子域(无帽子)」与「子路径(有帽子)」两种反代形态（帽子由 base.js 实时推断）。
  // 注意：带帽子时 base 必须是 "/sportmg/"（带尾斜杠），否则 pushState('#/xxx') 会丢失帽子前缀。
  history: createWebHashHistory(appBase() ? appBase() + '/' : '/'),
  routes
})

// 安装状态缓存（null=未检查）
let setupStatus = null

async function checkInstalled() {
  if (setupStatus !== null) return setupStatus
  try {
    const res = await fetch(apiBase() + '/setup/status')
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
      } else if (authStore.isReferee) {
        next('/referee/dashboard')
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
      } else if (authStore.isReferee) {
        next('/referee/dashboard')
      } else {
        next('/login')
      }
      return
    }
  }

  next()
})

export default router
