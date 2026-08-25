<template>
  <div class="events-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2 class="page-title">项目管理</h2>
      <p class="page-desc">管理运动会比赛项目，支持预设模板快速创建</p>
    </div>

    <!-- 操作栏 -->
     <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="openTemplateDialog">
          <el-icon><DocumentCopy /></el-icon>
          预设模板
        </el-button>
        <el-button type="success" @click="openAddDialog">
          <el-icon><Plus /></el-icon>
          新增项目
        </el-button>
        <el-upload
          :action="importUrl"
          :headers="uploadHeaders"
          :show-file-list="false"
          accept=".xlsx,.xls,.csv"
          :on-success="onImportSuccess"
          :on-error="onImportError"
          style="display:inline-block;margin-left:8px"
        >
          <el-button type="warning"><el-icon><Upload /></el-icon> 导入Excel/CSV</el-button>
        </el-upload>
        <el-button plain @click="downloadTemplate" style="margin-left:8px">
          <el-icon><Download /></el-icon> 下载模板
        </el-button>
        <el-button plain @click="handleExport" style="margin-left:4px">
          <el-icon><Download /></el-icon> 导出
        </el-button>
      </div>
      <div class="toolbar-right">
        <el-select
          v-model="filters.grade"
          placeholder="年级筛选"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option label="一年级" value="一年级" />
          <el-option label="二年级" value="二年级" />
          <el-option label="三年级" value="三年级" />
          <el-option label="四年级" value="四年级" />
          <el-option label="五年级" value="五年级" />
          <el-option label="六年级" value="六年级" />
          <el-option label="初一年级" value="初一年级" />
          <el-option label="初二年级" value="初二年级" />
          <el-option label="初三年级" value="初三年级" />
          <el-option label="高一年级" value="高一年级" />
          <el-option label="高二年级" value="高二年级" />
          <el-option label="高三年级" value="高三年级" />
        </el-select>
        <el-select
          v-model="filters.gender"
          placeholder="性别筛选"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option label="男子组" value="男子组" />
          <el-option label="女子组" value="女子组" />
          <el-option label="混合组" value="混合组" />
        </el-select>
        <el-select
          v-model="filters.eventType"
          placeholder="项目类型"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option label="径赛" value="径赛" />
          <el-option label="田赛" value="田赛" />
        </el-select>
      </div>
    </div>

    <!-- 数据表格 -->
    <el-table
      v-loading="loading"
      :data="tableData"
      border
      stripe
      style="width: 100%"
      :header-cell-style="{ background: '#f5f7fa', color: '#303133' }"
    >
      <el-table-column prop="name" label="项目名称" min-width="150" />
      <el-table-column prop="eventType" label="项目类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.eventType === '径赛' ? 'danger' : 'warning'" effect="light">
            {{ row.eventType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gender" label="性别" width="100" align="center" />
      <el-table-column prop="gradeGroup" label="年级组" width="120" align="center" />
      <el-table-column prop="maxParticipants" label="最大报名人数" width="120" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="禁用"
            inline-prompt
            @change="(val: boolean) => handleToggleStatus(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEditDialog(row)">
            编辑
          </el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 预设模板对话框 -->
    <el-dialog
      v-model="templateDialogVisible"
      title="预设模板"
      width="700px"
      :close-on-click-modal="false"
    >
      <el-tabs v-model="templateCategory" type="border-card">
        <el-tab-pane label="跑步类" name="跑步类">
          <div class="template-list">
            <div
              v-for="tpl in runningTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="跳跃类" name="跳跃类">
          <div class="template-list">
            <div
              v-for="tpl in jumpingTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="投掷类" name="投掷类">
          <div class="template-list">
            <div
              v-for="tpl in throwingTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="接力类" name="接力类">
          <div class="template-list">
            <div
              v-for="tpl in relayTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="templateDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="isEdit ? '编辑项目' : '新增项目'"
      width="550px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="110px"
      >
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入项目名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="项目类型" prop="eventType">
          <el-select v-model="formData.eventType" placeholder="请选择项目类型" style="width: 100%">
            <el-option label="径赛" value="径赛" />
            <el-option label="田赛" value="田赛" />
          </el-select>
        </el-form-item>
        <el-form-item label="性别" prop="gender">
          <el-select v-model="formData.gender" placeholder="请选择性别组" style="width: 100%">
            <el-option label="男子组" value="男子组" />
            <el-option label="女子组" value="女子组" />
            <el-option label="混合组" value="混合组" />
          </el-select>
        </el-form-item>
        <el-form-item label="年级组" prop="gradeGroup">
          <el-select v-model="formData.gradeGroup" placeholder="请选择年级组" style="width: 100%">
            <el-option label="一年级" value="一年级" />
            <el-option label="二年级" value="二年级" />
            <el-option label="三年级" value="三年级" />
            <el-option label="四年级" value="四年级" />
            <el-option label="五年级" value="五年级" />
            <el-option label="六年级" value="六年级" />
            <el-option label="初一年级" value="初一年级" />
            <el-option label="初二年级" value="初二年级" />
            <el-option label="初三年级" value="初三年级" />
            <el-option label="高一年级" value="高一年级" />
            <el-option label="高二年级" value="高二年级" />
            <el-option label="高三年级" value="高三年级" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大报名人数" prop="maxParticipants">
          <el-input-number
            v-model="formData.maxParticipants"
            :min="1"
            :max="999"
            placeholder="请输入最大报名人数"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="排序号" prop="sortOrder">
          <el-input-number
            v-model="formData.sortOrder"
            :min="0"
            :max="9999"
            placeholder="数字越小越靠前"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入项目描述（选填）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy, Plus, CircleCheck, Upload, Download } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

// ==================== 类型定义 ====================
interface EventItem {
  id?: number
  name: string
  eventType: string
  gender: string
  gradeGroup: string
  maxParticipants: number
  description: string
  sortOrder: number
  enabled: boolean
}

interface TemplateItem {
  name: string
  eventType: string
  gender: string
}

// ==================== 预设模板数据 ====================
const runningTemplates: TemplateItem[] = [
  { name: '100米', eventType: '径赛', gender: '男子组' },
  { name: '100米', eventType: '径赛', gender: '女子组' },
  { name: '200米', eventType: '径赛', gender: '男子组' },
  { name: '200米', eventType: '径赛', gender: '女子组' },
  { name: '400米', eventType: '径赛', gender: '男子组' },
  { name: '400米', eventType: '径赛', gender: '女子组' },
  { name: '800米', eventType: '径赛', gender: '男子组' },
  { name: '800米', eventType: '径赛', gender: '女子组' },
  { name: '1500米', eventType: '径赛', gender: '男子组' },
  { name: '1500米', eventType: '径赛', gender: '女子组' },
]

const jumpingTemplates: TemplateItem[] = [
  { name: '跳高', eventType: '田赛', gender: '男子组' },
  { name: '跳高', eventType: '田赛', gender: '女子组' },
  { name: '跳远', eventType: '田赛', gender: '男子组' },
  { name: '跳远', eventType: '田赛', gender: '女子组' },
]

const throwingTemplates: TemplateItem[] = [
  { name: '铅球', eventType: '田赛', gender: '男子组' },
  { name: '铅球', eventType: '田赛', gender: '女子组' },
  { name: '实心球', eventType: '田赛', gender: '男子组' },
  { name: '实心球', eventType: '田赛', gender: '女子组' },
]

const relayTemplates: TemplateItem[] = [
  { name: '4×100米接力', eventType: '径赛', gender: '男子组' },
  { name: '4×100米接力', eventType: '径赛', gender: '女子组' },
  { name: '4×100米接力', eventType: '径赛', gender: '混合组' },
]

// ==================== 状态 ====================
const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<EventItem[]>([])
const formRef = ref<FormInstance>()

const filters = reactive({
  grade: '',
  gender: '',
  eventType: '',
})

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0,
})

