<template>
  <div class="ct-results" v-loading="loading">
    <el-card shadow="never">
      <template #header><span>班级成绩</span></template>
      <el-table :data="tableData" border stripe>
        <el-table-column prop="athleteName" label="运动员" />
        <el-table-column prop="eventName" label="项目" />
        <el-table-column prop="score" label="成绩" width="100" />
        <el-table-column prop="rank" label="名次" width="70">
          <template #default="{ row }">
            <el-tag :type="rankType(row.rank)" size="small">
              {{ row.rank || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="points" label="积分" width="70">
          <template #default="{ row }">
            <span style="font-weight: bold; color: #409EFF">{{ row.points || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="100">
          <template #default="{ row }">
            <el-tag v-if="row.isRecord" type="danger" size="small">破纪录</el-tag>
            <span v-else>{{ row.remark || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="summary" v-if="summary">
        <el-divider />
        <el-row :gutter="16">
          <el-col :span="8">
            <el-statistic title="总积分" :value="summary.totalPoints || 0" />
          </el-col>
          <el-col :span="8">
            <el-statistic title="奖牌数" :value="summary.medalCount || 0" />
          </el-col>
          <el-col :span="8">
            <el-statistic title="年级排名" :value="summary.rank || '-'">
              <template #suffix>
                <span style="font-size: 14px">/ {{ summary.totalClasses || '-' }}</span>
              </template>
            </el-statistic>
          </el-col>
        </el-row>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const tableData = ref([])
const summary = ref(null)

function rankType(rank) {
  if (rank === 1) return 'danger'
  if (rank === 2) return 'warning'
  if (rank === 3) return 'success'
  return 'info'
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/results')
    tableData.value = res.records || res.results || []
    summary.value = res.summary || null
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
.ct-results {
  display: flex;
  flex-direction: column;
}

.summary {
  margin-top: 16px;
}
</style>
