<template>
  <div class="ct-dashboard" v-loading="loading">
    <!-- 角色横幅（班主任：绿色系，由 role-class-teacher 提供） -->
    <div class="role-hero">
      <h2 class="role-hero-title">🎓 {{ stats.className || '尚未绑定班级' }}</h2>
      <p class="role-hero-desc">
        {{ stats.className
          ? `全班 ${stats.athleteCount} 名运动员 · 已报名 ${stats.registrationCount} 人次 · 通过 ${stats.approvedCount} 人次`
          : '请联系管理员在「班级管理」中为本账号绑定班级' }}
      </p>
      <div class="role-hero-tags">
        <span class="role-hero-tag">班主任视角</span>
        <span class="role-hero-tag">本班运动员 {{ stats.athleteCount || 0 }}</span>
        <span class="role-hero-tag">获奖(前三) {{ stats.awardCount || 0 }}</span>
      </div>
    </div>

    <!-- 统计 -->
    <div class="role-stats">
      <div v-for="s in statCards" :key="s.label" class="role-stat">
        <div class="role-stat-num">{{ s.value }}</div>
        <div class="role-stat-label">{{ s.label }}</div>
      </div>
    </div>

    <!-- 快捷入口（班主任工作流） -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">班级工作流</span>
        <span class="role-card-extra">名单 → 报名 → 赛程 → 成绩</span>
      </div>
      <div class="role-quick">
        <div v-for="q in quickLinks" :key="q.path" class="role-quick-item" @click="$router.push(q.path)">
          <span class="role-quick-ico"><el-icon :size="20"><component :is="q.icon" /></el-icon></span>
          <div>
            <div class="role-quick-title">{{ q.title }}</div>
            <div class="role-quick-desc">{{ q.desc }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 今日安排 + 近期报名 -->
    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">🗓️ 本班已编排安排</span>
            <span class="role-card-extra">{{ schedules.length }} 条</span>
          </div>
          <ul v-if="schedules.length" class="role-list">
            <li v-for="(s, i) in schedules.slice(0, 10)" :key="i" class="role-list-item">
              <div class="role-list-main">
                <div class="role-list-title">{{ s.eventName }}</div>
                <div class="role-list-desc">第{{ s.heat }}组 · {{ s.laneNumber }}道 · {{ s.athleteName }}</div>
              </div>
            </li>
          </ul>
          <el-empty v-else description="暂无已编排赛程（报名通过并由体育老师编排后展示）" :image-size="70" />
        </div>
      </el-col>

      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">🕐 近期报名</span>
            <el-button link type="primary" size="small" @click="$router.push('/class-teacher/registration')">去报名 →</el-button>
          </div>
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
        </div>
      </el-col>
    </el-row>
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
  { title: '班级名单', desc: '花名册导入 / 手动添加', path: '/class-teacher/athletes', icon: List },
  { title: '运动会报名', desc: '逐个报名 / 批量导入', path: '/class-teacher/registration', icon: DocumentAdd },
  { title: '赛程查看', desc: '本班道次 / 组次安排', path: '/class-teacher/schedule', icon: Calendar },
  { title: '成绩查看', desc: '积分 / 奖牌 / 破纪录', path: '/class-teacher/results', icon: Trophy }
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
.ct-dashboard { display: flex; flex-direction: column; }
</style>
