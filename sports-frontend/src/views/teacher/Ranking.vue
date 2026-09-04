<template>
  <div class="ranking-page" v-loading="loading">
    <!-- 页面头（工作流 ③ 统计） -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🏅</span>
        <div>
          <h3 class="pg-title">合分排行 · 统计</h3>
          <p class="pg-desc">每班 / 年级内班级 / 按项目 / 男女 / TOP 排名与计分；入场式得分可手动录入或 Excel 导入，支持「含入场式 / 去除入场式」双口径最终总分</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#fef2f2;color:#dc2626">③ 统计排名</span>
      </div>
    </div>
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

      <!-- 合分排行（含/去除入场式） -->
      <el-tab-pane label="合分排行" name="scoreboard">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>合分排行</span>
              <div>
                <el-button type="warning" size="small" @click="openParadeDialog">
                  <el-icon><Trophy /></el-icon> 入场式得分
                </el-button>
                <el-button type="primary" size="small" @click="fetchScoreboard">
                  <el-icon><Refresh /></el-icon> 刷新
                </el-button>
              </div>
            </div>
          </template>

          <div class="filter-bar">
            <el-select v-model="boardFilter.grade" placeholder="年级（全校/该年级内班级）" clearable style="width: 190px" filterable>
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
            <el-select v-model="boardFilter.gender" placeholder="性别（全部/男榜/女榜）" clearable style="width: 160px">
              <el-option label="男生榜" value="男" />
              <el-option label="女生榜" value="女" />
            </el-select>
            <el-switch v-model="boardFilter.includeParade" active-text="总分含入场式" inactive-text="去除入场式" />
            <span class="hint">TOP</span>
            <el-input-number v-model="boardFilter.topN" :min="0" :max="50" size="small" style="width: 110px"
              :step="5" :title="'只显示前 N 名；0 = 全部'" />
            <span class="hint">0=全部</span>
            <span class="hint">
              口径：{{ boardFilter.gender ? (boardFilter.gender === '男' ? '男生' : '女生') + '得分 · ' : '' }}{{ boardFilter.includeParade ? '赛事 + 入场式' : '纯赛事得分' }}
            </span>
            <el-button type="primary" @click="fetchScoreboard">查询</el-button>
          </div>

          <el-alert type="success" :closable="false" style="margin-bottom: 12px"
            :title="`班级维度 × 男女分列 × 计分排行${boardFilter.includeParade ? '（已计入入场式）' : '（未计入入场式）'}`" />

          <el-table :data="boardRows" border stripe>
            <el-table-column prop="rank" label="名次" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.rank <= 3" :type="['danger', 'warning', 'success'][row.rank - 1]" size="small" effect="dark">
                  {{ row.rank }}
                </el-tag>
                <span v-else>{{ row.rank }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="className" label="班级" min-width="120" />
            <el-table-column prop="grade" label="年级" width="90">
              <template #default="{ row }">
                <el-tag size="small" type="info" effect="plain">{{ row.grade || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="maleScore" label="男得分" width="90" align="center" />
            <el-table-column prop="femaleScore" label="女得分" width="90" align="center" />
            <el-table-column prop="goldCount" label="🏅" width="60" align="center">
              <template #default="{ row }"><b style="color:#F56C6C">{{ row.goldCount || 0 }}</b></template>
            </el-table-column>
            <el-table-column prop="silverCount" label="🥈" width="55" align="center" />
            <el-table-column prop="bronzeCount" label="🥉" width="55" align="center" />
            <el-table-column prop="totalScore" label="赛事总分" width="100" align="center">
              <template #default="{ row }"><b>{{ row.totalScore }}</b></template>
            </el-table-column>
            <el-table-column label="入场式" width="100" align="center">
              <template #default="{ row }">
                <span v-if="row.hasParade" style="color:#E6A23C;font-weight:600">{{ row.paradeScore }}</span>
                <span v-else style="color:#c0c4cc">未录入</span>
              </template>
            </el-table-column>
            <el-table-column label="最终合分" width="110" align="center">
              <template #default="{ row }">
                <span v-if="boardFilter.includeParade" style="color:#409EFF;font-weight:700">{{ row.totalWithParade }}</span>
                <span v-else style="color:#409EFF;font-weight:700">{{ row.totalScore }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!boardRows.length" description="暂无成绩数据" :image-size="60" />

          <div v-if="gradeSummary.length" class="grade-summary">
            <div class="grade-summary-title">年级汇总</div>
            <el-table :data="gradeSummary" size="small" border>
              <el-table-column prop="grade" label="年级" width="120" />
              <el-table-column prop="maleScore" label="男得分" align="center" />
              <el-table-column prop="femaleScore" label="女得分" align="center" />
              <el-table-column prop="totalScore" label="年级总分" align="center">
                <template #default="{ row }"><b>{{ row.totalScore }}</b></template>
              </el-table-column>
            </el-table>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 入场式得分录入/导入 -->
    <el-dialog v-model="paradeVisible" title="入场式得分（手动录入 / Excel 导入）" width="760px" :close-on-click-modal="false">
      <el-alert type="info" show-icon :closable="false"
        title="为每个班级录入入场式（开幕式方阵）得分；合分排行中可切换「含/去除入场式」两种口径。" style="margin-bottom:12px" />
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <el-button type="primary" plain @click="loadParadeClasses">载入全部班级</el-button>
        <input type="file" accept=".xlsx,.xls,.csv" style="display:none" ref="paradeFile"
          @change="onParadeFile" />
        <el-button @click="triggerParadeFile">导入 Excel（班级|得分 或 年级|班级|得分）</el-button>
      </div>
      <el-table :data="paradeRows" size="small" border max-height="380">
        <el-table-column prop="grade" label="年级" width="110" />
        <el-table-column prop="className" label="班级" min-width="140" />
        <el-table-column label="得分" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.score" :min="0" :max="1000" :step="0.1" size="small" style="width:120px" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="paradeVisible = false">关闭</el-button>
        <el-button type="primary" :loading="paradeSaving" @click="saveParade">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Trophy, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const activeTab = ref('individual')
const eventList = ref([])
const individualData = ref([])
const teamData = ref([])
const recordData = ref([])
const boardRows = ref([])
const gradeSummary = ref([])

// 年级下拉：动态来自 系统设置·年级管理（不硬编码）
const gradeOptions = ref([])

const indFilter = reactive({ grade: '', eventId: '' })
const indPagination = reactive({ page: 1, size: 10, total: 0 })
const teamFilter = reactive({ grade: '' })
const recFilter = reactive({ grade: '', eventId: '' })
const boardFilter = reactive({ grade: '', gender: '', includeParade: false, topN: 0 })

// 入场式得分
const paradeVisible = ref(false)
const paradeRows = ref([])
const paradeSaving = ref(false)
const paradeFile = ref(null)

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
  else if (tab === 'scoreboard') fetchScoreboard()
}

// ==================== 合分排行 ====================
async function fetchScoreboard() {
  loading.value = true
  try {
    const params = { includeParade: boardFilter.includeParade }
    if (boardFilter.grade) params.grade = boardFilter.grade
    if (boardFilter.gender) params.gender = boardFilter.gender
    if (boardFilter.topN > 0) params.topN = boardFilter.topN
    const res = await request.get('/ranking/scoreboard', { params })
    // 后端按 topN 返回 top 字段（0=全部返回 rows）
    boardRows.value = (res.top && res.top.length) ? res.top : (res.rows || [])
    gradeSummary.value = res.gradeSummary || []
  } catch (e) {
    boardRows.value = []
    gradeSummary.value = []
  } finally {
    loading.value = false
  }
}

// ==================== 入场式得分 ====================
function openParadeDialog() {
  paradeVisible.value = true
  paradeRows.value = []
  loadParadeClasses()
}

async function loadParadeClasses() {
  try {
    const res = await request.get('/classes')
    const list = Array.isArray(res) ? res : (res.records || res.list || [])
    // 合并已录入分数（若有）
    let existing = []
    try {
      const pr = await request.get('/parade-score', { params: { grade: boardFilter.grade || undefined } })
      existing = Array.isArray(pr) ? pr : (pr.records || pr.list || [])
    } catch (e) { existing = [] }
    const map = {}
    existing.forEach(p => { if (p.classId || p.classInfoId) map[p.classId || p.classInfoId] = p.score })
    paradeRows.value = list
      .filter(c => c.isParticipating !== false)
      .map(c => ({
        classId: c.id,
        className: c.name,
        grade: c.grade,
        score: map[c.id] != null ? map[c.id] : 0
      }))
  } catch (e) {
    ElMessage.error('载入班级失败')
  }
}

async function saveParade() {
  paradeSaving.value = true
  try {
    const items = paradeRows.value
      .filter(r => r.classId && r.score != null && Number(r.score) > 0)
      .map(r => ({ classId: r.classId, score: Number(r.score) }))
    await request.post('/parade-score', items)
    ElMessage.success('入场式得分已保存')
    paradeVisible.value = false
    fetchScoreboard()
  } catch (e) {
    // 拦截器已提示
  } finally {
    paradeSaving.value = false
  }
}

function triggerParadeFile() {
  paradeFile.value?.click()
}

async function onParadeFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const res = await request.post('/parade-score/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    ElMessage.success(`导入完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    e.target.value = ''
    loadParadeClasses()
    fetchScoreboard()
  } catch (err) {
    ElMessage.error('入场式导入失败')
  }
}

function exportIndividual() {
  window.open(apiBase() + '/ranking/individual-score/export', '_blank')
}
function exportTeam() {
  window.open(apiBase() + '/ranking/team-score/export', '_blank')
}
function exportRecords() {
  window.open(apiBase() + '/ranking/records/export', '_blank')
}

async function loadGrades() {
  try {
    const res = await request.get('/system/grades')
    const list = Array.isArray(res) ? res : (res?.records || [])
    gradeOptions.value = list.map(g => (g && g.name) || '').filter(Boolean)
  } catch {
    gradeOptions.value = []
  }
}

onMounted(() => {
  fetchEvents()
  fetchIndividual()
  loadGrades()
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

.hint { font-size: 12px; color: #909399; }

.grade-summary { margin-top: 16px; }
.grade-summary-title { font-size: 14px; font-weight: 600; margin-bottom: 8px; color: #303133; }

@media(max-width:768px) {
  .filter-bar { flex-direction: column; }
  .card-header { flex-direction: column; align-items: flex-start; gap: 8px; }
}
</style>
