<template>
  <div class="meet-manage">
    <div class="page-head">
      <div>
        <h2>届 / 运动会管理</h2>
        <p class="sub">管理「第 X 届 Y 季节运动会」，切换当前届；成绩 / 报名 / 入场式均归属当前届，支撑跨届进步榜。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建届</el-button>
    </div>

    <el-alert
      v-if="currentMeet"
      type="success"
      :closable="false"
      show-icon
      style="margin-bottom:14px"
      :title="'当前届：' + currentMeet.name + '（' + currentMeet.year + ' 年）'">
      <template #default>新录入的成绩、报名、入场式将归属该届；切换当前届后，可在「系统设置-届」中手动重算毕业生标记。</template>
    </el-alert>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column prop="edition" label="届" width="70" align="center" />
        <el-table-column prop="season" label="季节" width="90" align="center" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="year" label="年份" width="80" align="center" />
        <el-table-column prop="location" label="地点" min-width="120" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.active" type="success" effect="dark">当前届</el-tag>
            <el-tag v-else type="info">非当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.active" size="small" type="primary" text @click="activate(row)">设为当前届</el-button>
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" text @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="editing? '编辑届' : '新建届'" width="460px" :close-on-click-modal="false">
      <el-form :model="form" label-width="84px" :rules="rules" ref="formRef">
        <el-form-item label="届次" prop="edition">
          <el-input-number v-model="form.edition" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="季节" prop="season">
          <el-input v-model="form.season" placeholder="如 秋季 / 春季 / 冬季（自由文本）" maxlength="20" />
        </el-form-item>
        <el-form-item label="举办年份" prop="year">
          <el-input-number v-model="form.year" :min="2000" :max="2100" />
        </el-form-item>
        <el-form-item label="地点">
          <el-input v-model="form.location" placeholder="如 学校田径场" maxlength="60" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="可选" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" placeholder="可选" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="设为当前届">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const list = ref([])
const loading = ref(false)
const dialog = ref(false)
const editing = ref(false)
const saving = ref(false)
const currentMeet = ref(null)
const formRef = ref()
const editingId = ref(null)
const form = reactive({
  edition: 1, season: '秋季', year: new Date().getFullYear(), location: '',
  startDate: '', endDate: '', remark: '', active: false
})
const rules = {
  edition: [{ required: true, message: '请填写届次', trigger: 'blur' }],
  season: [{ required: true, message: '请填写季节', trigger: 'blur' }],
  year: [{ required: true, message: '请填写年份', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    list.value = await request.get('/meets')
    currentMeet.value = await request.get('/meets/current')
    appStore.meetList.value = list.value
    appStore.currentMeet.value = currentMeet.value
  } catch (e) {} finally { loading.value = false }
}

function resetForm() {
  Object.assign(form, {
    edition: (list.value.reduce((m, x) => Math.max(m, x.edition || 0), 0) + 1),
    season: '秋季', year: new Date().getFullYear(), location: '',
    startDate: '', endDate: '', remark: '', active: false
  })
}
function openCreate() { editing.value = false; editingId.value = null; resetForm(); dialog.value = true }
function openEdit(row) {
  editing.value = true; editingId.value = row.id
  Object.assign(form, {
    edition: row.edition, season: row.season, year: row.year, location: row.location || '',
    startDate: row.startDate || '', endDate: row.endDate || '', remark: row.remark || '', active: !!row.active
  })
  dialog.value = true
}
async function save() {
  await formRef.value.validate().catch(() => { throw new Error('请完善表单') })
  saving.value = true
  try {
    if (editing.value) await request.put('/meets/' + editingId.value, form)
    else await request.post('/meets', form)
    ElMessage.success('保存成功'); dialog.value = false; await load()
  } catch (e) {} finally { saving.value = false }
}
async function activate(row) {
  try { await request.post('/meets/' + row.id + '/activate'); ElMessage.success('已设为当前届'); await load() } catch (e) {}
}
function remove(row) {
  ElMessageBox.confirm('删除「' + row.name + '」？若其为当前届，系统将自动改选最新一届。', '提示', { type: 'warning' })
    .then(async () => { try { await request.delete('/meets/' + row.id); ElMessage.success('已删除'); await load() } catch (e) {} })
    .catch(() => {})
}
onMounted(load)
</script>

<style scoped>
.meet-manage { max-width: 1000px; margin: 0 auto; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 20px; color: var(--text-primary); }
.sub { margin: 0; font-size: 13px; color: var(--text-secondary); }
</style>
