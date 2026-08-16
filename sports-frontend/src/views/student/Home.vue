<template>
  <div class="student-home" v-loading="loading">
    <!-- Welcome Card -->
    <div class="welcome-card">
      <el-avatar :size="56" icon="UserFilled" />
      <div class="welcome-info">
        <div class="welcome-name">{{ authStore.user?.realName || authStore.user?.username || '同学' }}</div>
        <div class="welcome-class">{{ authStore.user?.className || '' }}</div>
      </div>
    </div>

    <!-- Stats -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="8" v-for="stat in stats" :key="stat.label">
        <div class="stat-card-small">
          <div class="stat-num">{{ stat.value }}</div>
          <div class="stat-txt">{{ stat.label }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- My Events -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header><span>我的报名</span></template>
      <div v-if="myRegistrations.length">
        <div v-for="reg in myRegistrations" :key="reg.id" class="reg-item">
          <div class="reg-left">
            <div class="reg-event">{{ reg.eventName }}</div>
            <div class="reg-meta">{{ reg.eventType }} · {{ reg.gender }}</div>
          </div>
          <el-tag :type="statusType(reg.status)" size="small">{{ statusLabel(reg.status) }}</el-tag>
        </div>
      </div>
      <el-empty v-else description="暂无报名" :image-size="60" />
    </el-card>

    <!-- Upcoming Schedule -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header><span>近期赛程</span></template>
      <div v-if="upcomingSchedule.length">
        <div v-for="s in upcomingSchedule" :key="s.id" class="schedule-item">
          <div class="sched-left">
            <div class="sched-event">{{ s.eventName }}</div>
            <div class="sched-detail">
              第 {{ s.heat }} 组 · 第 {{ s.laneNumber }} 道
            </div>
          </div>
          <div class="sched-right">
            <div class="sched-time">{{ s.time || '待定' }}</div>
            <div class="sched-location">{{ s.location || '待定' }}</div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无赛程" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const stats = ref([
  { label: '报名项目', value: 0 },
  { label: '已通过', value: 0 },
  { label: '获奖', value: 0 }
])
const myRegistrations = ref([])
const upcomingSchedule = ref([])

function statusType(status) {
  const map = { pending: 'warning', approved: 'success', rejected: 'danger' }
  return map[status] || 'info'
}

function statusLabel(status) {
  const map = { pending: '待审核', approved: '已通过', rejected: '已拒绝' }
  return map[status] || status
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/student/home')
    if (res) {
      if (res.stats) {
        stats.value = stats.value.map((s, i) => {
          const keys = ['registrationCount', 'approvedCount', 'awardCount']
          return { ...s, value: res.stats[keys[i]] || 0 }
        })
      }
      myRegistrations.value = res.registrations || []
      upcomingSchedule.value = res.schedules || []
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.student-home {
  display: flex;
  flex-direction: column;
}

.welcome-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  color: #fff;
}

.welcome-name {
  font-size: 18px;
  font-weight: 600;
}

.welcome-class {
  font-size: 13px;
  opacity: 0.85;
  margin-top: 2px;
}

.stat-card-small {
  text-align: center;
  padding: 14px 8px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.stat-num {
  font-size: 24px;
  font-weight: bold;
  color: #409EFF;
}

.stat-txt {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.reg-item, .schedule-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.reg-item:last-child, .schedule-item:last-child {
  border-bottom: none;
}

.reg-event, .sched-event {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.reg-meta, .sched-detail {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.sched-time {
  font-size: 13px;
  color: #409EFF;
  text-align: right;
}

.sched-location {
  font-size: 12px;
  color: #909399;
  text-align: right;
  margin-top: 2px;
}
</style>
