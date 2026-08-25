<template>
  <div class="registration-page" v-loading="loading">
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
        <el-form-item label="年级">
          <el-select v-model="filterForm.grade" placeholder="全部年级" clearable style="width: 140px">
            <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
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
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
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

const gradeOptions = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三', '高一', '高二', '高三']

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
  window.open(apiBase() + '/excel/template/registration', '_blank')
}

function handleExport() {
  window.open(apiBase() + '/registrations/export', '_blank')
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
</style>
