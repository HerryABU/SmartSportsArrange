<template>
  <div class="dashboard">
    <!-- 欢迎横幅 -->
    <div class="welcome-banner">
      <div class="banner-left">
        <h2 class="banner-title">👋 欢迎回来，{{ authStore.user?.realName || authStore.user?.username || '管理员' }}</h2>
        <p class="banner-subtitle">{{ greetingText }}</p>
      </div>
      <div class="banner-right">
        <div class="date-display">
          <div class="date-day">{{ currentDay }}</div>
          <div class="date-full">{{ currentDateStr }}</div>
        </div>
      </div>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="12" :md="6" v-for="stat in stats" :key="stat.label">
        <div class="stat-card" :style="{ '--card-color': stat.color }">
          <div class="stat-card-inner">
            <div class="stat-icon-wrap">
              <el-icon :size="28"><component :is="stat.icon" /></el-icon>
            </div>
            <div class="stat-body">
              <div class="stat-value">{{ stat.value }}</div>
              <div class="stat-label">{{ stat.label }}</div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷操作 + 待办提醒 -->
    <el-row :gutter="16" class="info-row">
      <el-col :xs="24" :sm="24" :md="14">
        <el-card class="quick-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <el-icon><Connection /></el-icon>
              <span>运动会工作流 · 导入 → 编排 → 统计</span>
            </div>
          </template>
          <div class="flow-steps">
            <div v-for="stage in flowStages" :key="stage.step" class="flow-stage">
              <div class="flow-stage-head">
                <span class="flow-no">{{ stage.step }}</span>
                <div class="flow-head-text">
                  <div class="flow-title">{{ stage.title }}</div>
                  <div class="flow-desc">{{ stage.desc }}</div>
                </div>
              </div>
              <div class="flow-links">
                <div v-for="link in stage.links" :key="link.path" class="flow-link"
                  @click="$router.push(link.path)">
                  <span class="flow-link-icon" :style="{ background: link.bg, color: link.color }">
                    <el-icon :size="16"><component :is="link.icon" /></el-icon>
                  </span>
                  <span class="flow-link-label">{{ link.label }}</span>
                  <el-badge v-if="todoBadge(link.path)" :value="todoBadge(link.path)" :max="99"
                    :type="badgeTypeOf(link.path)" style="margin-left:auto" />
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="10">
        <el-card class="todo-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <el-icon><Bell /></el-icon>
              <span>待办提醒</span>
              <el-badge :value="todoCount" :hidden="todoCount === 0" :max="99" style="margin-left:8px" />
            </div>
          </template>
          <div v-if="todos.length" class="todo-list">
            <div v-for="item in todos" :key="item.label" class="todo-item" @click="item.onClick">
              <div class="todo-left">
                <el-icon :size="18" :color="item.color"><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </div>
              <el-badge :value="item.count" :type="item.badgeType" v-if="item.count > 0" />
            </div>
          </div>
          <el-empty v-else description="暂无待办事项" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 报名进度 + 今日赛程 -->
    <el-row :gutter="16" class="info-row">
      <el-col :xs="24" :sm="24" :md="12">
        <el-card class="progress-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <el-icon><DataBoard /></el-icon>
              <span>报名进度</span>
            </div>
          </template>
          <div v-if="registrationProgress.length" class="progress-list">
            <div v-for="item in registrationProgress" :key="item.name" class="progress-item">
              <div class="progress-info">
                <span class="progress-name">{{ item.name }}</span>
                <span class="progress-text">{{ item.registered }}/{{ item.total }} 人</span>
              </div>
              <el-progress
                :percentage="item.total > 0 ? Math.round(item.registered / item.total * 100) : 0"
                :stroke-width="14"
                :text-inside="true"
                :status="item.registered >= item.total ? 'success' : undefined"
              />
            </div>
          </div>
          <el-empty v-else description="暂无报名数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="12">
        <el-card class="schedule-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <el-icon><Clock /></el-icon>
              <span>今日赛程</span>
            </div>
          </template>
          <div v-if="todaySchedule.length" class="schedule-list">
            <div v-for="item in todaySchedule" :key="item.id" class="schedule-item">
              <div class="sched-time-col">
                <div class="sched-time">{{ item.time }}</div>
                <div class="sched-location">{{ item.location }}</div>
              </div>
              <div class="sched-divider"></div>
              <div class="sched-info">
                <div class="sched-event">{{ item.eventName }}</div>
                <div class="sched-meta">{{ item.gender }} · {{ item.heat }} · {{ item.status }}</div>
              </div>
              <el-tag :type="scheduleStatusType(item.statusCode)" size="small" effect="light">
                {{ item.status }}
              </el-tag>
            </div>
          </div>
          <el-empty v-else description="今日暂无赛程" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
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
          onClick: () => window._router?.push('/teacher/registrations')
        },
        {
          label: '未编排项目', count: res.unarrangedEvents || 0,
          icon: Grid, color: '#409EFF', badgeType: '',
          onClick: () => window._router?.push('/teacher/arrange')
        },
        {
          label: '待录入成绩', count: res.pendingScores || 0,
          icon: EditPen, color: '#F56C6C', badgeType: 'danger',
          onClick: () => window._router?.push('/teacher/scores')
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
  window._router = useRouter()
  fetchStats()
  fetchTodos()
  fetchRegistrationProgress()
  fetchTodaySchedule()
})

onBeforeUnmount(() => {
  if (timeTimer) clearInterval(timeTimer)
  delete window._router
})
</script>

