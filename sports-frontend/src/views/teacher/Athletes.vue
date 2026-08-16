<template>
  <div class="athletes-container">
    <!-- 搜索/筛选区域 -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="年级">
          <el-select
            v-model="searchForm.grade"
            placeholder="请选择年级"
            clearable
            style="width: 140px"
            @change="handleGradeChange"
          >
            <el-option label="一年级" value="一年级" />
            <el-option label="二年级" value="二年级" />
            <el-option label="三年级" value="三年级" />
            <el-option label="四年级" value="四年级" />
            <el-option label="五年级" value="五年级" />
            <el-option label="六年级" value="六年级" />
            <el-option label="七年级" value="七年级" />
            <el-option label="八年级" value="八年级" />
            <el-option label="九年级" value="九年级" />
            <el-option label="高一" value="高一" />
            <el-option label="高二" value="高二" />
            <el-option label="高三" value="高三" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级">
          <el-select
            v-model="searchForm.classId"
            placeholder="请选择班级"
            clearable
            style="width: 160px"
          >
            <el-option
              v-for="c in filteredClassOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="性别">
          <el-select
            v-model="searchForm.gender"
            placeholder="请选择性别"
            clearable
            style="width: 100px"
          >
            <el-option label="男" value="男" />
            <el-option label="女" value="女" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="姓名/学号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
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

    <!-- 操作按钮栏 -->
    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            新增运动员
          </el-button>
        </div>
        <div class="toolbar-right">
          <el-button @click="handleImport">
            <el-icon><Upload /></el-icon>
            导入
          </el-button>
          <el-button @click="handleExport">
            <el-icon><Download /></el-icon>
            导出
          </el-button>
          <el-button @click="downloadTemplate" plain>
            <el-icon><DocumentCopy /></el-icon>
            下载示例
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 数据表格 -->
    <el-card class="table-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="tableData"
        border
        stripe
        style="width: 100%"
      >
        <el-table-column prop="name" label="姓名" min-width="100" align="center" />
        <el-table-column prop="gender" label="性别" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.gender === '男' ? 'primary' : 'danger'" size="small">
              {{ row.gender }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="grade" label="年级" width="100" align="center" />
        <el-table-column prop="className" label="班级" width="120" align="center" />
        <el-table-column prop="studentNo" label="学号" width="140" align="center" />
        <el-table-column prop="eventCount" label="报名项目数" width="110" align="center" />
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">
              <el-icon><Edit /></el-icon>
              编辑
            </el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">
              <el-icon><Delete /></el-icon>
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
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="90px"
      >
        <el-form-item label="姓名" prop="name">
          <el-input v-model="formData.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="性别" prop="gender">
          <el-select v-model="formData.gender" placeholder="请选择性别" style="width: 100%">
            <el-option label="男" value="男" />
            <el-option label="女" value="女" />
          </el-select>
        </el-form-item>
        <el-form-item label="年级" prop="grade">
          <el-select
            v-model="formData.grade"
            placeholder="请选择年级"
            style="width: 100%"
            @change="handleFormGradeChange"
          >
            <el-option label="一年级" value="一年级" />
            <el-option label="二年级" value="二年级" />
            <el-option label="三年级" value="三年级" />
            <el-option label="四年级" value="四年级" />
            <el-option label="五年级" value="五年级" />
            <el-option label="六年级" value="六年级" />
            <el-option label="七年级" value="七年级" />
            <el-option label="八年级" value="八年级" />
            <el-option label="九年级" value="九年级" />
            <el-option label="高一" value="高一" />
            <el-option label="高二" value="高二" />
            <el-option label="高三" value="高三" />
          </el-select>
        </el-form-item>
        <el-form-item label="班级" prop="classId">
          <el-select
            v-model="formData.classId"
            placeholder="请选择班级"
            style="width: 100%"
          >
            <el-option
              v-for="c in formClassOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="学号" prop="studentNo">
          <el-input v-model="formData.studentNo" placeholder="请输入学号" />
        </el-form-item>
        <el-form-item label="出生日期" prop="birthDate">
          <el-date-picker
            v-model="formData.birthDate"
            type="date"
            placeholder="请选择出生日期"
            style="width: 100%"
            value-format="YYYY-MM-DD"
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

    <!-- 隐藏的文件上传 input -->
    <input
      ref="fileInputRef"
      type="file"
      accept=".xlsx,.xls,.csv"
      style="display: none"
      @change="handleFileChange"
    />

    <!-- 导入预览对话框 -->
    <ImportPreview 
      v-model="showImportPreview" 
      :file="importFile" 
      type="athlete"
      @imported="onImported" 
    />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Edit, Delete, Upload, Download, DocumentCopy, List } from '@element-plus/icons-vue'
import request from '@/utils/request'
import ImportPreview from '@/components/ImportPreview.vue'

// ==================== 响应式数据 ====================

const loading = ref(false)
const submitLoading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const formRef = ref(null)
const fileInputRef = ref(null)

// 班级选项（全部）
const classOptions = ref([])

// 搜索表单
const searchForm = reactive({
  grade: '',
  classId: '',
  gender: '',
  keyword: ''
})

// 表格数据
const tableData = ref([])

// 分页
const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

// 表单数据
const formData = reactive({
  name: '',
  gender: '',
  grade: '',
  classId: '',
  studentNo: '',
  birthDate: ''
})

// ==================== 计算属性 ====================

