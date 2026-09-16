<template>
  <div class="dashboard">
    <!-- 角色横幅（管理员 / 体育老师） -->
    <div class="role-hero">
      <h2 class="role-hero-title">{{ isAdmin ? '🛡️ 管理员工作台' : '🏟️ 体育老师工作台' }}</h2>
      <p class="role-hero-desc">
        👋 {{ authStore.user?.realName || authStore.user?.username || '老师' }}，{{ greetingText }}
        ｜ {{ currentDateStr }} {{ currentTime }}
      </p>
      <div class="role-hero-tags">
        <span class="role-hero-tag">{{ isAdmin ? '管理员' : '体育老师' }}</span>
        <span class="role-hero-tag">待办 {{ todoCount }}</span>
        <span v-if="isAdmin" class="role-hero-tag">四类名单批量导入</span>
        <span v-else class="role-hero-tag">导入 → 编排 → 统计</span>
      </div>
    </div>

    <!-- 统计 -->
    <div class="role-stats">
      <div v-for="s in stats" :key="s.label" class="role-stat">
        <div class="role-stat-num">{{ s.value }}</div>
        <div class="role-stat-label">{{ s.label }}</div>
      </div>
    </div>

    <!-- 角色专属入口 -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">{{ isAdmin ? '管理员专属' : '编排与成绩' }}</span>
        <span class="role-card-extra">{{ isAdmin ? '账号 / 名单 / 裁判 / 系统' : '赛程 / 道次 / 成绩 / 排名' }}</span>
      </div>
      <div class="role-quick">
        <div v-for="q in roleQuick" :key="q.path" class="role-quick-item" @click="go(q.path)">
          <span class="role-quick-ico">{{ q.ico }}</span>
          <div>
            <div class="role-quick-title">{{ q.title }}</div>
            <div class="role-quick-desc">{{ q.desc }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 三步工作流 -->
    <div v-for="stage in flowStages" :key="stage.step" class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">{{ stage.step }}. {{ stage.title }}</span>
        <span class="role-card-extra">{{ stage.desc }}</span>
      </div>
      <div class="role-quick">
        <div v-for="l in stage.links" :key="l.path" class="role-quick-item" @click="go(l.path)">
          <span class="role-quick-ico"><el-icon><component :is="l.icon" /></el-icon></span>
          <div>
            <div class="role-quick-title">
              {{ l.label }}
              <el-tag v-if="todoBadge(l.path)" size="small" :type="badgeTypeOf(l.path)" style="margin-left:6px">
                {{ todoBadge(l.path) }}
              </el-tag>
            </div>
            <div class="role-quick-desc">点击进入</div>
          </div>
        </div>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 待办 -->
      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">待办提醒</span>
            <span class="role-card-extra">共 {{ todoCount }} 项</span>
          </div>
          <ul v-if="todos.length" class="role-list">
            <li v-for="t in todos" :key="t.label" class="role-list-item" style="cursor:pointer" @click="t.onClick && t.onClick()">
              <span class="role-quick-ico"><el-icon><component :is="t.icon" /></el-icon></span>
              <div class="role-list-main">
                <div class="role-list-title">{{ t.label }}</div>
              </div>
              <el-tag size="small" :type="t.badgeType || 'info'">{{ t.count }}</el-tag>
            </li>
          </ul>
          <el-empty v-else description="暂无待办" />
        </div>
      </el-col>

      <!-- 报名进度 -->
      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">报名进度</span>
            <span class="role-card-extra">按年级</span>
          </div>
          <ul v-if="registrationProgress.length" class="role-list">
            <li v-for="p in registrationProgress" :key="p.name" class="role-list-item">
              <div class="role-list-main">
                <div class="role-list-title">{{ p.name }} · {{ p.registered }}/{{ p.total }}</div>
                <el-progress :percentage="pct(p)" :stroke-width="8" />
              </div>
            </li>
          </ul>
          <el-empty v-else description="暂无进度数据" />
        </div>
      </el-col>
    </el-row>

    <!-- 今日赛程 -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">今日赛程</span>
        <span class="role-card-extra">{{ todaySchedule.length }} 项</span>
      </div>
      <el-table v-if="todaySchedule.length" :data="todaySchedule" size="small" border stripe>
        <el-table-column label="时间" width="110">
          <template #default="{ row }">{{ row.time || row.startTime || row.slot || '-' }}</template>
        </el-table-column>
        <el-table-column label="项目" min-width="140">
          <template #default="{ row }">{{ row.eventName || row.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="场地" width="120">
          <template #default="{ row }">{{ row.venue || row.venueName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="scheduleStatusType(row.status || row.state)">
              {{ scheduleStatusText(row.status || row.state) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="今日暂无赛程" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import {
  Trophy, Medal, School, UserFilled, EditPen, Grid,
  Document, Connection, DataBoard, DataAnalysis,
  Bell, Clock, WarningFilled, SuccessFilled, CircleCheck
} from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const isAdmin = computed(() => !!authStore.isAdmin)

const greetingText = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了，注意休息 🌙'
  if (hour < 9) return '早上好！今天运动会一定很精彩 ☀️'
  if (hour < 12) return '上午好！比赛正火热进行中 🔥'
  if (hour < 14) return '中午好！休息一下再继续 🍱'
  if (hour < 18) return '下午好！精彩比赛持续上演 🏃'
  return '晚上好！回顾今天的精彩瞬间 🌆'
})

const currentDay = ref(new Date().getDate())
const currentDateStr = ref(
  new Date().getFullYear() + '年' + (new Date().getMonth() + 1) + '月' + new Date().getDate() + '日'
)
const currentTime = ref('')
let timeTimer = null

function updateTime() {
  const now = new Date()
  currentTime.value = now.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  })
  currentDay.value = now.getDate()
  currentDateStr.value = now.getFullYear() + '年' + (now.getMonth() + 1) + '月' + now.getDate() + '日'
}

