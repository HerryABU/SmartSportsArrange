<template>
  <div class="scores-page" v-loading="loading">
    <!-- 页面头（工作流 ② 编排比赛） -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🏁</span>
        <div>
          <h3 class="pg-title">成绩录入</h3>
          <p class="pg-desc">选择项目与组次 → 录入成绩（径赛秒 / 田赛米，支持 DNS/DNF/DSQ）→ 批量保存并自动计算排名与积分</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#fffbeb;color:#d97706">② 编排比赛</span>
      </div>
    </div>
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

    <!-- 未编排提示 -->
    <el-alert v-if="selectedEventId && !eventHeats.length" type="warning" show-icon :closable="false"
      title="该项目暂无编排道次：请先到「📅 赛程编排」点“一键生成赛程”（会自动排道次），或到「道次编排」为该项目执行编排后回来录入成绩。" />

    <!-- Score Entry Table -->
    <el-card class="table-card" shadow="never" v-if="scoreData.length">
      <template #header>
        <div class="card-header">
          <span>{{ currentEventName }} · 第 {{ selectedHeat }} 组（{{ scoreData.length }} 人）</span>
          <span class="header-tip">💡 按 Tab 键快速跳转到下一个成绩输入框 · 点击成绩行尾 DNS/DNF/DSQ 标记弃权/未完赛/取消资格</span>
        </div>
      </template>
      <el-table :data="scoreData" border stripe>
        <el-table-column prop="laneNumber" label="道次" width="60" align="center" />
        <el-table-column label="号码" width="80" align="center">
          <template #default="{ row }">
            <span style="font-weight:600;color:#409EFF">{{ row.athleteNumber || row.number || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="athleteName" label="运动员" min-width="90" />
        <el-table-column prop="className" label="班级" min-width="110" />
        <el-table-column label="成绩" min-width="220">
          <template #default="{ row, $index }">
            <div class="score-cell">
              <el-input
                :ref="el => setScoreRef($index, el)"
                v-model="row.score"
                :placeholder="scorePlaceholder"
                size="small"
                @keydown.tab.prevent="focusNextScore($index)"
                :disabled="row._status !== 'normal'"
                style="flex:1; max-width:220px"
              >
                <template #append v-if="unitLabel">{{ unitLabel }}</template>
              </el-input>
              <el-button-group size="small" class="status-btns">
                <el-button :type="row._status === 'DNS' ? 'warning' : 'default'" @click="setStatus($index, 'DNS')"
                  title="DNS - 未出发">DNS</el-button>
                <el-button :type="row._status === 'DNF' ? 'warning' : 'default'" @click="setStatus($index, 'DNF')"
                  title="DNF - 未完赛">DNF</el-button>
                <el-button :type="row._status === 'DSQ' ? 'danger' : 'default'" @click="setStatus($index, 'DSQ')"
                  title="DSQ - 取消资格">DSQ</el-button>
                <el-button v-if="row._status && row._status !== 'normal'" @click="setStatus($index, 'normal')" type="info"
                  title="恢复正常">✓</el-button>
              </el-button-group>
              <el-tag v-if="row._status && row._status !== 'normal'" :type="statusTagType(row._status)" size="small" effect="dark" class="status-tag">
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

    <el-empty v-else-if="!selectedEventId" description="请选择项目和组次后加载成绩表" :image-size="100" />
    <el-empty v-else-if="!scoreData.length && eventHeats.length" description="当前组没有可录入的运动员（道次为空）" :image-size="80" />
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
const eventHeats = ref([])       // 当前项目编排 heats：[{ heat, lanes:[{lane,athleteId,...}] }]
const heatOptions = computed(() => eventHeats.value.map(h => h.heat))
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
    nextTick(() => { scoreRefs.value[nextIndex].focus() })
  }
}

/** DNS/DNF/DSQ 状态按钮：normal 代表正常录入 */
function setStatus(index, status) {
  const row = scoreData.value[index]
  if (!row) return
  if (status === 'normal') {
    row._status = 'normal'
    return
  }
  row._status = status
  row.score = ''
}

async function fetchEvents() {
  try {
    const res = await request.get('/events')
    eventList.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    console.error('获取项目列表失败', e)
  }
}