// 搜索区域的班级选项（按年级筛选）
const filteredClassOptions = computed(() => {
  if (!searchForm.grade) return classOptions.value
  return classOptions.value.filter((c) => c.grade === searchForm.grade)
})

// 表单中的班级选项（按年级筛选）
const formClassOptions = computed(() => {
  if (!formData.grade) return classOptions.value
  return classOptions.value.filter((c) => c.grade === formData.grade)
})

// 对话框标题
const dialogTitle = computed(() => (isEdit.value ? '编辑运动员' : '新增运动员'))

// ==================== 表单验证规则 ====================

const formRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  grade: [{ required: true, message: '请选择年级', trigger: 'change' }],
  classId: [{ required: true, message: '请选择班级', trigger: 'change' }],
  studentNo: [{ required: true, message: '请输入学号', trigger: 'blur' }]
}

// ==================== 生命周期 ====================

onMounted(() => {
  loadClassOptions()
  loadTableData()
})

// ==================== 数据加载 ====================

// 加载班级列表
async function loadClassOptions() {
  try {
    const data = await request.get('/classes')
    classOptions.value = Array.isArray(data) ? data : []
  } catch {
    // 请求失败时静默处理
  }
}

// 加载表格数据
async function loadTableData() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size
    }
    if (searchForm.grade) params.grade = searchForm.grade
    if (searchForm.classId) params.classId = searchForm.classId
    if (searchForm.gender) params.gender = searchForm.gender
    if (searchForm.keyword) params.keyword = searchForm.keyword

    const data = await request.get('/athletes', { params })
    // 响应拦截器已解包 data，可能是 { records, total } 或数组
    if (data && data.records) {
      tableData.value = data.records
      pagination.total = data.total || 0
    } else if (Array.isArray(data)) {
      tableData.value = data
      pagination.total = data.length
    } else {
      tableData.value = []
      pagination.total = 0
    }
  } catch {
    tableData.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

// ==================== 搜索相关 ====================

function handleSearch() {
  pagination.page = 1
  loadTableData()
}

function handleReset() {
  searchForm.grade = ''
  searchForm.classId = ''
  searchForm.gender = ''
  searchForm.keyword = ''
  pagination.page = 1
  loadTableData()
}

function handleGradeChange() {
  // 年级变化时清空班级选择
  searchForm.classId = ''
}

function handleFormGradeChange() {
  // 表单中年级变化时清空班级选择
  formData.classId = ''
}

// ==================== 分页 ====================

function handlePageChange(page) {
  pagination.page = page
  loadTableData()
}

function handleSizeChange(size) {
  pagination.size = size
  pagination.page = 1
  loadTableData()
}

// ==================== 新增/编辑 ====================

function handleAdd() {
  isEdit.value = false
  editId.value = null
  resetForm()
  dialogVisible.value = true
}

function handleEdit(row) {
  isEdit.value = true
  editId.value = row.id
  formData.name = row.name || ''
  formData.gender = row.gender || ''
  formData.grade = row.grade || ''
  formData.classId = row.classId || ''
  formData.studentNo = row.studentNo || ''

  formData.birthDate = row.birthDate || ''
  dialogVisible.value = true
}

function resetForm() {
  formData.name = ''
  formData.gender = ''
  formData.grade = ''
  formData.classId = ''
  formData.studentNo = ''
  formData.birthDate = ''
  if (formRef.value) {
    formRef.value.resetFields()
  }
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
    const payload = { ...formData }

    if (isEdit.value) {
      await request.put(`/athletes/${editId.value}`, payload)
      ElMessage.success('编辑成功')
    } else {
      await request.post('/athletes', payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadTableData()
  } catch {
    // 错误已由拦截器处理
  } finally {
    submitLoading.value = false
  }
}

// ==================== 删除 ====================

function handleDelete(row) {
  ElMessageBox.confirm(`确定要删除运动员「${row.name}」吗？此操作不可恢复。`, '删除确认', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(async () => {
      try {
        await request.delete(`/athletes/${row.id}`)
        ElMessage.success('删除成功')
        // 如果当前页只剩一条数据且不是第一页，回到上一页
        if (tableData.value.length === 1 && pagination.page > 1) {
          pagination.page--
        }
        loadTableData()
      } catch {
        // 错误已由拦截器处理
      }
    })
    .catch(() => {
      // 用户取消删除
    })
}

// ==================== 导入/导出 ====================

const showImportPreview = ref(false)
const importFile = ref(null)

function handleImport() {
  fileInputRef.value?.click()
}

function handleFileChange(event) {
  const file = event.target.files?.[0]
  if (!file) return
  importFile.value = file
  showImportPreview.value = true
  // 清空 input 以便重复选择同一文件
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

function onImported(result) {
  if (result?.success > 0) {
    loadTableData()
  }
}

function downloadTemplate() {
  window.open('/api/athletes/template', '_blank')
}

async function handleExport() {
  try {
    const blob = await request.get('/athletes/export', {
      responseType: 'blob'
    })
    // 创建下载链接
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `运动员数据_${Date.now()}.xlsx`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    // 错误已由拦截器处理
  }
}
</script>

<style scoped>
.athletes-container {
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
  justify-content: space-between;
  align-items: center;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  gap: 8px;
  align-items: center;
}

.table-card {
  min-height: 400px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
@media(max-width:768px) {
  .athletes-container { padding: 8px; }
  .toolbar { flex-direction: column; align-items: flex-start; gap: 8px; }
  .toolbar-left, .toolbar-right { flex-wrap: wrap; }
}
</style>