<style scoped>
.dashboard { padding: 8px; }

.welcome-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #409eff 0%, #337ecc 100%);
  border-radius: 16px;
  padding: 24px 32px;
  margin-bottom: 20px;
  color: #fff;
  box-shadow: 0 8px 24px rgba(64, 158, 255, 0.25);
}
.banner-title { margin: 0 0 6px; font-size: 22px; font-weight: 600; }
.banner-subtitle { margin: 0; font-size: 14px; opacity: 0.85; }
.date-display { text-align: center; }
.date-day { font-size: 36px; font-weight: 700; line-height: 1; opacity: 0.9; }
.date-full { font-size: 13px; opacity: 0.75; margin-top: 4px; }

.stats-row { margin-bottom: 20px; }
.stat-card {
  position: relative;
  background: #fff;
  border-radius: 14px;
  padding: 20px;
  transition: all 0.3s ease;
  border: 1px solid #ebeef5;
  overflow: hidden;
}
.stat-card::after {
  content: '';
  position: absolute;
  top: 0; right: 0;
  width: 80px; height: 80px;
  background: var(--card-color);
  opacity: 0.06;
  border-radius: 50%;
  transform: translate(30%, -30%);
}
.stat-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(0, 0, 0, 0.08); }
.stat-card-inner { display: flex; align-items: center; gap: 14px; }
.stat-icon-wrap {
  width: 52px; height: 52px;
  border-radius: 14px;
  background: var(--card-color);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.stat-value { font-size: 30px; font-weight: 700; color: #303133; line-height: 1.1; }
.stat-label { font-size: 13px; color: #909399; margin-top: 4px; }

.info-row { margin-bottom: 20px; }
.quick-card, .todo-card, .progress-card, .schedule-card {
  border-radius: 14px;
  height: 100%;
}
.quick-card :deep(.el-card__header),
.todo-card :deep(.el-card__header),
.progress-card :deep(.el-card__header),
.schedule-card :deep(.el-card__header) {
  background: linear-gradient(135deg, #f5f7fa 0%, #e8ecf1 100%);
  border-radius: 14px 14px 0 0;
  padding: 14px 20px;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}

.flow-steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  padding: 4px 0;
}
.flow-stage {
  border: 1px solid #ebeef5;
  border-radius: 14px;
  padding: 14px;
  background: linear-gradient(180deg, #fafcff 0%, #ffffff 100%);
  transition: all 0.25s;
}
.flow-stage:hover { box-shadow: 0 8px 20px rgba(0, 0, 0, 0.06); transform: translateY(-2px); }
.flow-stage-head { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.flow-no {
  width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, var(--color-primary), var(--color-info));
  color: #fff; font-size: 15px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
}
.flow-head-text { line-height: 1.3; }
.flow-title { font-size: 15px; font-weight: 700; color: #303133; }
.flow-desc { font-size: 11px; color: #909399; }
.flow-links { display: flex; flex-direction: column; gap: 8px; }
.flow-link {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 10px;
  cursor: pointer; font-size: 13px; color: #303133;
  border: 1px solid transparent;
  transition: all 0.2s;
}
.flow-link:hover { background: #f5f7fa; border-color: #e4e7ed; transform: translateX(2px); }
.flow-link-icon {
  width: 26px; height: 26px; border-radius: 8px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
}
.flow-link-label { color: #303133; }

.todo-list { padding: 4px 0; }
.todo-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.todo-item:hover { background: #f5f7fa; }
.todo-left { display: flex; align-items: center; gap: 10px; font-size: 14px; }

.progress-list { padding: 4px 0; }
.progress-item { margin-bottom: 14px; }
.progress-item:last-child { margin-bottom: 0; }
.progress-info {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.progress-name { font-size: 13px; font-weight: 500; color: #303133; }
.progress-text { font-size: 12px; color: #909399; }

.schedule-list { padding: 4px 0; max-height: 320px; overflow-y: auto; }
.schedule-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.schedule-item:last-child { border-bottom: none; }
.schedule-item:hover { background: #fafafa; border-radius: 6px; }
.sched-time-col { min-width: 70px; text-align: center; }
.sched-time { font-size: 14px; font-weight: 600; color: #409EFF; }
.sched-location { font-size: 11px; color: #909399; }
.sched-divider { width: 1px; height: 32px; background: #e4e7ed; flex-shrink: 0; }
.sched-info { flex: 1; }
.sched-event { font-size: 14px; font-weight: 500; color: #303133; }
.sched-meta { font-size: 12px; color: #909399; margin-top: 2px; }

@media (max-width: 768px) {
  .welcome-banner {
    flex-direction: column;
    text-align: center;
    padding: 20px 16px;
    gap: 12px;
  }
  .banner-title { font-size: 18px; }
  .banner-subtitle { font-size: 13px; }
  .date-display { display: flex; gap: 8px; align-items: baseline; justify-content: center; }
  .date-day { font-size: 28px; }
  .stat-card { padding: 14px; }
  .stat-card-inner { gap: 10px; }
  .stat-icon-wrap { width: 42px; height: 42px; border-radius: 10px; }
  .stat-icon-wrap .el-icon { font-size: 22px; }
  .stat-value { font-size: 24px; }
  .quick-actions { grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .quick-item { padding: 10px 4px; font-size: 12px; }
  .quick-icon { width: 40px; height: 40px; }
  .flow-steps { grid-template-columns: 1fr; }
}
</style>
