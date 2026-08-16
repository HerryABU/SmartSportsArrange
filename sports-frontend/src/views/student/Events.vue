<template>
  <div class="student-events" v-loading="loading">
    <div class="event-filter">
      <el-input v-model="keyword" placeholder="搜索项目" clearable size="small" style="margin-bottom: 8px" />
      <el-select v-model="filterType" placeholder="项目类型" clearable size="small" style="width: 100%; margin-bottom: 8px">
        <el-option label="径赛" value="径赛" />
        <el-option label="田赛" value="田赛" />
      </el-select>
      <el-select v-model="filterGender" placeholder="性别组" clearable size="small" style="width: 100%">
        <el-option label="男子组" value="男子组" />
        <el-option label="女子组" value="女子组" />
      </el-select>
    </div>

    <div v-if="filteredEvents.length">
      <div v-for="event in filteredEvents" :key="event.id" class="event-card" @click="toggleDetail(event)">
        <div class="event-header">
          <span class="event-name">{{ event.name }}</span>
          <el-tag :type="event.eventType === '径赛' ? 'danger' : 'warning'" size="small">
            {{ event.eventType }}
          </el-tag>
        </div>
        <div class="event-meta">
          <span>{{ event.gender }}</span>
          <span>{{ event.gradeGroup }}</span>
          <span v-if="event.maxParticipants">限报 {{ event.maxParticipants }} 人</span>
        </div>
        <div v-if="event.description" class="event-desc">{{ event.description }}</div>
        <div v-if="expandedId === event.id" class="event-detail">
          <el-divider />
          <p><strong>已报名人数：</strong>{{ event.registrationCount || 0 }}{{ event.maxParticipants ? ` / ${event.maxParticipants}` : '' }}</p>
          <p v-if="event.myRegistration">
            <el-tag :type="statusType(event.myRegistration.status)" size="small">
              {{ statusLabel(event.myRegistration.status) }}
            </el-tag>
          </p>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无项目" :image-size="80" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const events = ref([])
const keyword = ref('')
const filterType = ref('')
const filterGender = ref('')
const expandedId = ref(null)

const filteredEvents = computed(() => {
  return events.value.filter(e => {
    if (keyword.value && !e.name.includes(keyword.value)) return false
    if (filterType.value && e.eventType !== filterType.value) return false
    if (filterGender.value && e.gender !== filterGender.value) return false
    return true
  })
})

function statusType(status) {
  const map = { pending: 'warning', approved: 'success', rejected: 'danger' }
  return map[status] || 'info'
}

function statusLabel(status) {
  const map = { pending: '待审核', approved: '已通过', rejected: '已拒绝' }
  return map[status] || status
}

function toggleDetail(event) {
  expandedId.value = expandedId.value === event.id ? null : event.id
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/student/events')
    events.value = Array.isArray(res) ? res : (res.records || [])
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
.student-events {
  display: flex;
  flex-direction: column;
}

.event-filter {
  margin-bottom: 12px;
}

.event-card {
  background: #fff;
  border: 1px solid #e6e6e6;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: all 0.2s;
}

.event-card:active {
  background: #f5f7fa;
}

.event-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.event-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.event-meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}

.event-desc {
  font-size: 13px;
  color: #606266;
  margin-top: 6px;
  line-height: 1.5;
}

.event-detail {
  font-size: 13px;
  color: #606266;
}

.event-detail p {
  margin-bottom: 4px;
}
</style>