/** 项目切换：取编排 heats（决赛优先），自动选第一组并加载成绩表 */
async function onEventChange(eventId) {
  selectedHeat.value = null
  eventHeats.value = []
  scoreData.value = []
  if (!eventId) return
  try {
    const res = await request.get(`/arrange/events/${eventId}`)
    let heatsArr = []
    if (Array.isArray(res?.rounds) && res.rounds.length) {
      const fin = res.rounds.find(r => r.round === 'final') || res.rounds[res.rounds.length - 1]
      heatsArr = (fin && fin.heats) || []
    } else if (Array.isArray(res?.heats)) {
      heatsArr = res.heats
    } else if (Array.isArray(res)) {
      heatsArr = res
    }
    eventHeats.value = heatsArr.filter(h => h && (h.lanes || []).length)
    if (eventHeats.value.length) {
      selectedHeat.value = eventHeats.value[0].heat ?? eventHeats.value[0].heatNo ?? 1
      await fetchScores()
    } else {
      ElMessage.warning('该项目暂无编排道次，请先编排（赛程编排-一键生成 或 道次编排）')
    }
  } catch (e) {
    eventHeats.value = []
    ElMessage.warning('读取编排信息失败：' + (e?.message || '请先为该项目生成道次'))
  }
}

function onHeatChange() {
  fetchScores()
}

/**
 * 成绩表骨架 = 当前组次的编排道次名单；再用 /results 合并已保存的成绩/状态/名次。
 * （即使尚无任何已存成绩，也保证列出全部运动员行，可直接录入。）
 */
async function fetchScores() {
  if (!selectedEventId.value || !selectedHeat.value) return
  const heatObj = eventHeats.value.find(h => (h.heat ?? h.heatNo) === selectedHeat.value)
  if (!heatObj) { scoreData.value = []; return }
  loading.value = true
  try {
    const lanes = (heatObj.lanes || []).filter(l => l && l.athleteId)
    scoreData.value = lanes.map(l => ({
      athleteId: l.athleteId,
      laneNumber: l.lane,
      athleteNumber: l.number || '',
      athleteName: l.athleteName || '',
      className: l.className || '',
      score: '',
      rank: null,
      points: null,
      remark: '',
      _status: 'normal'
    }))
    // 合并已保存成绩（尽力而为：字段按 Result JSON 读取）
    try {
      const saved = await request.get('/results', {
        params: { eventId: selectedEventId.value, heat: selectedHeat.value }
      })
      const list = Array.isArray(saved) ? saved : (saved?.records || saved?.list || [])
      if (list.length) {
        const byAthlete = new Map()
        list.forEach(r => {
          const aid = r.athleteId ?? (r.athlete && r.athlete.id)
          if (aid != null) byAthlete.set(Number(aid), r)
        })
        scoreData.value.forEach(row => {
          const r = byAthlete.get(row.athleteId)
          if (!r) return
          const st = String(r.status || 'valid').toUpperCase()
          if (['DNS', 'DNF', 'DSQ'].includes(st)) {
            row._status = st
            row.score = ''
          } else {
            row._status = 'normal'
            row.score = r.rawTime || r.scoreText || row.score || ''
          }
          const rk = r.rank ?? r.totalRank
          if (rk != null) row.rank = rk
          const pts = r.points ?? r.score
          if (pts != null && (st === 'VALID' || st === 'NORMAL')) row.points = pts
          if (r.remark) row.remark = r.remark
        })
      }
    } catch (e) {
      console.warn('合并已存成绩失败（不影响录入）', e)
    }
  } catch (e) {
    console.error('加载成绩失败', e)
    scoreData.value = []
  } finally {
    loading.value = false
  }
}

async function handleBulkSave() {
  loading.value = true
  let savedCount = 0
  try {
    for (const row of scoreData.value) {
      const payload = {
        eventId: selectedEventId.value,
        athleteId: row.athleteId,
        heat: selectedHeat.value,
        laneNumber: row.laneNumber,
        remark: row.remark
      }
      if (row._status && row._status !== 'normal') {
        payload.status = row._status
      } else if (row.score) {
        payload.rawTime = row.score
      } else {
        continue
      }
      await request.post('/results', payload)
      savedCount++
    }

    if (savedCount > 0) {
      try {
        await request.post(`/results/events/${selectedEventId.value}/calculate-ranking`)
        ElMessage.success(`已保存 ${savedCount} 条成绩并自动计算排名`)
      } catch {
        ElMessage.success(`已保存 ${savedCount} 条成绩`)
      }
      await fetchScores()
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

function onImportSuccess() {
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
  flex-wrap: wrap;
  gap: 6px;
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
