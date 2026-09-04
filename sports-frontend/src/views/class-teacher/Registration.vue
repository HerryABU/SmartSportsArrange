<template>
  <div class="ct-registration" v-loading="loading">
    <!-- 页面头（班主任现场/后置报名） -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🎯</span>
        <div>
          <h3 class="pg-title">运动会报名</h3>
          <p class="pg-desc">① 现场/逐个报名提交后进入「待审核」，由体育老师通过后生效；② 批量导入可直接选「后置导入（已报好表）→ 直接通过」</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">仅限本班</span>
        <el-button type="success" size="small" @click="exportRegistrations">
          <el-icon><Download /></el-icon> 导出报名表
        </el-button>
      </div>
    </div>

    <!-- ===== 批量报名导入（班主任：现场 or 后置） ===== -->
    <el-card shadow="never" class="roster-card batch-card">
      <template #header>
        <div class="card-header">
          <span>🗂️ 批量报名导入（表格1：年级|班级|姓名|性别|学号|项目|是否团体赛数量|成绩）</span>
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <el-radio-group v-model="ctImportMode" size="small">
              <el-radio-button value="offline">后置导入（已报好表→直接通过）</el-radio-button>
              <el-radio-button value="onsite">现场导入（→待审核）</el-radio-button>
            </el-radio-group>
            <el-button size="small" plain @click="downloadSignupTemplate">
              <el-icon><DocumentCopy /></el-icon> 模板
            </el-button>
          </div>
        </div>
      </template>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px">
        <template #title>
          两种报名方式任选：① 用上方「现场报名」逐个登记（→ 待审核）；② 直接把整理好的「报名表 Excel/CSV」批量导入。
          班主任只允许导入自己绑定班级；项目列可填项目编码（如 100M）或精确项目名称；未建档的学生将自动建档并生成号码簿。
        </template>
      </el-alert>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <input ref="ctFileInput" type="file" accept=".xlsx,.xls,.csv" style="display:none"
          @change="onCtFile" />
        <el-button type="primary" @click="ctFileInput?.click()">
          <el-icon><Upload /></el-icon> 选择报名表
        </el-button>
        <span v-if="ctFile" class="batch-file">{{ ctFile.name }}</span>
        <el-button type="success" :loading="ctImporting" :disabled="!ctFile" @click="doCtImport">
          开始导入
        </el-button>
      </div>
      <el-alert v-if="ctResult" :type="ctResult.failed > 0 ? 'warning' : 'success'" show-icon
        style="margin-top:10px"
        :title="`导入完成：成功 ${ctResult.success} 条，跳过(重复) ${ctResult.skipped} 条，失败 ${ctResult.failed} 条${ctResult.createdAthletes ? '，新建运动员 ' + ctResult.createdAthletes + ' 名' : ''}`" />
      <div v-if="ctResult && ctResult.errors && ctResult.errors.length" class="batch-errors">
        <div class="batch-errors-title">失败明细（行号自表头下一行起）：</div>
        <div v-for="(e, i) in ctResult.errors" :key="i" class="batch-error-item">第 {{ e.row }} 行：{{ e.message }}</div>
      </div>
    </el-card>

    <!-- ===== 第①步：导入班级名单 ===== -->
    <el-card shadow="never" class="roster-card" v-if="!athletes.length">
      <template #header>
        <div class="card-header">
          <span>📋 ① 导入班级名单</span>
          <el-tag type="warning">请先导入全班花名册</el-tag>
        </div>
      </template>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px">
        <template #title>导入格式：Excel 第1列=学号, 第2列=姓名, 第3列=性别 (男/女/M/F)。导入后将自动创建学生账号。</template>
      </el-alert>
      <div style="display:flex;gap:8px">
        <el-upload :action="importRosterUrl" :headers="uploadHeaders" :show-file-list="false"
          accept=".xlsx,.xls" :on-success="onImportSuccess" :on-error="onImportError">
          <el-button type="primary" size="default" :loading="importing">
            <el-icon><Upload /></el-icon> 导入班级名单 (Excel)
          </el-button>
        </el-upload>
        <el-button size="default" plain @click="downloadRosterTemplate">
          <el-icon><DocumentCopy /></el-icon> 下载模板
        </el-button>
      </div>
    </el-card>

    <!-- ===== 名单已导入 — 统计摘要 ===== -->
    <el-card shadow="never" class="roster-card" v-else>
      <template #header>
        <div class="card-header">
          <span>📋 班级名单</span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-tag type="success">已导入 {{ athletes.length }} 名运动员</el-tag>
            <el-upload :action="importRosterUrl" :headers="uploadHeaders" :show-file-list="false"
              accept=".xlsx,.xls" :on-success="onImportSuccess" :on-error="onImportError" style="display:inline-block">
              <el-button size="small" plain><el-icon><Upload /></el-icon> 重新导入</el-button>
            </el-upload>
          </div>
        </div>
      </template>
      <!-- 统计条 -->
      <el-row :gutter="16" style="margin-bottom:4px">
        <el-col :xs="12" :sm="6"><div class="stat-item"><span class="stat-num">{{ athletes.length }}</span><span class="stat-label">全班人数</span></div></el-col>
        <el-col :xs="12" :sm="6"><div class="stat-item"><span class="stat-num">{{ regStats.regCount }}</span><span class="stat-label">已报名人次</span></div></el-col>
        <el-col :xs="12" :sm="6"><div class="stat-item"><span class="stat-num">{{ regStats.athleteCount }}</span><span class="stat-label">已报名人数</span></div></el-col>
        <el-col :xs="12" :sm="6"><div class="stat-item warn"><span class="stat-num">{{ athletes.length - regStats.athleteCount }}</span><span class="stat-label">未报名人数</span></div></el-col>
      </el-row>
    </el-card>

    <!-- ===== 第②步：运动员报名 ===== -->
    <el-card shadow="never" style="margin-top:12px" v-if="athletes.length">
      <template #header>
        <div class="card-header">
          <span>🎯 ② 运动员报名</span>
          <el-button size="small" type="success" @click="exportRegistrations">
            <el-icon><Download /></el-icon> 导出报名表
          </el-button>
        </div>
      </template>

      <!-- 学号搜索 -->
      <el-form inline style="margin-bottom:8px">
        <el-form-item label="输入学号">
          <el-input v-model="searchId" placeholder="如：2024001" clearable style="width:160px"
            @input="onSearchId" @clear="clearSearch">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <template v-if="foundAthlete">
            <el-tag type="success" size="large" effect="dark" style="font-size:14px;padding:6px 14px">
              {{ foundAthlete.name }}
              <span style="margin-left:6px;opacity:0.7">
                ({{ foundAthlete.gender==='M'?'男':'女' }} | {{ foundAthlete.grade }} | 已报{{ getRegCount(foundAthlete) }}项)
              </span>
            </el-tag>
            <!-- 该运动员已报项目 -->
            <span v-if="getAthleteRegs(foundAthlete).length" style="margin-left:8px;font-size:12px;color:#909399">
              已报: {{ getAthleteRegs(foundAthlete).map(r=>r.eventName).join('、') }}
            </span>
          </template>
          <el-tag v-else-if="searchId && searched" type="danger" effect="dark">
            未找到学号「{{ searchId }}」
          </el-tag>
          <span v-else style="color:#909399;font-size:13px">输入学号后自动显示姓名，然后点击下方项目完成报名</span>
        </el-form-item>
      </el-form>

      <el-divider style="margin:8px 0" />

      <!-- 可选项目卡片 -->
      <h4 style="margin:0 0 10px;color:#606266">可选项目（点击报名）</h4>
      <el-row :gutter="10">
        <el-col :xs="12" :sm="8" :md="4" v-for="evt in events" :key="evt.id" style="margin-bottom:10px">
          <div :class="['event-card', { registered: isRegistered(foundAthlete, evt), disabled: !canReg(foundAthlete, evt) }]"
            @click="tryRegister(evt)">
            <div class="event-name">{{ evt.name }}</div>
            <div class="event-meta">
              <el-tag size="small" :type="evtType(evt)==='径赛'?'danger':''">{{ evtType(evt) }}</el-tag>
              <el-tag size="small" effect="plain">{{ genderLabel(evt.gender || evt.genderLimit) }}</el-tag>
            </div>
            <div class="event-status">
              <span v-if="isRegistered(foundAthlete, evt)" class="reg-badge done">✓ 已报</span>
              <span v-else-if="!foundAthlete" class="reg-badge hint">输入学号</span>
              <span v-else-if="!canReg(foundAthlete, evt)" class="reg-badge limit">不可报</span>
              <span v-else class="reg-badge avail">点击报名</span>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- ===== 已报名列表 ===== -->
    <el-card shadow="never" style="margin-top:12px" v-if="registrations.length">
      <template #header>
        <div class="card-header">
          <span>📋 报名清单 ({{ registrations.length }}条)</span>
          <el-button size="small" type="success" @click="exportRegistrations">
            <el-icon><Download /></el-icon> 导出Excel
          </el-button>
        </div>
      </template>
      <el-table :data="registrations" border size="small" max-height="400">
        <el-table-column prop="athleteName" label="姓名" width="90" />
        <el-table-column label="学号" width="100">
          <template #default="{row}">{{ getAthleteSid(row.athleteName) }}</template>
        </el-table-column>
        <el-table-column prop="eventName" label="项目" />
        <el-table-column prop="eventType" label="类型" width="60" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{row}">
            <el-tag :type="row.status==='approved'?'success':row.status==='withdrawn'?'info':'warning'" size="small">
              {{ row.status==='approved'?'已通过':row.status==='withdrawn'?'已取消':'待审核' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60" fixed="right">
          <template #default="{row}">
            <el-button v-if="row.status!=='withdrawn'" type="danger" size="small" link @click="cancelReg(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 未报名名单 ===== -->
    <el-card shadow="never" style="margin-top:12px" v-if="unregisteredAthletes.length">
      <template #header><span>⚠️ 未报名运动员 ({{ unregisteredAthletes.length }}人)</span></template>
      <el-table :data="unregisteredAthletes" border size="small" max-height="250">
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="studentNo" label="学号" />
        <el-table-column label="性别" width="60">
          <template #default="{row}">{{ row.gender==='M'?'男':'女' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{row}">
            <el-button type="primary" size="small" link @click="quickSearch(row.studentNo)">去报名</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, DocumentCopy, Search, Download } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const importing = ref(false)
const athletes = ref([])
const events = ref([])
const registrations = ref([])
const searchId = ref('')
const searched = ref(false)

const foundAthlete = computed(() => {
  if (!searchId.value) return null
  return athletes.value.find(a => a.studentNo === searchId.value.trim()) || null
})

const regStats = computed(() => {
  const active = registrations.value.filter(r => r.status !== 'withdrawn')
  const names = new Set(active.map(r => r.athleteName))
  return { regCount: active.length, athleteCount: names.size }
})

const unregisteredAthletes = computed(() => {
  const regNames = new Set(registrations.value.filter(r => r.status !== 'withdrawn').map(r => r.athleteName))
  return athletes.value.filter(a => !regNames.has(a.name))
})

function quickSearch(sid) {
  searchId.value = sid
  searched.value = true
}

function getAthleteSid(name) {
  const a = athletes.value.find(a => a.name === name)
  return a ? (a.studentNo || a.studentId || '') : ''
}

function onSearchId() { searched.value = !!searchId.value }
function clearSearch() { searched.value = false }

/** 项目类型（兼容 eventType/category/isTrack 多种来源） */
function evtType(evt) {
  if (evt.eventType) return evt.eventType
  if (evt.isTrack === false) return '田赛'
  return evt.isTrack ? '径赛' : (evt.category || '径赛')
}
/** 性别显示（男子组/M/男 → 男子；女子组/F/女 → 女子；其余混合） */
function genderLabel(g) {
  const t = String(g || '').trim()
  if (['M', '男', '男子组', '男子'].includes(t)) return '男子'
  if (['F', '女', '女子组', '女子'].includes(t)) return '女子'
  return '混合'
}
function normGender(g) {
  const t = String(g || '').trim()
  if (['M', '男', '男子组', '男子'].includes(t)) return 'M'
  if (['F', '女', '女子组', '女子'].includes(t)) return 'F'
  return ''
}
function genderMatch(limit, athleteGender) {
  const l = normGender(limit)
  if (!l || l === 'X') return true // 混合/未限制
  const a = normGender(athleteGender)
  return !a || a === l
}

function getRegCount(athlete) {
  if (!athlete) return 0
  return registrations.value.filter(r => r.athleteName === athlete.name && r.status !== 'withdrawn').length
}

function getAthleteRegs(athlete) {
  if (!athlete) return []
  return registrations.value.filter(r => r.athleteName === athlete.name && r.status !== 'withdrawn')
}

function isRegistered(athlete, event) {
  if (!athlete) return false
  return registrations.value.some(r => r.athleteName === athlete.name && r.eventName === event.name && r.status !== 'withdrawn')
}

function canReg(athlete, event) {
  if (!athlete) return false
  if (isRegistered(athlete, event)) return false
  if (!genderMatch(event.gender || event.genderLimit, athlete.gender)) return false
  if (getRegCount(athlete) >= 3) return false
  return true
}

async function tryRegister(event) {
  if (!foundAthlete.value) { ElMessage.warning('请先输入学号定位运动员'); return }
  if (isRegistered(foundAthlete.value, event)) { ElMessage.info('已报名此项目'); return }
  if (!canReg(foundAthlete.value, event)) {
    if (!genderMatch(event.gender || event.genderLimit, foundAthlete.value.gender))
      ElMessage.warning('性别不符合项目要求')
    else if (getRegCount(foundAthlete.value) >= 3)
      ElMessage.warning('已报满3项')
    return
  }
  loading.value = true
  try {
    await request.post('/class-teacher/register', { athleteId: foundAthlete.value.id, eventId: event.id })
    ElMessage.success(`${foundAthlete.value.name} 报名 ${event.name} 已提交，等待体育老师审核`)
    await fetchRegistrations()
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

async function cancelReg(row) {
  try {
    await ElMessageBox.confirm(`确定取消「${row.athleteName}」的「${row.eventName}」？`, '提示', { type: 'warning' })
    await request.delete('/class-teacher/register/' + row.id)
    ElMessage.success('已取消')
    fetchRegistrations()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

// 导出报名表
async function exportRegistrations() {
  const token = localStorage.getItem('token') || ''
  try {
    const res = await fetch(apiBase() + '/class-teacher/registrations/export', {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (!res.ok) throw new Error('导出失败')
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = '报名表.xlsx'; a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) { ElMessage.error('导出失败') }
}

// ===== 导入 =====
const token = localStorage.getItem('token') || ''
const importRosterUrl = apiBase() + '/class-teacher/import-roster'
const uploadHeaders = computed(() => ({ Authorization: token ? `Bearer ${token}` : '' }))

function onImportSuccess(res) {
  importing.value = false
  const d = res.data || res
  ElMessage.success(`导入完成：新增运动员 ${d.createdAthletes||0} 人，跳过 ${d.skipped||0} 条`)
  fetchAthletes()
}

function onImportError() { importing.value = false; ElMessage.error('导入失败，请检查文件格式') }

function downloadRosterTemplate() {
  const csv = '学号,姓名,性别\n2024001,张三,男\n2024002,李四,女'
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = '班级名单模板.csv'; a.click()
  URL.revokeObjectURL(url)
}

// ===== 数据获取 =====
async function fetchAthletes() {
  try { const res = await request.get('/class-teacher/athletes', { params: { page: 1, size: 200 } }); athletes.value = res.records || [] }
  catch (e) { console.error(e) }
}
async function fetchEvents() {
  try { const res = await request.get('/class-teacher/events'); events.value = Array.isArray(res) ? res : (res.records || []) }
  catch (e) { console.error(e) }
}
async function fetchRegistrations() {
  try { const res = await request.get('/class-teacher/registrations'); registrations.value = res.records || [] }
  catch (e) { console.error(e) }
}
async function fetchAll() { loading.value = true; await Promise.all([fetchAthletes(), fetchEvents(), fetchRegistrations()]); loading.value = false }

// ===== 批量报名导入（现场 or 后置） =====
const ctFileInput = ref(null)
const ctImportMode = ref('offline')
const ctFile = ref(null)
const ctImporting = ref(false)
const ctResult = ref(null)

function downloadSignupTemplate() {
  window.open(apiBase() + '/registrations/template', '_blank')
}

function onCtFile(e) {
  ctFile.value = e.target.files?.[0] || null
  ctResult.value = null
}

async function doCtImport() {
  if (!ctFile.value) return
  ctImporting.value = true
  ctResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', ctFile.value)
    const res = await request.post('/registrations/import-sheet', fd, {
      params: { source: ctImportMode.value },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    ctResult.value = res || {}
    ElMessage.success(`导入完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    fetchAll()
  } catch {
    // 拦截器已提示
  } finally {
    ctImporting.value = false
  }
}

onMounted(() => fetchAll())
</script>

<style scoped>
.ct-registration { display:flex; flex-direction:column; }
.card-header { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; }
.roster-card { border-left:4px solid #409eff; }

.stat-item { text-align:center; padding:10px 0; background:#f5f7fa; border-radius:8px; }
.stat-num { display:block; font-size:26px; font-weight:700; color:#303133; }
.stat-label { font-size:12px; color:#909399; margin-top:2px; display:block; }
.stat-item.warn .stat-num { color:#e6a23c; }

.event-card { cursor:pointer; transition:all .2s; border:1px solid #e4e7ed; border-radius:10px; text-align:center;
  padding:14px 8px; min-height:90px; display:flex; flex-direction:column; justify-content:center; background:#fff; }
.event-card:hover { border-color:#409eff; box-shadow:0 4px 16px rgba(64,158,255,.15); transform:translateY(-2px); }
.event-card.registered { border-color:#67c23a; background:#f0f9eb; }
.event-card.disabled { opacity:.5; cursor:not-allowed; }
.event-card.disabled:hover { border-color:#e4e7ed; box-shadow:none; transform:none; }
.event-name { font-size:14px; font-weight:600; color:#303133; margin-bottom:6px; }
.event-meta { display:flex; justify-content:center; gap:4px; margin-bottom:4px; }
.event-status { font-size:11px; }
.reg-badge { padding:1px 6px; border-radius:8px; font-size:11px; }
.reg-badge.done { color:#67c23a; background:#e1f3d8; }
.reg-badge.avail { color:#409eff; background:#ecf5ff; }
.reg-badge.limit { color:#909399; background:#f4f4f5; }
.reg-badge.hint { color:#e6a23c; background:#fdf6ec; }

.batch-card { border-left-color:#67c23a; }
.batch-file { font-size:13px; color:#606266; }
.batch-errors {
  margin-top:10px; max-height:200px; overflow-y:auto;
  border:1px solid #e6a23c; border-radius:6px; padding:8px 12px; background:#fdf6ec;
}
.batch-errors-title { font-size:12px; color:#e6a23c; font-weight:600; margin-bottom:4px; }
.batch-error-item { font-size:12px; color:#7a6a3e; line-height:1.6; }

@media(max-width:768px) {
  .card-header { flex-direction: column; align-items: flex-start; }
  .stat-num { font-size: 20px; }
}
</style>
