<template>
  <div class="venue-manage">
    <div class="page-head">
      <div>
        <h2 class="page-title">场地管理</h2>
        <p class="page-sub">维护运动场馆/区域及其「最大并行数」。编排引擎以各场地的 parallelMax 作为该场地同一时刻可并行进行的项目数上限（1=串行，n=并行）。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增场地</el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column prop="code" label="场地编码" width="140" />
        <el-table-column prop="name" label="场地名称" min-width="160" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.type)" effect="light">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capacity" label="可容纳项目数" width="120" align="center" />
        <el-table-column label="最大并行数" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="warning" effect="dark">{{ row.parallelMax }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" :before-change="() => toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && list.length === 0" description="暂无场地，请先新增" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑场地' : '新增场地'" width="460px" :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="96px">
        <el-form-item label="场地编码" prop="code">
          <el-input v-model="form.code" placeholder="如 TRACK / FIELD_A / SWIM" maxlength="32" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="场地名称" prop="name">
          <el-input v-model="form.name" placeholder="如 主跑道 / 跳远区A / 游泳馆" maxlength="128" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" style="width:100%">
            <el-option label="径赛场地 (track)" value="track" />
            <el-option label="田赛场地 (field)" value="field" />
            <el-option label="游泳场馆 (pool)" value="pool" />
            <el-option label="其它 (other)" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="可容纳项目数" prop="capacity">
          <el-input-number v-model="form.capacity" :min="1" :max="999" style="width:100%" />
        </el-form-item>
        <el-form-item label="最大并行数" prop="parallelMax">
          <el-input-number v-model="form.parallelMax" :min="1" :max="999" style="width:100%" />
          <div class="form-tip">同一时刻可同时进行的项目数（1=串行，n=并行）。项目绑定该场地编码后，其并发数受此上限约束。</div>
        </el-form-item>
        <el-form-item label="排序" prop="sortOrder">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref()

const emptyForm = () => ({
  id: null, code: '', name: '', type: 'track',
  capacity: 1, parallelMax: 1, sortOrder: 0, enabled: true
})
const form = reactive(emptyForm())

const rules = {
  code: [{ required: true, message: '请输入场地编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入场地名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }]
}

const TYPE_LABELS = { track: '径赛', field: '田赛', pool: '游泳', other: '其它' }
function typeLabel(t) { return TYPE_LABELS[t] || t || '其它' }
function typeTag(t) {
  return { track: 'danger', field: 'success', pool: 'info', other: 'warning' }[t] || 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await request.get('/venues')
    list.value = Array.isArray(data) ? data : []
  } catch (e) { /* 错误已由拦截器提示 */ }
  finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, emptyForm())
  isEdit.value = false
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}
function openEdit(row) {
  Object.assign(form, {
    id: row.id, code: row.code, name: row.name, type: row.type,
    capacity: row.capacity, parallelMax: row.parallelMax,
    sortOrder: row.sortOrder || 0, enabled: row.enabled !== false
  })
  isEdit.value = true
  dialogVisible.value = true
  formRef.value?.clearValidate?.()
}

async function submit() {
  await formRef.value.validate().catch(() => { throw new Error('validation') })
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(), name: form.name.trim(), type: form.type,
      capacity: form.capacity, parallelMax: form.parallelMax, sortOrder: form.sortOrder
    }
    if (isEdit.value) {
      await request.put('/venues/' + form.id, payload)
      ElMessage.success('更新成功')
    } else {
      await request.post('/venues', payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { saving.value = false }
}

async function toggleEnabled(row) {
  const next = !row.enabled
  try {
    await request.put('/venues/' + row.id, { enabled: next })
    row.enabled = next
    ElMessage.success(next ? '已启用' : '已停用')
    return true
  } catch (e) { return false }
}

function remove(row) {
  ElMessageBox.confirm(`确定删除场地「${row.name}（${row.code}）」？`, '提示', { type: 'warning' })
    .then(async () => {
      await request.delete('/venues/' + row.id)
      ElMessage.success('删除成功')
      await load()
    }).catch(() => {})
}

onMounted(load)
</script>

<style scoped>
.venue-manage { max-width: 1100px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-title { margin: 0 0 4px; font-size: 20px; font-weight: 700; }
.page-sub { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.6; max-width: 760px; }
.table-card { border-radius: 12px; }
.form-tip { font-size: 12px; color: var(--text-secondary); line-height: 1.5; margin-top: 4px; }
</style>
