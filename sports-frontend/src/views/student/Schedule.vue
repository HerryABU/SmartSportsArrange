<template>
  <div class="student-schedule" v-loading="loading">
    <el-card shadow="never">
      <template #header><span>我的赛程</span></template>
      <div v-if="scheduleData.length">
        <div v-for="s in scheduleData" :key="s.id" class="schedule-card">
          <div class="sched-header">
            <span class="sched-event-name">{{ s.eventName }}</span>
            <el-tag :type="s.eventType === '径赛' ? 'danger' : 'warning'" size="small">
              {{ s.eventType }}
            </el-tag>
          </div>
          <div class="sched-body">
            <div class="sched-row">
              <span class="sched-label">组次</span>
              <span>第 {{ s.heat }} 组</span>
            </div>
            <div class="sched-row">
              <span class="sched-label">道次</span>
              <span>第 {{ s.laneNumber }} 道</span>
            </div>
            <div class="sched-row">
              <span class="sched-label">性别组</span>
              <span>{{ s.gender }}</span>
            </div>
            <div class="sched-row">
              <span class="sched-label">时间</span>
              <span>{{ s.time || '待定' }}</span>
            </div>
            <div class="sched-row">
              <span class="sched-label">场地</span>
              <span>{{ s.location || '待定' }}</span>
            </div>
            <div class="sched-row" v-if="s.note">
              <span class="sched-label">备注</span>
              <span>{{ s.note }}</span>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无赛程安排" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const scheduleData = ref([])

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/student/schedule')
    scheduleData.value = Array.isArray(res) ? res : (res.records || [])
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
.student-schedule {
  display: flex;
  flex-direction: column;
}

.schedule-card {
  background: #fff;
  border: 1px solid #e6e6e6;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
}

.schedule-card:last-child {
  margin-bottom: 0;
}

.sched-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.sched-event-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.sched-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sched-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}

.sched-label {
  width: 50px;
  color: #909399;
  flex-shrink: 0;
}
</style>
