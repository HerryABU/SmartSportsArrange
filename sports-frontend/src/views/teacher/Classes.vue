<template>
  <div class="classes-container">
    <!-- 页面头（工作流 ① 导入） -->
    <div class="pg-head rise-in" style="margin-bottom:14px">
      <div class="pg-titles">
        <span class="pg-ico">🏫</span>
        <div>
          <h3 class="pg-title">班级管理（名单）</h3>
          <p class="pg-desc">运动会参赛班级花名册 —— 维护年级/班级/班主任，支持 Excel 导入；班主任账号在此绑定到对应班级</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">① 导入报名</span>
      </div>
    </div>
    <!-- Search / Filter Bar -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="年级">
          <el-select
            v-model="searchForm.grade"
            placeholder="请选择年级"
            clearable
            style="width: 150px"
          >
            <el-option
              v-for="item in gradeOptions"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="班级名称 / 编号 / 班主任"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">
            搜索
          </el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Toolbar -->
    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-button type="primary" :icon="Plus" @click="handleAdd">
            新增班级
          </el-button>
          <el-upload
            :action="importUrl"
            :headers="uploadHeaders"
            :show-file-list="false"
            accept=".xlsx,.xls"
            :on-success="handleImportSuccess"
            :on-error="handleImportError"
            style="display: inline-block; margin-left: 10px"
          >
            <el-button type="success" :icon="Upload">导入Excel</el-button>
          </el-upload>
          <el-button type="warning" :icon="Download" @click="handleExport" style="margin-left: 10px">
            导出Excel
          </el-button>
          <el-button type="info" :icon="DocumentCopy" @click="downloadTemplate" style="margin-left: 4px" plain>
            下载示例
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Data Table -->
    <el-card class="table-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        border
        row-key="id"
        style="width: 100%"
        :header-cell-style="{ background: '#f5f7fa', color: '#303133', fontWeight: 'bold' }"
        @expand-change="onExpand"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-loading="expandLoading === row.id" style="padding:8px 24px">
              <h4 style="margin:0 0 8px">🏃 {{ row.name }} — 学生列表 ({{ getStudents(row).length }}人)</h4>
              <el-table :data="getStudents(row)" border size="small" v-if="getStudents(row).length">
                <el-table-column label="姓名" min-width="110">
                  <template #default="{ row: r }">
                    <span class="stu-name">{{ r.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="学号" width="130">
                  <template #default="{ row: r }">{{ r.studentNo || '—' }}</template>
                </el-table-column>
                <el-table-column label="性别" width="70" align="center">
                  <template #default="{ row: r }">
                    <el-tag size="small" :type="r.gender === 'M' ? 'primary' : r.gender === 'F' ? 'danger' : 'info'"
                      effect="light">{{ r.gender === 'M' ? '男' : r.gender === 'F' ? '女' : '—' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="grade" label="年级" width="90" align="center" />
                <el-table-column label="号码" width="110" align="center">
                  <template #default="{ row: r }">
                    <el-tag v-if="r.number" size="small" effect="plain">{{ r.number }}</el-tag>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="该班级暂无学生" :image-size="40" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="班级名称" min-width="120" align="center" />
        <el-table-column label="年级" width="100" align="center">
          <template #default="{ row }">
            <el-tag type="primary" effect="plain">{{ row.grade }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="班级编号" min-width="120" align="center" />
        <el-table-column prop="teacherName" label="班主任" width="110" align="center">
          <template #default="{ row }">
            {{ row.teacherName || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="studentCount" label="学生人数" width="100" align="center">
          <template #default="{ row }">
            <b style="color:#1e40af">{{ row.studentCount ?? 0 }}</b>
          </template>
        </el-table-column>
        <el-table-column label="是否参赛" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isParticipating === false ? 'info' : 'success'" effect="light">
              {{ row.isParticipating === false ? '未参赛' : '参赛' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :icon="Edit" size="small" @click="handleEdit(row)">
              编辑
            </el-button>
            <el-button type="danger" link :icon="Delete" size="small" @click="handleDeleteConfirm(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- Add / Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
      >
        <el-form-item label="班级名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入班级名称" clearable />
        </el-form-item>
        <el-form-item label="年级" prop="grade">
          <el-select
            v-model="formData.grade"
            placeholder="请选择年级"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="item in gradeOptions"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="班级编号" prop="code">
          <el-input v-model="formData.code" placeholder="请输入班级编号" clearable />
        </el-form-item>
        <el-form-item label="班主任" prop="teacherName">
          <el-input v-model="formData.teacherName" placeholder="请输入班主任姓名（选填）" clearable />
        </el-form-item>
        <el-form-item label="学生人数" prop="studentCount">
          <el-input-number
            v-model="formData.studentCount"
            :min="0"
            :max="200"
            placeholder="请输入学生人数"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Upload, Download, Edit, Delete, DocumentCopy } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

// ---------- grade options ----------
const gradeOptions = [
  '一年级', '二年级', '三年级', '四年级', '五年级', '六年级',
  '初一', '初二', '初三',
  '高一', '高二', '高三'
]

// ---------- search / filter ----------
const searchForm = reactive({
  grade: '',
  keyword: ''
})

// ---------- pagination ----------
const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

// ---------- table ----------
const loading = ref(false)
const tableData = ref([])
const expandLoading = ref(null)
const studentsCache = ref({})  // { classId: [students] }

function getStudents(row) { return studentsCache.value[row.id] || [] }

async function onExpand(row, expandedRows) {
  if (!expandedRows.includes(row)) return
  if (studentsCache.value[row.id]) return  // 已缓存
  expandLoading.value = row.id
  try {
    const res = await request.get('/athletes', { params: { classId: row.id, size: 200 } })
    studentsCache.value[row.id] = res.records || []
  } catch (e) { console.error(e); studentsCache.value[row.id] = [] }
  finally { expandLoading.value = null }
}

// ---------- dialog ----------
const dialogVisible = ref(false)
const dialogTitle = ref('新增班级')
const submitLoading = ref(false)
const editingId = ref(null)
const formRef = ref(null)

const formData = reactive({
  name: '',
  grade: '',
  code: '',
  teacherName: '',
  studentCount: 0
})

const formRules = {
  name: [
    { required: true, message: '请输入班级名称', trigger: 'blur' }
  ],
  grade: [
    { required: true, message: '请选择年级', trigger: 'change' }
  ],
  code: [
    { required: true, message: '请输入班级编号', trigger: 'blur' }
  ],
  studentCount: [
    { required: true, message: '请输入学生人数', trigger: 'blur' }
  ]
}

// ---------- upload ----------
const token = localStorage.getItem('token') || ''
const importUrl = apiBase() + '/classes/import'
const uploadHeaders = computed(() => ({
  Authorization: token ? `Bearer ${token}` : ''
}))

// ---------- lifecycle ----------
onMounted(() => {
  fetchData()
})

// ---------- data fetching ----------
async function fetchData() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size
    }
    if (searchForm.grade) params.grade = searchForm.grade
    if (searchForm.keyword) params.keyword = searchForm.keyword

    const res = await request.get('/classes', { params })
    // The response interceptor extracts data field when code is 200
    tableData.value = res.records || []
    pagination.total = res.total || 0
  } catch (error) {
    // 错误消息已在 request 拦截器中显示
    tableData.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

// ---------- search / reset ----------
function handleSearch() {
  pagination.page = 1
  fetchData()
}

function handleReset() {
  searchForm.grade = ''
  searchForm.keyword = ''
  pagination.page = 1
  fetchData()
}

// ---------- pagination ----------
function handlePageChange(page) {
  pagination.page = page
  fetchData()
}

function handleSizeChange(size) {
  pagination.size = size
  pagination.page = 1
  fetchData()
}

// ---------- add / edit ----------
function handleAdd() {
  editingId.value = null
  dialogTitle.value = '新增班级'
  resetForm()
  dialogVisible.value = true
}

function handleEdit(row) {
  editingId.value = row.id
  dialogTitle.value = '编辑班级'
  formData.name = row.name
  formData.grade = row.grade
  formData.code = row.code
  formData.teacherName = row.teacherName || ''
  formData.studentCount = row.studentCount
  dialogVisible.value = true
}

function resetForm() {
  formData.name = ''
  formData.grade = ''
  formData.code = ''
  formData.teacherName = ''
  formData.studentCount = 0
  formRef.value?.resetFields()
}

async function handleSubmit() {
  if (!formRef.value) return

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitLoading.value = true
  try {
    if (editingId.value) {
      await request.put(`/classes/${editingId.value}`, formData)
      ElMessage.success('班级信息更新成功')
    } else {
      await request.post('/classes', formData)
      ElMessage.success('班级创建成功')
    }
    dialogVisible.value = false
    fetchData()
  } catch (error) {
    // 错误消息已在 request 拦截器中显示，此处仅处理额外逻辑
    console.error('提交班级失败:', error)
  } finally {
    submitLoading.value = false
  }
}

// ---------- delete ----------
function handleDeleteConfirm(row) {
  ElMessageBox.confirm(
    `确定要删除班级「${row.name}」吗？删除后数据将无法恢复。`,
    '删除确认',
    {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    }
  )
    .then(() => {
      handleDelete(row)
    })
    .catch(() => {
      // user cancelled
    })
}

async function handleDelete(row) {
  try {
    await request.delete(`/classes/${row.id}`)
    ElMessage.success('删除成功')
    // If the current page becomes empty after deletion, go back one page
    if (tableData.value.length === 1 && pagination.page > 1) {
      pagination.page--
    }
    fetchData()
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

// ---------- import / export ----------
function handleImportSuccess(response, uploadFile) {
  ElMessage.success('导入成功')
  fetchData()
}

function handleImportError(error, uploadFile) {
  ElMessage.error('导入失败，请检查文件格式')
}

function downloadTemplate() {
  window.open(apiBase() + '/classes/template', '_blank')
}

async function handleExport() {
  try {
    // Use axios directly for blob download since the interceptor unwraps JSON
    const axios = (await import('axios')).default
    const token = localStorage.getItem('token')
    const response = await axios.get(apiBase() + '/classes/export', {
      responseType: 'blob',
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })

    // Create a download link from the blob
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `班级数据_${new Date().toISOString().slice(0, 10)}.xlsx`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (error) {
    ElMessage.error('导出失败')
  }
}
</script>

<style scoped>
.classes-container {
  padding: 16px;
}

.search-card {
  margin-bottom: 12px;
}

.toolbar-card {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
}

.table-card {
  margin-bottom: 12px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
@media(max-width:768px) {
  .classes-container { padding: 8px; }
  .toolbar { flex-direction: column; align-items: flex-start; gap: 8px; }
  .toolbar-left { flex-wrap: wrap; }
}
</style>