const stats = ref([
  { label: '班级总数', value: 0, icon: School, color: '#409EFF' },
  { label: '运动员总数', value: 0, icon: UserFilled, color: '#67C23A' },
  { label: '比赛项目', value: 0, icon: Trophy, color: '#E6A23C' },
  { label: '报名总数', value: 0, icon: Document, color: '#F56C6C' }
])

// 角色专属快捷入口
const roleQuick = computed(() => isAdmin.value ? [
  { ico: '👥', title: '用户管理', desc: '班主任 / 体育老师账号批量导入', path: '/teacher/settings?tab=users' },
  { ico: '✨', title: '批量创建', desc: '班级与用户一键生成 · 四类导入入口', path: '/teacher/settings?tab=batch' },
  { ico: '🥇', title: '裁判管理', desc: '裁判花名册与专长（Excel 导入）', path: '/teacher/referees' },
  { ico: '🧑‍⚖️', title: '裁判工作台', desc: '裁判视角：执裁安排汇总', path: '/referee/dashboard' },
  { ico: '⚙️', title: '系统设置', desc: '运动会配置 / 数据库 / 备份', path: '/teacher/settings' }
] : [
  { ico: '🗓️', title: '赛程编排', desc: '天×时段×场地调度', path: '/teacher/schedule' },
  { ico: '🏁', title: '道次编排', desc: '分组分道 · 自检 · 抽签 · 两阶段', path: '/teacher/arrange' },
  { ico: '✍️', title: '成绩录入', desc: '录入与名次积分', path: '/teacher/scores' },
  { ico: '🏆', title: '合分排行', desc: '班级总分与名次', path: '/teacher/ranking' },
  { ico: '🥇', title: '裁判工作安排', desc: '查看各裁判分配', path: '/teacher/referee-board' }
])

function go(path) {
  if (!path) return
  // 带 ?tab= 的深链：整串交给 router，保留查询参数
  router.push(path)
}

// 三步工作流：导入 → 编排 → 统计
const flowStages = [
  {
    step: 1, title: '导入报名', desc: '班级 → 运动员 → 项目 → 报名表',
    links: [
      { label: '班级管理', path: '/teacher/classes', icon: School, color: '#409eff', bg: '#ecf5ff' },
      { label: '运动员名单', path: '/teacher/athletes', icon: UserFilled, color: '#67c23a', bg: '#f0f9eb' },
      { label: '比赛项目', path: '/teacher/events', icon: Trophy, color: '#e6a23c', bg: '#fdf6ec' },
      { label: '报名表导入·审核', path: '/teacher/registrations', icon: Document, color: '#f56c6c', bg: '#fef0f0' }
    ]
  },
  {
    step: 2, title: '编排比赛', desc: '日程 → 道次分组 → 成绩',
    links: [
      { label: '赛程编排', path: '/teacher/schedule', icon: Clock, color: '#6366f1', bg: '#eef2ff' },
      { label: '道次编排', path: '/teacher/arrange', icon: Grid, color: '#0ea5e9', bg: '#ecfeff' },
      { label: '成绩录入', path: '/teacher/scores', icon: EditPen, color: '#f59e0b', bg: '#fffbeb' }
    ]
  },
  {
    step: 3, title: '统计排名', desc: '合分排行 → 秩序册/成绩册',
    links: [
      { label: '合分排行', path: '/teacher/ranking', icon: Medal, color: '#ef4444', bg: '#fef2f2' },
      { label: '报表中心', path: '/teacher/reports', icon: DataAnalysis, color: '#8b5cf6', bg: '#f5f3ff' }
    ]
  }
]

