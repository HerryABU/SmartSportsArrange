<template>
  <div class="ct-schedule" v-loading="loading">
    <el-card shadow="never">
      <template #header><span>赛程安排</span></template>
      <el-table :data="tableData" border stripe>
        <el-table-column prop="eventName" label="项目" />
        <el-table-column prop="eventType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.eventType === '径赛' ? 'danger' : 'warning'">
              {{ row.eventType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="gender" label="组别" width="90" />
        <el-table-column prop="heat" label="组次" width="70">
          <template #default="{ row }">第 {{ row.heat }} 组</template>
        </el-table-column>
        <el-table-column prop="athleteName" label="参赛运动员" />
        <el-table-column prop="laneNumber" label="道次" width="60" />
        <el-table-column prop="time" label="比赛时间" width="160">
          <template #default="{ row }">{{ row.time || '待定' }}</template>
        </el-table-column>
        <el-table-column prop="location" label="场地" width="120">
          <template #default="{ row }">{{ row.location || '待定' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const tableData = ref([])

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/schedule')
    tableData.value = Array.isArray(res) ? res : (res.records || [])
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
.ct-schedule {
  display: flex;
  flex-direction: column;
}
</style>
