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
        <span class="role-hero-tag">按序操作：录入 → 编排 → 统计</span>
      </div>
    </div>

    <!-- 统计 -->
    <div class="role-stats">
      <div v-for="s in stats" :key="s.label" class="role-stat">
        <div class="role-stat-num">{{ s.value }}</div>
        <div class="role-stat-label">{{ s.label }}</div>
      </div>
    </div>

    <!-- ============ 分步流程（顺序 + 录入） ============ -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">运动会工作流 · 一步一步来</span>
        <span class="role-card-extra">
          <el-tag v-if="currentStepNo" type="primary" size="small" effect="dark" round>
            建议当前：第 {{ currentStepNo }} 步
          </el-tag>
          <span v-else>共 {{ workflowSteps.length }} 步</span>
        </span>
      </div>

      <div class="role-steps">
        <div
          v-for="s in workflowSteps"
          :key="s.no"
          class="role-step"
          :class="{ 'is-current': currentStepNo === s.no }"
        >
          <div class="role-step-no">{{ s.no }}</div>
          <div class="role-step-body">
            <div class="role-step-head">
              <span class="role-step-title">{{ s.title }}</span>
              <span class="role-step-tag" :class="'tag-' + s.kind">{{ kindLabel(s.kind) }}</span>
              <el-tag v-if="todoOf(s)" size="small" :type="todoTypeOf(s)" effect="plain">
                待办 {{ todoOf(s) }}
              </el-tag>
              <div class="role-step-action">
                <el-button size="small" :type="currentStepNo === s.no ? 'primary' : 'default'" @click="go(s.to)">
                  {{ actionLabel(s.kind) }}
                </el-button>
              </div>
            </div>
            <div class="role-step-desc">{{ s.desc }}</div>
            <div v-if="s.tip" class="role-step-tip">💡 {{ s.tip }}</div>
          </div>
        </div>
      </div>

      <el-alert
        v-if="currentStepNo"
        type="info"
        :closable="false"
        show-icon
        style="margin-top:6px"
        :title="`当前建议先完成第 ${currentStepNo} 步（该步骤还有待办未处理）`"
      />
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

    <!-- 其他入口（非工作流项） -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">其他入口</span>
        <span class="role-card-extra">随时可用</span>
      </div>
      <div class="role-quick">
        <div v-for="q in otherLinks" :key="q.path" class="role-quick-item" @click="go(q.path)">
          <span class="role-quick-ico">{{ q.ico }}</span>
          <div>
            <div class="role-quick-title">{{ q.title }}</div>
            <div class="role-quick-desc">{{ q.desc }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import {
  Trophy, School, UserFilled, EditPen, Grid,
  Document, WarningFilled, SuccessFilled
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
  currentDateStr.value = now.getFullYear() + '年' + (now.getMonth() + 1) + '月' + now.getDate() + '日'
}

const stats = ref([
  { label: '班级总数', value: 0, icon: School },
  { label: '运动员总数', value: 0, icon: UserFilled },
  { label: '比赛项目', value: 0, icon: Trophy },
  { label: '报名总数', value: 0, icon: Document }
])

// ============ 分步工作流（顺序 + 录入） ============
// kind: entry=录入 / config=配置 / arrange=编排 / report=统计
// todoKey: 与 /statistics/todo 返回字段对应的待办标签（用于定位「建议当前步骤」）
const baseSteps = computed(() => {
  const steps = [
    {
      title: '配置运动会基础信息', kind: 'config', to: '/teacher/settings',
      desc: '运动会名称、举办日期、时段（上午/下午）、场地（名称+编码）、年级出场顺序。',
      tip: '先定场地与时段，后面的赛程编排才有可用资源。'
    },
    {
      title: '导入 / 创建班级', kind: 'entry', to: '/teacher/classes',
      desc: '录入班级与年级，并绑定班主任；班主任端才能看到本班数据。'
    },
    {
      title: '导入学生名单（运动员）', kind: 'entry', to: '/teacher/athletes',
      desc: '批量导入学生花名册，自动生成运动员并分配号码簿；支持列映射预览。'
    }
  ]
  if (isAdmin.value) {
    steps.push(
      {
        title: '导入班主任 / 体育老师账号', kind: 'entry', to: '/teacher/settings?tab=users',
        desc: '批量导入用户名单（角色选班主任 / 体育老师），或在「批量创建」一键生成。'
      },
      {
        title: '导入裁判名单并开通账号', kind: 'entry', to: '/teacher/referees',
        desc: '导入裁判花名册（专长支持 [a,b，c]），可单个/批量开通登录账号。',
        tip: '不打算用裁判编排？可在「设置 → 编排规则」里关闭裁判编排。'
      }
    )
  }
  steps.push(
    {
      title: '录入比赛项目', kind: 'entry', to: '/teacher/events',
      desc: '用「表格2」模板 Excel/CSV 录入，或导出/导入 JSON（全字段含时间长度、裁判数量、抽签等）。'
    },
    {
      title: '导入报名表并审核', kind: 'entry', to: '/teacher/registrations', todoKey: '待审核报名',
      desc: '导入或接收班主任端报名，批量审核通过 / 拒绝。',
      tip: '报名全部审核通过后再执行编排，否则漏人。'
    },
    {
      title: '赛程编排（天 × 时段 × 场地）', kind: 'arrange', to: '/teacher/schedule',
      desc: '把项目排进时间表，设置并数、自定义项目顺序、田赛分组/并行捆绑组。'
    },
    {
      title: '道次编排（分组分道）', kind: 'arrange', to: '/teacher/arrange', todoKey: '未编排项目',
      desc: '自动分组分道；内置对抗式自检、可按项目抽签、支持预留模拟空位与时间。',
      tip: '预赛成绩录入后可一键「重排全部决赛」（两阶段编排）。'
    },
    {
      title: '录入成绩', kind: 'entry', to: '/teacher/scores', todoKey: '待录入成绩',
      desc: '按组次录入成绩，自动计算名次与积分（径赛可录成绩/田赛可录试跳试掷）。'
    },
    {
      title: '合分排行与报表', kind: 'report', to: '/teacher/ranking',
      desc: '班级总分排行、秩序册（Excel/Word）、成绩册与数据大屏。'
    }
  )
  return steps.map((s, i) => ({ ...s, no: i + 1 }))
})

const workflowSteps = computed(() => baseSteps.value)

const kindLabel = (k) =>
  ({ entry: '录入', config: '配置', arrange: '编排', report: '统计' }[k] || k)
const actionLabel = (k) =>
  ({ entry: '去录入 →', config: '去配置 →', arrange: '去编排 →', report: '去查看 →' }[k] || '去处理 →')

const otherLinks = computed(() => {
  const links = [
    { ico: '🖥️', title: '数据大屏', desc: '现场投屏总览', path: '/screen?mode=overview' },
    { ico: '📈', title: '排行榜大屏', desc: '实时合分排行', path: '/screen?mode=ranking' },
    { ico: '🧑‍⚖️', title: '裁判工作台', desc: '裁判视角 · 执裁安排', path: '/referee/dashboard' },
    { ico: '📖', title: '说明书', desc: '完整操作手册', path: '/teacher/help' }
  ]
  if (isAdmin.value) {
    links.push({ ico: '⚙️', title: '系统设置', desc: '数据库 / 备份 / 用户', path: '/teacher/settings' })
  }
  return links
})

function go(path) {
  if (path) router.push(path)
}

const todos = ref([])
const registrationProgress = ref([])
const todaySchedule = ref([])

const todoCount = computed(() => todos.value.reduce((sum, t) => sum + (t.count || 0), 0))

function todoCountOf(label) {
  const t = todos.value.find(x => x.label === label)
  return t && t.count ? t.count : 0
}
/** 步骤的待办数（仅带 todoKey 的步骤） */
function todoOf(step) {
  return step.todoKey ? todoCountOf(step.todoKey) : 0
}
function todoTypeOf(step) {
  if (step.kind === 'arrange') return 'primary'
  if (step.kind === 'entry') return 'warning'
  return 'danger'
}
/** 建议当前步骤 = 第一个「有待办」的步骤 */
const currentStepNo = computed(() => {
  const hit = workflowSteps.value.find(s => todoOf(s) > 0)
  return hit ? hit.no : 0
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
          icon: WarningFilled, badgeType: 'warning',
          onClick: () => router.push('/teacher/registrations')
        },
        {
          label: '未编排项目', count: res.unarrangedEvents || 0,
          icon: Grid, badgeType: 'primary',
          onClick: () => router.push('/teacher/arrange')
        },
        {
          label: '待录入成绩', count: res.pendingScores || 0,
          icon: EditPen, badgeType: 'danger',
          onClick: () => router.push('/teacher/scores')
        },
        {
          label: '已完成事项', count: res.completed || 0,
          icon: SuccessFilled, badgeType: 'success',
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
        name, registered: data.registered || 0, total: data.total || 0
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
