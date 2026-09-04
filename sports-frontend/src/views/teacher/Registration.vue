<template>
  <div class="registration-page" v-loading="loading">
    <!-- 页面头（工作流 ① 导入） -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico"><el-icon :size="20"><Document /></el-icon></span>
        <div>
          <h3 class="pg-title">报名表导入 · 审核</h3>
          <p class="pg-desc">表格1（年级|班级|姓名|性别|学号|项目|是否团体赛数量|成绩）导入 —— 体育老师后置导入直接生效；班主任端另支持现场报名（导入为待审核）</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">① 导入报名</span>
        <el-button type="success" :icon="Upload" @click="openImportDialog">导入报名表</el-button>
      </div>
    </div>

    <!-- View Toggle -->
    <div class="view-toggle">
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button value="list">报名列表</el-radio-button>
        <el-radio-button value="cards">项目总览</el-radio-button>
      </el-radio-group>
    </div>

    <!-- Search & Filter -->
    <el-card class="filter-card" shadow="never">
      <el-form :model="filterForm" inline>
        <el-form-item label="班级">
          <el-select v-model="filterForm.classId" placeholder="全部班级" clearable filterable style="width: 160px">
            <el-option v-for="c in classList" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目" v-if="viewMode === 'list'">
          <el-select v-model="filterForm.eventId" placeholder="全部项目" clearable filterable style="width: 160px">
            <el-option v-for="e in eventList" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" v-if="viewMode === 'list'">
          <el-select v-model="filterForm.status" placeholder="全部状态" clearable style="width: 120px">
            <el-option label="待审核" value="pending" />
            <el-option label="已通过" value="approved" />
            <el-option label="已拒绝" value="rejected" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input v-model="filterForm.keyword" placeholder="搜索运动员姓名" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button type="success" @click="openImportDialog">
            <el-icon><Upload /></el-icon>
            导入报名表
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Project Cards View -->
    <div v-if="viewMode === 'cards'" class="project-cards-grid">
      <el-row :gutter="14">
        <el-col :xs="12" :sm="8" :md="6" :lg="4" v-for="evt in projectCards" :key="evt.id" style="margin-bottom: 14px">
          <div :class="['project-card', { 'is-full': evt.registered >= (evt.maxParticipants || 10) }]">
            <div class="pc-header">
              <el-tag size="small" :type="evt.eventType === '径赛' ? 'danger' : 'warning'" effect="light">
                {{ evt.eventType || '未分类' }}
              </el-tag>
              <el-tag size="small" effect="plain">{{ evt.genderLimit || '混合' }}</el-tag>
            </div>
            <div class="pc-name">{{ evt.name }}</div>
            <div class="pc-grade">{{ evt.gradeGroup || evt.category || '' }}</div>
            <el-progress
              :percentage="Math.min(100, Math.round(evt.registered / (evt.maxParticipants || 10) * 100))"
              :stroke-width="10"
              :text-inside="true"
              :status="evt.registered >= (evt.maxParticipants || 10) ? 'success' : undefined"
            />
            <div class="pc-footer">
              <span class="pc-count">{{ evt.registered || 0 }}/{{ evt.maxParticipants || 10 }}</span>
              <span class="pc-pending" v-if="evt.pendingCount">{{ evt.pendingCount }}待审</span>
            </div>
          </div>
        </el-col>
      </el-row>
      <el-empty v-if="!projectCards.length" description="暂无项目数据" :image-size="80" />
    </div>

    <!-- Table View -->
    <template v-if="viewMode === 'list'">
      <el-card class="table-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>报名列表</span>
            <div>
              <el-button type="success" @click="handleBatchApprove" :disabled="selectedIds.length === 0">
                批量通过
              </el-button>
              <el-button type="danger" @click="handleBatchReject" :disabled="selectedIds.length === 0">
                批量拒绝
              </el-button>
              <el-button @click="handleExport">导出</el-button>
              <el-button @click="downloadTemplate" plain>
                <el-icon><DocumentCopy /></el-icon>
                下载示例
              </el-button>
            </div>
          </div>
        </template>

        <el-table :data="tableData" stripe @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="45" />
          <el-table-column prop="athleteName" label="运动员" min-width="100" />
          <el-table-column prop="className" label="班级" min-width="120" />
          <el-table-column prop="grade" label="年级" width="80">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.grade }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="eventName" label="报名项目" min-width="140" />
          <el-table-column prop="eventType" label="项目类型" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.eventType === '径赛' ? 'danger' : 'warning'">
                {{ row.eventType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="报名时间" width="160">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'pending'" type="success" size="small" link @click="handleApprove(row)">
                通过
              </el-button>
              <el-button v-if="row.status === 'pending'" type="danger" size="small" link @click="handleReject(row)">
                拒绝
              </el-button>
              <el-button type="primary" size="small" link @click="handleView(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.size"
            :page-sizes="[10, 20, 50, 100]"
            :total="pagination.total"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleSearch"
            @current-change="handleSearch"
          />
        </div>
      </el-card>
    </template>

    <!-- View Detail Dialog -->
    <el-dialog v-model="detailVisible" title="报名详情" width="500px">
      <el-descriptions :column="2" border v-if="currentRow">
        <el-descriptions-item label="运动员">{{ currentRow.athleteName }}</el-descriptions-item>
        <el-descriptions-item label="班级">{{ currentRow.className }}</el-descriptions-item>
        <el-descriptions-item label="报名项目">{{ currentRow.eventName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(currentRow.status)">{{ statusLabel(currentRow.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="报名时间">{{ formatDate(currentRow.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">{{ formatDate(currentRow.updatedAt) }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ currentRow.remark || '无' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 导入报名表向导（三种模式：班主任现场 / 班主任后置 / 体育老师后置） -->
    <el-dialog v-model="importVisible" title="导入报名表" width="760px" :close-on-click-modal="false">
      <el-alert type="info" show-icon :closable="false"
        title="导入方式说明：① 后置导入＝把已经报名完成的报名表整表导入（直接置为已通过）；② 现场报名＝班主任现场登记，导入为待审核，需另行审核通过。" />
      <el-form label-width="110px" style="margin-top: 16px">
        <el-form-item label="导入模式">
          <el-radio-group v-model="importMode">
            <el-radio-button value="offline">后置导入（已报好的报名表，直接生效）</el-radio-button>
            <el-radio-button value="onsite" disabled title="现场报名请使用班主任账号登录后操作">
              现场报名（班主任端）
            </el-radio-button>
          </el-radio-group>
          <div class="import-tip">体育老师/管理员仅支持「后置导入」，可导入任意班级；班主任可在班主任端进行现场报名（待审核）或后置导入（限本人绑定班）。</div>
        </el-form-item>
        <el-form-item label="下载模板">
          <el-button link type="primary" @click="downloadSignupTemplate">报名表模板（年级/班级/姓名/性别/学号/项目/是否团体赛数量/成绩）</el-button>
        </el-form-item>
        <el-form-item label="选择文件">
          <input type="file" accept=".xlsx,.xls,.csv" @change="onFileChange" />
          <div class="import-tip">支持 Excel / CSV；「项目」列可填项目编码（如 100M）或精确项目名称。</div>
        </el-form-item>
      </el-form>

      <el-alert v-if="importResult" :type="importResult.failed > 0 ? 'warning' : 'success'"
        :title="`导入完成：成功 ${importResult.success} 条，跳过(重复) ${importResult.skipped} 条，失败 ${importResult.failed} 条${importResult.createdAthletes ? '，新建运动员 ' + importResult.createdAthletes + ' 名' : ''}`"
        show-icon :closable="false" style="margin-top: 8px" />
      <div v-if="importResult && importResult.errors && importResult.errors.length" class="import-errors">
        <div class="import-errors-title">失败明细（第 N 行从模板表头下一行起算）：</div>
        <div v-for="(e, i) in importResult.errors" :key="i" class="import-error-item">
          第 {{ e.row }} 行：{{ e.message }}
        </div>
      </div>

      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" :disabled="!selectedFile" @click="doImport">
          开始导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy, Upload, Document } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const viewMode = ref('list')
const tableData = ref([])
const selectedIds = ref([])
const classList = ref([])
const eventList = ref([])
const allRegistrations = ref([])
const detailVisible = ref(false)
const currentRow = ref(null)

const filterForm = reactive({
  grade: '',
  classId: '',
  eventId: '',
  status: '',
  keyword: ''
})

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

const projectCards = computed(() => {
  return eventList.value.map(evt => {
    const regs = allRegistrations.value.filter(r => r.eventId === evt.id)
    return {
      ...evt,
      registered: regs.filter(r => r.status === 'approved').length,
      pendingCount: regs.filter(r => r.status === 'pending').length,
      maxParticipants: evt.maxParticipants || 10
    }
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

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

async function fetchData() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size,
      ...filterForm
    }
    Object.keys(params).forEach(k => { if (!params[k]) delete params[k] })
    const res = await request.get('/registrations', { params })
    tableData.value = res.records || res.list || []
    pagination.total = res.total || 0
  } catch (e) {
    console.error('获取报名列表失败', e)
  } finally {
    loading.value = false
  }
}

async function fetchAllRegistrations() {
  try {
    const res = await request.get('/registrations', { params: { size: 9999 } })
    allRegistrations.value = res.records || res.list || []
  } catch (e) {
    console.error(e)
  }
}

async function fetchOptions() {
  try {
    const [classes, events] = await Promise.all([
      request.get('/classes'),
      request.get('/events')
    ])
    classList.value = Array.isArray(classes) ? classes : (classes.records || [])
    eventList.value = Array.isArray(events) ? events : (events.records || [])
  } catch (e) {
    console.error('获取选项失败', e)
  }
}

function handleSearch() {
  pagination.page = 1
  fetchData()
}

function handleReset() {
  Object.assign(filterForm, { grade: '', classId: '', eventId: '', status: '', keyword: '' })
  handleSearch()
}

function handleSelectionChange(selection) {
  selectedIds.value = selection.map(s => s.id)
}

async function handleApprove(row) {
  try {
    await request.put(`/registrations/${row.id}/approve`)
    ElMessage.success('已通过')
    fetchData()
    fetchAllRegistrations()
  } catch (e) { console.error(e) }
}

async function handleReject(row) {
  try {
    await request.put(`/registrations/${row.id}/reject`)
    ElMessage.success('已拒绝')
    fetchData()
    fetchAllRegistrations()
  } catch (e) { console.error(e) }
}

async function handleBatchApprove() {
  try {
    await ElMessageBox.confirm(`确定要通过选中的 ${selectedIds.value.length} 条报名吗？`, '批量通过', { type: 'warning' })
    await request.put('/registrations/batch-approve', { ids: selectedIds.value })
    ElMessage.success('批量通过成功')
    fetchData()
    fetchAllRegistrations()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

async function handleBatchReject() {
  try {
    await ElMessageBox.confirm(`确定要拒绝选中的 ${selectedIds.value.length} 条报名吗？`, '批量拒绝', { type: 'warning' })
    await request.put('/registrations/batch-reject', { ids: selectedIds.value })
    ElMessage.success('批量拒绝成功')
    fetchData()
    fetchAllRegistrations()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

function handleView(row) {
  currentRow.value = row
  detailVisible.value = true
}

function downloadTemplate() {
  // 与导入向导同源：表格1 报名表模板（年级|班级|姓名|性别|学号|项目|是否团体赛数量|成绩）
  window.open(apiBase() + '/registrations/template', '_blank')
}

function handleExport() {
  window.open(apiBase() + '/registrations/export', '_blank')
}

// ==================== 报名表导入向导（三种模式） ====================
const importVisible = ref(false)
const importMode = ref('offline')
const selectedFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

function openImportDialog() {
  importVisible.value = true
  importMode.value = 'offline'
  selectedFile.value = null
  importResult.value = null
}

function downloadSignupTemplate() {
  window.open(apiBase() + '/registrations/template', '_blank')
}

function onFileChange(e) {
  selectedFile.value = e.target.files?.[0] || null
  importResult.value = null
}

async function doImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', selectedFile.value)
    const res = await request.post('/registrations/import-sheet', fd, {
      params: { source: importMode.value },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    importResult.value = res || {}
    ElMessage.success(`导入完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    fetchData()
    fetchAllRegistrations()
  } catch {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  fetchOptions()
  fetchData()
  fetchAllRegistrations()
})
</script>

<style scoped>
.registration-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.view-toggle {
  display: flex;
  justify-content: flex-end;
}

.filter-card {
  margin-bottom: 0;
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

/* Project Cards */
.project-cards-grid {
  min-height: 200px;
}

.project-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  padding: 14px;
  transition: all 0.25s;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.project-card:hover {
  border-color: #409EFF;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.12);
  transform: translateY(-2px);
}

.project-card.is-full {
  border-color: #67C23A;
  background: #f0f9eb;
}

.pc-header {
  display: flex;
  gap: 4px;
}

.pc-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.pc-grade {
  font-size: 12px;
  color: #909399;
}

.pc-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
}

.pc-count {
  font-size: 13px;
  font-weight: 600;
  color: #409EFF;
}

.pc-pending {
  font-size: 11px;
  color: #E6A23C;
  background: #fdf6ec;
  padding: 1px 6px;
  border-radius: 8px;
}
@media (max-width: 768px) {
  .view-toggle { justify-content: center; }
  .card-header { flex-direction: column; align-items: flex-start; gap: 8px; }
}

.import-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
  width: 100%;
}

.import-errors {
  margin-top: 10px;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid #e6a23c;
  border-radius: 6px;
  padding: 8px 12px;
  background: #fdf6ec;
}

.import-errors-title {
  font-size: 12px;
  color: #e6a23c;
  font-weight: 600;
  margin-bottom: 4px;
}

.import-error-item {
  font-size: 12px;
  color: #7a6a3e;
  line-height: 1.6;
}
</style>
