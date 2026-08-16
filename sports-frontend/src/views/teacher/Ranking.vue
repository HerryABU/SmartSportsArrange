<template>
  <div class="ranking-page" v-loading="loading">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- Individual Rankings -->
      <el-tab-pane label="个人排名" name="individual">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>个人排名</span>
              <el-button type="success" size="small" :disabled="!individualData.length" @click="exportIndividual">
                <el-icon><Download /></el-icon> 导出
              </el-button>
            </div>
          </template>
          <div class="filter-bar">
            <el-select v-model="indFilter.grade" placeholder="年级" clearable style="width: 140px">
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
            <el-select v-model="indFilter.eventId" placeholder="项目" clearable filterable style="width: 180px">
              <el-option v-for="e in eventList" :key="e.id" :label="e.name" :value="e.id" />
            </el-select>
            <el-button type="primary" @click="handleIndSearch">查询</el-button>
          </div>

          <el-table :data="individualData" border stripe>
            <el-table-column type="index" label="排名" width="60" />
            <el-table-column prop="athleteNumber" label="号码" width="70" align="center">
              <template #default="{ row }">
                <span style="font-weight:600;color:#409EFF">{{ row.athleteNumber || row.number || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="athleteName" label="姓名" min-width="90" />
            <el-table-column prop="className" label="班级" min-width="110" />
            <el-table-column prop="eventName" label="项目" min-width="130" />
            <el-table-column prop="score" label="成绩" width="90" />
            <el-table-column prop="rank" label="名次" width="70" align="center">
              <template #default="{ row }">
                <el-tag :type="rankType(row.rank)" size="small">{{ row.rank }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="points" label="积分" width="60" align="center">
              <template #default="{ row }">
                <span :style="{ fontWeight: row.points ? 'bold' : 'normal' }">{{ row.points || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="80">
              <template #default="{ row }">
                <el-tag v-if="row.isRecord" type="danger" size="small" effect="dark">破纪录</el-tag>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="indPagination.page"
              v-model:page-size="indPagination.size"
              :total="indPagination.total"
              layout="total, prev, pager, next"
              @current-change="fetchIndividual"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- Team Rankings -->
      <el-tab-pane label="团体总分" name="team">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>团体总分</span>
              <el-button type="success" size="small" :disabled="!teamData.length" @click="exportTeam">
                <el-icon><Download /></el-icon> 导出
              </el-button>
            </div>
          </template>
          <div class="filter-bar">
            <el-select v-model="teamFilter.grade" placeholder="年级" clearable style="width: 140px">
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
            <el-button type="primary" @click="fetchTeam">查询</el-button>
          </div>

          <el-table :data="teamData" border stripe highlight-current-row
            :default-expand-all="false" row-key="className"
            @expand-change="onTeamExpand">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand-breakdown" v-loading="row._loadingBreakdown">
                  <el-table :data="row.eventBreakdown || []" size="small" border>
                    <el-table-column prop="eventName" label="项目" min-width="120" />
                    <el-table-column prop="rank" label="名次" width="60" align="center">
                      <template #default="{ row: br }">
                        <el-tag :type="rankType(br.rank)" size="small" v-if="br.rank">{{ br.rank }}</el-tag>
                        <span v-else>-</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="score" label="成绩" width="80" />
                    <el-table-column prop="points" label="积分" width="60" align="center" />
                  </el-table>
                  <el-empty v-if="!row.eventBreakdown?.length && !row._loadingBreakdown" description="暂无分项数据" :image-size="40" />
                </div>
              </template>
            </el-table-column>
            <el-table-column type="index" label="排名" width="60">
              <template #default="{ $index }">
                <el-tag v-if="$index < 3" :type="['danger', 'warning', 'success'][$index]" size="small" effect="dark">
                  {{ $index + 1 }}
                </el-tag>
                <span v-else>{{ $index + 1 }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="className" label="班级" min-width="140" />
            <el-table-column prop="grade" label="年级" width="100" />
            <el-table-column prop="goldCount" label="金牌" width="70" align="center">
              <template #default="{ row }">
                <span style="color: #F56C6C; font-weight: bold">{{ row.goldCount || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="silverCount" label="银牌" width="70" align="center">
              <template #default="{ row }">
                <span style="color: #909399; font-weight: bold">{{ row.silverCount || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="bronzeCount" label="铜牌" width="70" align="center">
              <template #default="{ row }">
                <span style="color: #E6A23C; font-weight: bold">{{ row.bronzeCount || 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="totalPoints" label="总分" width="80" align="center">
              <template #default="{ row }">
                <span style="font-size: 16px; font-weight: bold; color: #409EFF">{{ row.totalPoints || 0 }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- Records -->
      <el-tab-pane label="破纪录榜" name="records">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>破纪录榜</span>
              <el-button type="success" size="small" :disabled="!recordData.length" @click="exportRecords">
                <el-icon><Download /></el-icon> 导出
              </el-button>
            </div>
          </template>
          <div class="filter-bar">
            <el-select v-model="recFilter.grade" placeholder="年级" clearable style="width: 140px">
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
            <el-select v-model="recFilter.eventId" placeholder="项目" clearable filterable style="width: 180px">
              <el-option v-for="e in eventList" :key="e.id" :label="e.name" :value="e.id" />
            </el-select>
            <el-button type="primary" @click="fetchRecords">查询</el-button>
          </div>

          <el-table :data="recordData" border stripe>
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="athleteNumber" label="号码" width="70" align="center">
              <template #default="{ row }">
                <span style="font-weight:600;color:#409EFF">{{ row.athleteNumber || row.number || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="athleteName" label="运动员" min-width="90" />
            <el-table-column prop="className" label="班级" min-width="110" />
            <el-table-column prop="eventName" label="项目" min-width="130" />
            <el-table-column prop="score" label="新成绩" width="90">
              <template #default="{ row }">
                <span style="font-weight:600;color:#67C23A">{{ row.score }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="oldRecord" label="原纪录" width="90">
              <template #default="{ row }">
                <span style="color:#909399">{{ row.oldRecord || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="提升" width="90" align="center">
              <template #default="{ row }">
                <span v-if="row.score && row.oldRecord" :style="{ color: '#67C23A', fontWeight: 'bold' }">
                  {{ computeDelta(row.score, row.oldRecord) }}
                </span>
                <span v-else style="color:#c0c4cc">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="recordType" label="记录类型" width="90">
              <template #default="{ row }">
                <el-tag type="danger" size="small" effect="dark">{{ row.recordType || '校纪录' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const activeTab = ref('individual')
const eventList = ref([])
const individualData = ref([])
const teamData = ref([])
const recordData = ref([])

const gradeOptions = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三', '高一', '高二', '高三']

const indFilter = reactive({ grade: '', eventId: '' })
const indPagination = reactive({ page: 1, size: 10, total: 0 })
const teamFilter = reactive({ grade: '' })
const recFilter = reactive({ grade: '', eventId: '' })

function rankType(rank) {
  if (rank === 1) return 'danger'
  if (rank === 2) return 'warning'
  if (rank === 3) return 'success'
  return 'info'
}

function computeDelta(newScore, oldRecord) {
  const n = parseFloat(newScore)
  const o = parseFloat(oldRecord)
  if (isNaN(n) || isNaN(o)) return '-'
  const diff = Math.abs(n - o)
  // Track events: lower is better; Field events: higher is better
  if (n < o) return `↑${diff.toFixed(2)}`
  if (n > o) return `↑${diff.toFixed(2)}`
  return '-'
}

async function fetchEvents() {
  try {
    const res = await request.get('/events')
    eventList.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    console.error(e)
  }
}

async function fetchIndividual() {
  loading.value = true
  try {
    const params = { page: indPagination.page, size: indPagination.size }
    if (indFilter.grade) params.grade = indFilter.grade
    if (indFilter.eventId) params.eventId = indFilter.eventId
    const res = await request.get('/ranking/individual-score', { params })
    individualData.value = res.records || res.list || []
    indPagination.total = res.total || 0
  } catch (e) {
    console.error(e)
    ElMessage.error('获取个人排名失败')
  } finally {
    loading.value = false
  }
}

function handleIndSearch() {
  indPagination.page = 1
  fetchIndividual()
}

async function fetchTeam() {
  loading.value = true
  try {
    const params = {}
    if (teamFilter.grade) params.grade = teamFilter.grade
    const res = await request.get('/ranking/team-score', { params })
    teamData.value = (Array.isArray(res) ? res : (res.records || [])).map(row => ({ ...row, eventBreakdown: null, _loadingBreakdown: false }))
  } catch (e) {
    console.error(e)
    ElMessage.error('获取团体排名失败')
  } finally {
    loading.value = false
  }
}

async function onTeamExpand(row, expandedRows) {
  if (!expandedRows.includes(row) || row.eventBreakdown) return
  row._loadingBreakdown = true
  try {
    const res = await request.get('/ranking/team-score/breakdown', { params: { className: row.className, grade: row.grade } })
    row.eventBreakdown = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    row.eventBreakdown = []
  } finally {
    row._loadingBreakdown = false
  }
}

async function fetchRecords() {
  loading.value = true
  try {
    const params = {}
    if (recFilter.grade) params.grade = recFilter.grade
    if (recFilter.eventId) params.eventId = recFilter.eventId
    const res = await request.get('/ranking/records', { params })
    recordData.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    console.error(e)
    ElMessage.error('获取破纪录榜失败')
  } finally {
    loading.value = false
  }
}

function onTabChange(tab) {
  if (tab === 'individual') fetchIndividual()
  else if (tab === 'team') fetchTeam()
  else if (tab === 'records') fetchRecords()
}

function exportIndividual() {
  window.open('/api/ranking/individual-score/export', '_blank')
}
function exportTeam() {
  window.open('/api/ranking/team-score/export', '_blank')
}
function exportRecords() {
  window.open('/api/ranking/records/export', '_blank')
}

onMounted(() => {
  fetchEvents()
  fetchIndividual()
})
</script>

<style scoped>
.ranking-page {
  display: flex;
  flex-direction: column;
}
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.expand-breakdown {
  padding: 8px 24px;
  max-height: 300px;
  overflow-y: auto;
}
@media(max-width:768px) {
  .filter-bar { flex-direction: column; }
  .card-header { flex-direction: column; align-items: flex-start; gap: 8px; }
}
</style>