const todoCountOf = (label) => {
  const t = todos.value.find(x => x.label === label)
  return t && t.count ? t.count : 0
}
function todoBadge(path) {
  if (path === '/teacher/registrations') return todoCountOf('待审核报名')
  if (path === '/teacher/arrange') return todoCountOf('未编排项目')
  if (path === '/teacher/scores') return todoCountOf('待录入成绩')
  return 0
}
function badgeTypeOf(path) {
  if (path === '/teacher/registrations') return 'warning'
  if (path === '/teacher/arrange') return 'primary'
  return 'danger'
}

const todos = ref([])
const registrationProgress = ref([])
const todaySchedule = ref([])

const todoCount = computed(() => {
  return todos.value.reduce((sum, t) => sum + (t.count || 0), 0)
})

function scheduleStatusType(code) {
  const map = { preparing: 'info', in_progress: 'success', finished: 'warning', cancelled: 'danger' }
  return map[code] || 'info'
}
function scheduleStatusText(code) {
  const map = { preparing: '待开始', in_progress: '进行中', finished: '已结束', cancelled: '已取消' }
  return map[code] || (code || '-')
}
function pct(p) {
  const total = Number(p.total || 0)
  if (!total) return 0
  return Math.min(100, Math.round((Number(p.registered || 0) / total) * 100))
}

async function fetchStats() {
  try {
    const res = await request.get('/statistics/registration')
    if (res) {
      stats.value[0].value = res.totalClasses || 0
      stats.value[1].value = res.totalAthletes || 0
      stats.value[2].value = res.totalEvents || 0
      stats.value[3].value = res.totalRegistrations || 0
    }
  } catch (e) {
    console.error('统计加载失败', e)
  }
}

async function fetchTodos() {
  try {
    const res = await request.get('/statistics/todo')
    if (res) {
      todos.value = [
        {
          label: '待审核报名', count: res.pendingRegistrations || 0,
          icon: WarningFilled, color: '#E6A23C', badgeType: 'warning',
          onClick: () => router.push('/teacher/registrations')
        },
        {
          label: '未编排项目', count: res.unarrangedEvents || 0,
          icon: Grid, color: '#409EFF', badgeType: '',
          onClick: () => router.push('/teacher/arrange')
        },
        {
          label: '待录入成绩', count: res.pendingScores || 0,
          icon: EditPen, color: '#F56C6C', badgeType: 'danger',
          onClick: () => router.push('/teacher/scores')
        },
        {
          label: '已完成事项', count: res.completed || 0,
          icon: SuccessFilled, color: '#67C23A', badgeType: 'success',
          onClick: () => {}
        }
      ]
    }
  } catch (e) {
    todos.value = []
  }
}

async function fetchRegistrationProgress() {
  try {
    const res = await request.get('/statistics/registration-progress')
    if (Array.isArray(res)) {
      registrationProgress.value = res
    } else if (res?.byGrade) {
      registrationProgress.value = Object.entries(res.byGrade).map(([name, data]) => ({
        name,
        registered: data.registered || 0,
        total: data.total || 0
      }))
    } else {
      registrationProgress.value = []
    }
  } catch (e) {
    registrationProgress.value = []
  }
}

async function fetchTodaySchedule() {
  try {
    const res = await request.get('/statistics/today-schedule')
    todaySchedule.value = Array.isArray(res) ? res : (res?.records || [])
  } catch (e) {
    todaySchedule.value = []
  }
}

onMounted(() => {
  updateTime()
  timeTimer = setInterval(updateTime, 1000)
  fetchStats()
  fetchTodos()
  fetchRegistrationProgress()
  fetchTodaySchedule()
})
onBeforeUnmount(() => {
  if (timeTimer) clearInterval(timeTimer)
})
</script>

<style scoped>
.dashboard { padding: 4px 2px 8px; }
</style>
