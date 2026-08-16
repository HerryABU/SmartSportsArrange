<template>
  <div class="student-results" v-loading="loading">
    <el-card shadow="never">
      <template #header><span>我的成绩</span></template>
      <div v-if="resultData.length">
        <div v-for="r in resultData" :key="r.id" class="result-card">
          <div class="result-header">
            <span class="result-event">{{ r.eventName }}</span>
            <el-tag :type="rankType(r.rank)" size="small">
              {{ r.rank ? `第 ${r.rank} 名` : '未排名' }}
            </el-tag>
          </div>
          <div class="result-body">
            <div class="result-row">
              <span class="result-label">成绩</span>
              <span class="result-value">{{ r.score || '-' }}</span>
            </div>
            <div class="result-row">
              <span class="result-label">积分</span>
              <span class="result-score">{{ r.points || 0 }} 分</span>
            </div>
            <div class="result-row" v-if="r.isRecord">
              <el-tag type="danger" size="small">🏆 破纪录</el-tag>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无成绩记录" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const resultData = ref([])

function rankType(rank) {
  if (rank === 1) return 'danger'
  if (rank === 2) return 'warning'
  if (rank === 3) return 'success'
  return 'info'
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/student/results')
    resultData.value = Array.isArray(res) ? res : (res.records || [])
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
.student-results {
  display: flex;
  flex-direction: column;
}

.result-card {
  background: #fff;
  border: 1px solid #e6e6e6;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 12px;
}

.result-card:last-child {
  margin-bottom: 0;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.result-event {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.result-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.result-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #606266;
}

.result-label {
  width: 40px;
  color: #909399;
}

.result-value {
  font-weight: 600;
  color: #303133;
}

.result-score {
  font-weight: bold;
  color: #409EFF;
}
</style>
