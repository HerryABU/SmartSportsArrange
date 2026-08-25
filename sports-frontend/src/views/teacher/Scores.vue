<template>
  <div class="scores-page" v-loading="loading">
    <!-- Event & Heat Selector -->
    <el-card class="selector-card" shadow="never">
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12" :md="6">
          <el-form-item label="选择项目" label-width="80px">
            <el-select v-model="selectedEventId" placeholder="请选择项目" filterable @change="onEventChange" style="width: 100%">
              <el-option v-for="e in eventList" :key="e.id" :label="`[${e.eventType || e.category || ''}] ${e.name}`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="4">
          <el-form-item label="选择组次" label-width="80px">
            <el-select v-model="selectedHeat" placeholder="请选择组次" @change="onHeatChange" style="width: 100%">
              <el-option v-for="h in heatOptions" :key="h" :label="`第 ${h} 组`" :value="h" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="24" :md="14">
          <div class="selector-actions">
            <el-button type="primary" :disabled="!selectedEventId || !selectedHeat" @click="fetchScores">
              <el-icon><Search /></el-icon>
              加载成绩表
            </el-button>
            <el-button type="success" :disabled="!scoreData.length" @click="handleBulkSave">
              <el-icon><Check /></el-icon>
              批量保存并排名
            </el-button>
            <el-upload
              :action="uploadUrl"
              :headers="uploadHeaders"
              :show-file-list="false"
              :on-success="onImportSuccess"
              accept=".xlsx,.xls"
              style="display: inline-block; margin-left: 8px"
            >
              <el-button :disabled="!selectedEventId">
                <el-icon><Upload /></el-icon>
                导入成绩
              </el-button>
            </el-upload>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- Score Entry Table -->
    <el-card class="table-card" shadow="never" v-if="scoreData.length">
      <template #header>
        <div class="card-header">
          <span>{{ currentEventName }} · 第 {{ selectedHeat }} 组</span>
          <span class="header-tip">💡 按 Tab 键快速跳转到下一个成绩输入框</span>
        </div>
      </template>
      <el-table :data="scoreData" border stripe>
        <el-table-column prop="laneNumber" label="道次" width="60" align="center" />
        <el-table-column prop="athleteNumber" label="号码" width="70" align="center">
          <template #default="{ row }">
            <span style="font-weight:600;color:#409EFF">{{ row.athleteNumber || row.number || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="athleteName" label="运动员" min-width="90" />
        <el-table-column prop="className" label="班级" min-width="100" />
        <el-table-column label="成绩" min-width="200">
          <template #default="{ row, $index }">
            <div class="score-cell">
              <el-input
                :ref="el => setScoreRef($index, el)"
                v-model="row.score"
                :placeholder="scorePlaceholder"
                size="small"
                @keydown.tab.prevent="focusNextScore($index)"
                :disabled="row._status !== 'normal'"
                style="flex:1"
              >
                <template #append v-if="unitLabel">{{ unitLabel }}</template>
              </el-input>
              <el-button-group size="small" class="status-btns" v-if="row._status === 'normal' || !row._status">
                <el-button @click="setStatus($index, 'DNS')" :type="row._status === 'DNS' ? 'warning' : ''"
                  title="DNS - 未出发">DNS</el-button>
                <el-button @click="setStatus($index, 'DNF')" :type="row._status === 'DNF' ? 'warning' : ''"
                  title="DNF - 未完赛">DNF</el-button>
                <el-button @click="setStatus($index, 'DSQ')" :type="row._status === 'DSQ' ? 'danger' : ''"
                  title="DSQ - 取消资格">DSQ</el-button>
                <el-button v-if="row._status && row._status !== 'normal'" @click="clearStatus($index)" type="info"
                  title="恢复正常">取消</el-button>
              </el-button-group>
              <el-tag v-else :type="statusTagType(row._status)" size="small" effect="dark" class="status-tag">
                {{ row._status }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="100">
          <template #default="{ row }">
            <el-input v-model="row.remark" placeholder="备注" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="排名" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.rank" :type="rankType(row.rank)" size="small">{{ row.rank }}</el-tag>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column label="积分" width="60" align="center">
          <template #default="{ row }">
            <span :style="{ fontWeight: row.points ? 'bold' : 'normal', color: row.points ? '#409EFF' : '#c0c4cc' }">
              {{ row.points || '-' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-empty v-else description="请选择项目和组次后加载成绩表" :image-size="100" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const eventList = ref([])
const selectedEventId = ref('')
const selectedHeat = ref(null)
const heatOptions = ref([])
const scoreData = ref([])
const scoreRefs = ref({})

const currentEvent = computed(() => eventList.value.find(e => e.id === selectedEventId.value))
const currentEventName = computed(() => currentEvent.value?.name || '')
const isTrackEvent = computed(() => {
  const e = currentEvent.value
  return e && (e.eventType === '径赛' || e.category === '径赛')
})

const unitLabel = computed(() => isTrackEvent.value ? '秒' : '米')
const scorePlaceholder = computed(() => isTrackEvent.value ? '如：12.34' : '如：5.20')

const uploadUrl = computed(() => apiBase() + '/results/import?eventId=' + selectedEventId.value + '&heat=' + (selectedHeat.value || ''))
const uploadHeaders = computed(() => ({
  Authorization: `Bearer ${localStorage.getItem('token')}`
}))

function rankType(rank) {
  if (rank === 1) return 'danger'
  if (rank === 2) return 'warning'
  if (rank === 3) return 'success'
  return 'info'
}

function statusTagType(status) {
  const map = { DNS: 'warning', DNF: 'info', DSQ: 'danger' }
  return map[status] || 'info'
}

function setScoreRef(index, el) {
  if (el) scoreRefs.value[index] = el
}

function focusNextScore(currentIndex) {
  const nextIndex = currentIndex + 1
  if (scoreRefs.value[nextIndex]) {
    nextTick(() => {
      scoreRefs.value[nextIndex].focus()
    })
  }
}

function setStatus(index, status) {
  scoreData.value[index]._status = status
  scoreData.value[index].score = ''
}

function clearStatus(index) {
  scoreData.value[index]._status = 'normal'
}

async function fetchEvents() {
  try {
    const res = await request.get('/events')
    eventList.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    console.error('获取项目列表失败', e)
  }
}

async function onEventChange(eventId) {
  selectedHeat.value = null
  scoreData.value = []
  if (!eventId) return
  try {
    const res = await request.get(`/arrange/events/${eventId}`)
    const heats = res.heats || res || []
    heatOptions.value = heats.map((_, i) => i + 1)
  } catch (e) {
    heatOptions.value = []
  }
}

function onHeatChange() {
  scoreData.value = []
}

async function fetchScores() {
  if (!selectedEventId.value || !selectedHeat.value) return
  loading.value = true
  try {
    const res = await request.get('/results', {
      params: { eventId: selectedEventId.value, heat: selectedHeat.value }
    })
    const items = res.records || res.list || res || []
    scoreData.value = items.map(item => ({
      ...item,
      score: item.score || item.rawTime || '',
      remark: item.remark || '',
      _status: item.dns ? 'DNS' : item.dnf ? 'DNF' : item.dsq ? 'DSQ' : 'normal'
    }))
  } catch (e) {
    console.error('获取成绩失败', e)
  } finally {
    loading.value = false
  }
}

async function handleBulkSave() {
  loading.value = true
  let savedCount = 0
  try {
    for (const row of scoreData.value) {
      if (row._status && row._status !== 'normal') {
        await request.post('/results', {
          eventId: selectedEventId.value,
          athleteId: row.athleteId,
          status: row._status,
          heat: selectedHeat.value,
          laneNumber: row.laneNumber,
          remark: row.remark
        })
        savedCount++
      } else if (row.score) {
        const payload = {
          eventId: selectedEventId.value,
          athleteId: row.athleteId,
          heat: selectedHeat.value,
          laneNumber: row.laneNumber,
          remark: row.remark,
          rawTime: row.score
        }
        await request.post('/results', payload)
        savedCount++
      }
    }

    if (savedCount > 0) {
      try {
        await request.post(`/results/events/${selectedEventId.value}/calculate-ranking`)
        await fetchScores()
        ElMessage.success(`已保存 ${savedCount} 条成绩并自动计算排名`)
      } catch {
        ElMessage.success(`已保存 ${savedCount} 条成绩`)
      }
    } else {
      ElMessage.warning('没有需要保存的成绩')
    }
  } catch (e) {
    console.error('保存失败', e)
    ElMessage.error('部分成绩保存失败')
  } finally {
    loading.value = false
  }
}

function onImportSuccess(response) {
  ElMessage.success('成绩导入成功')
  fetchScores()
}

onMounted(() => {
  fetchEvents()
})
</script>

<style scoped>
.scores-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-tip {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.selector-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.score-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-btns {
  flex-shrink: 0;
}

.status-btns .el-button {
  padding: 5px 8px;
  font-size: 11px;
}

.status-tag {
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .card-header { flex-direction: column; align-items: flex-start; gap: 4px; }
  .header-tip { display: none; }
  .score-cell { flex-wrap: wrap; }
  .score-cell .el-button-group { width: 100%; }
}
</style>