const isEdit = ref(false)
const editingId = ref<number | null>(null)

const formDialogVisible = ref(false)
const templateDialogVisible = ref(false)
const templateCategory = ref('跑步类')

const formData = reactive<EventItem>({
  name: '',
  eventType: '',
  gender: '',
  gradeGroup: '',
  maxParticipants: 1,
  description: '',
  sortOrder: 0,
  enabled: true,
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  eventType: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  gender: [{ required: true, message: '请选择性别组', trigger: 'change' }],
  gradeGroup: [{ required: true, message: '请选择年级组', trigger: 'change' }],
  maxParticipants: [{ required: true, message: '请输入最大报名人数', trigger: 'blur' }],
}

// ==================== 导入/导出 ====================
const token = localStorage.getItem('token') || ''
const importUrl = apiBase() + '/events/import'
const uploadHeaders = computed(() => ({ Authorization: token ? `Bearer ${token}` : '' }))

function onImportSuccess(res: any) {
  ElMessage.success(`导入完成：成功 ${res?.success || res?.data?.success || 0} 条`)
  fetchData()
}
function onImportError() { ElMessage.error('导入失败，请检查文件格式') }

function downloadTemplate() {
  const csv = '项目名称,项目代码,类别(径赛/田赛),性别限制(M/F/mixed),道数,需要预赛(是/否),计分方式(global/grade),纪录(秒/米)\n100米,M100,径赛,M,8,是,global,\n跳远,TY_F,田赛,F,1,否,global,'
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = '项目导入模板.csv'; a.click()
  URL.revokeObjectURL(url)
}

function handleExport() {
  window.open(apiBase() + '/events/export', '_blank')
}

// ==================== 方法 ====================

// 加载数据
async function fetchData() {
  loading.value = true
  try {
    const params: Record<string, string | number> = {
      page: pagination.page,
      size: pagination.size,
    }
    if (filters.grade) params.grade = filters.grade
    if (filters.gender) params.gender = filters.gender
    if (filters.eventType) params.eventType = filters.eventType

    const res = await request.get('/events', { params })
    // 拦截器已解包 res.data，可能是 { records, total } 或直接是数组
    if (Array.isArray(res)) {
      tableData.value = res
      pagination.total = res.length
    } else if (res && res.records) {
      tableData.value = res.records
      pagination.total = res.total ?? 0
    } else if (res && res.total !== undefined) {
      tableData.value = res.records ?? []
      pagination.total = res.total
    } else {
      tableData.value = (res as any) ?? []
      pagination.total = tableData.value.length
    }
  } catch {
    // 错误由拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 筛选变化
function handleFilterChange() {
  pagination.page = 1
  fetchData()
}

// 分页
function handlePageChange(page: number) {
  pagination.page = page
  fetchData()
}

function handleSizeChange(size: number) {
  pagination.size = size
  pagination.page = 1
  fetchData()
}

// 打开预设模板对话框
function openTemplateDialog() {
  templateCategory.value = '跑步类'
  templateDialogVisible.value = true
}

// 选择模板
function selectTemplate(tpl: TemplateItem) {
  templateDialogVisible.value = false

  // 填充表单并打开编辑对话框
  resetFormData()
  formData.name = tpl.name
  formData.eventType = tpl.eventType
  formData.gender = tpl.gender
  formData.gradeGroup = ''
  formData.maxParticipants = 1
  formData.description = ''
  formData.sortOrder = 0
  formData.enabled = true

  isEdit.value = false
  editingId.value = null
  formDialogVisible.value = true
}

// 新增
function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  resetFormData()
  formDialogVisible.value = true
}

// 编辑
function openEditDialog(row: EventItem) {
  isEdit.value = true
  editingId.value = row.id ?? null
  formData.name = row.name
  formData.eventType = row.eventType
  formData.gender = row.gender
  formData.gradeGroup = row.gradeGroup
  formData.maxParticipants = row.maxParticipants
  formData.description = row.description ?? ''
  formData.sortOrder = row.sortOrder ?? 0
  formData.enabled = row.enabled
  formDialogVisible.value = true
}

// 重置表单数据
function resetFormData() {
  formData.name = ''
  formData.eventType = ''
  formData.gender = ''
  formData.gradeGroup = ''
  formData.maxParticipants = 1
  formData.description = ''
  formData.sortOrder = 0
  formData.enabled = true
}

// 关闭对话框时重置表单
function resetForm() {
  formRef.value?.resetFields()
}

// 提交表单
async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value && editingId.value !== null) {
      await request.put(`/events/${editingId.value}`, { ...formData })
      ElMessage.success('项目更新成功')
    } else {
      await request.post('/events', { ...formData })
      ElMessage.success('项目创建成功')
    }
    formDialogVisible.value = false
    fetchData()
  } catch {
    // 错误由拦截器统一处理
  } finally {
    submitLoading.value = false
  }
}

