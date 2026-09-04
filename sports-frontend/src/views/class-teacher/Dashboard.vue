<template>
  <div class="ct-dashboard" v-loading="loading">
    <!-- 欢迎横幅 -->
    <div class="hero rise-in">
      <div class="hero-left">
        <div class="hero-tag">📚 班主任工作台</div>
        <h2 class="hero-title">{{ stats.className || '尚未绑定班级' }}</h2>
        <p class="hero-sub">
          {{ stats.className
            ? `全班 ${stats.athleteCount} 名运动员 · 已报名 ${stats.registrationCount} 人次 · 通过 ${stats.approvedCount} 人次`
            : '请联系管理员在「班级管理」中为本账号绑定班级' }}
        </p>
      </div>
      <div class="hero-right">
        <div class="hero-num">{{ stats.athleteCount || 0 }}</div>
        <div class="hero-num-label">本班运动员</div>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="stat-grid">
      <div class="stat-card" v-for="s in statCards" :key="s.label" :style="{ '--c': s.color, '--bg': s.bg }">
        <div class="stat-icon"><el-icon :size="26"><component :is="s.icon" /></el-icon></div>
        <div class="stat-body">
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <!-- 快捷入口（班主任工作流） -->
    <div class="quick-grid">
      <div class="quick-card" v-for="q in quickLinks" :key="q.path" @click="$router.push(q.path)"
        :style="{ '--qg': q.grad }">
        <el-icon :size="30"><component :is="q.icon" /></el-icon>
        <span class="quick-title">{{ q.title }}</span>
        <span class="quick-desc">{{ q.desc }}</span>
      </div>
    </div>

    <!-- 今日安排 + 近期报名 -->
    <div class="lower-grid">
      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-head"><span>🗓️ 今日/已编排安排</span>
            <el-tag size="small" type="info" effect="plain">{{ schedules.length }} 条</el-tag>
          </div>
        </template>
        <div v-if="schedules.length" class="mini-list">
          <div v-for="(s, i) in schedules.slice(0, 10)" :key="i" class="mini-item">
            <span class="mini-name">{{ s.eventName }}</span>
            <span class="mini-ath">{{ s.athleteName }}</span>
            <span class="mini-meta">第{{ s.heat }}组 · {{ s.laneNumber }}道</span>
          </div>
        </div>
        <el-empty v-else description="暂无已编排赛程（报名通过并由体育老师编排后展示）" :image-size="70" />
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-head"><span>🕐 近期报名</span>
            <el-button link type="primary" size="small" @click="$router.push('/class-teacher/registration')">去报名 →</el-button>
          </div>
        </template>
        <el-table v-if="registrationList.length" :data="registrationList" size="small" border max-height="280">
          <el-table-column prop="athleteName" label="运动员" min-width="90" />
          <el-table-column prop="eventName" label="项目" min-width="120" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'approved' ? 'success' : row.status === 'withdrawn' ? 'info' : 'warning'"
                size="small">{{ row.status === 'approved' ? '已通过' : row.status === 'withdrawn' ? '已取消' : '待审核' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无报名记录" :image-size="70" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { UserFilled, DocumentAdd, Medal, Trophy, List, Calendar } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const stats = ref({ athleteCount: 0, registrationCount: 0, approvedCount: 0, awardCount: 0, className: '' })
const registrationList = ref([])
const schedules = ref([])

const statCards = ref([
  { label: '运动员数', value: 0, icon: UserFilled, color: '#2563eb', bg: '#eff6ff' },
  { label: '已报名(人次)', value: 0, icon: DocumentAdd, color: '#16a34a', bg: '#f0fdf4' },
  { label: '已通过', value: 0, icon: Medal, color: '#d97706', bg: '#fffbeb' },
  { label: '获奖(前三)', value: 0, icon: Trophy, color: '#dc2626', bg: '#fef2f2' }
])

const quickLinks = [
  { title: '班级名单', desc: '花名册导入 / 手动添加', path: '/class-teacher/athletes', icon: List, grad: 'linear-gradient(135deg,#3b82f6,#6366f1)' },
  { title: '运动会报名', desc: '逐个报名 / 批量导入', path: '/class-teacher/registration', icon: DocumentAdd, grad: 'linear-gradient(135deg,#10b981,#059669)' },
  { title: '赛程查看', desc: '本班道次 / 组次安排', path: '/class-teacher/schedule', icon: Calendar, grad: 'linear-gradient(135deg,#f59e0b,#d97706)' },
  { title: '成绩查看', desc: '积分 / 奖牌 / 破纪录', path: '/class-teacher/results', icon: Trophy, grad: 'linear-gradient(135deg,#ef4444,#db2777)' }
]

onMounted(async () => {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/dashboard')
    if (res?.stats) {
      stats.value = { ...stats.value, ...res.stats }
      statCards.value[0].value = res.stats.athleteCount || 0
      statCards.value[1].value = res.stats.registrationCount || 0
      statCards.value[2].value = res.stats.approvedCount || 0
      statCards.value[3].value = res.stats.awardCount || 0
    }
    registrationList.value = res?.registrations || []
    schedules.value = res?.schedules || []
  } catch (e) { console.error(e) }
  finally { loading.value = false }
})
</script>

<style scoped>
.ct-dashboard { display: flex; flex-direction: column; gap: 14px; }

.hero {
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
  padding: 22px 26px; border-radius: 18px; color: #fff;
  background:
    radial-gradient(600px 220px at 90% -20%, rgba(255,255,255,.18), transparent 60%),
    linear-gradient(120deg, #166534 0%, #15803d 45%, #16a34a 100%);
  box-shadow: 0 10px 26px rgba(22,101,52,.28);
}
.hero-tag { font-size: 12px; opacity: .85; letter-spacing: 1px; }
.hero-title { margin: 6px 0 2px; font-size: 24px; font-weight: 800; }
.hero-sub { margin: 0; font-size: 13px; opacity: .88; }
.hero-right { text-align: center; }
.hero-num { font-size: 44px; font-weight: 900; line-height: 1; }
.hero-num-label { font-size: 12px; opacity: .8; margin-top: 4px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card {
  display: flex; align-items: center; gap: 12px; padding: 16px;
  border-radius: 16px; background: var(--bg, #fff);
  border: 1px solid #e2e8f0; transition: all .25s;
  position: relative; overflow: hidden;
}
.stat-card::after {
  content: ''; position: absolute; right: -24px; top: -24px;
  width: 90px; height: 90px; border-radius: 50%; background: var(--c); opacity: .07;
}
.stat-card:hover { transform: translateY(-3px); box-shadow: 0 10px 22px rgba(15,23,42,.08); }
.stat-icon {
  width: 48px; height: 48px; border-radius: 14px; color: #fff; flex-shrink: 0;
  background: var(--c); display: inline-flex; align-items: center; justify-content: center;
}
.stat-value { font-size: 26px; font-weight: 800; line-height: 1.1; }
.stat-label { font-size: 12px; color: #64748b; margin-top: 2px; }

.quick-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.quick-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 8px;
  padding: 18px; border-radius: 16px; color: #fff; cursor: pointer;
  background: var(--qg); box-shadow: 0 6px 18px rgba(15,23,42,.12);
  transition: all .25s;
}
.quick-card:hover { transform: translateY(-4px) scale(1.01); box-shadow: 0 14px 28px rgba(15,23,42,.18); }
.quick-title { font-size: 16px; font-weight: 700; }
.quick-desc { font-size: 12px; opacity: .88; }

.lower-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.panel-card { border-radius: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 700; }
.mini-list { display: flex; flex-direction: column; max-height: 300px; overflow-y: auto; }
.mini-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 6px;
  border-bottom: 1px solid #f1f5f9; font-size: 13px;
}
.mini-item:last-child { border-bottom: none; }
.mini-name { font-weight: 700; min-width: 130px; }
.mini-ath { color: #334155; flex: 1; }
.mini-meta { color: #94a3b8; font-size: 12px; }

@media (max-width: 1000px) { .stat-grid, .quick-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 720px) { .lower-grid { grid-template-columns: 1fr; } .hero { flex-direction: column; text-align: center; } }
@media (max-width: 520px) { .stat-grid, .quick-grid { grid-template-columns: 1fr; } }
</style>