// 启用/禁用
async function handleToggleStatus(row: EventItem, val: boolean) {
  const action = val ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}项目"${row.name}"吗？`,
      `${action}确认`,
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    // 用户取消，回滚 switch
    fetchData()
    return
  }

  try {
    await request.put(`/events/${row.id}/status`, { enabled: val })
    ElMessage.success(`项目已${action}`)
    fetchData()
  } catch {
    fetchData()
  }
}

// 删除
async function handleDelete(row: EventItem) {
  try {
    await ElMessageBox.confirm(
      `确定要删除项目"${row.name}"吗？删除后不可恢复。`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }

  try {
    await request.delete(`/events/${row.id}`)
    ElMessage.success('项目已删除')
    // 如果当前页删空，回到上一页
    if (tableData.value.length === 1 && pagination.page > 1) {
      pagination.page--
    }
    fetchData()
  } catch {
    // 错误由拦截器统一处理
  }
}

// ==================== 生命周期 ====================
onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.events-container {
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  min-height: calc(100vh - 100px);
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 6px 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.page-desc {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 10px;
}

.toolbar-left {
  display: flex;
  gap: 10px;
}

.toolbar-right {
  display: flex;
  gap: 10px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* 模板列表 */
.template-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  padding: 8px 0;
}

.template-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.template-item:hover {
  border-color: #67c23a;
  background: #f0f9eb;
}

.template-item .template-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.template-item .template-info {
  flex: 1;
  font-size: 12px;
  color: #909399;
}
@media(max-width:768px) {
  .events-container { padding: 8px; }
  .toolbar { flex-direction: column; align-items: flex-start; }
  .toolbar-left, .toolbar-right { flex-wrap: wrap; }
}
</style>
