<template>
  <div class="custom-projects">
    <div class="page-head">
      <div>
        <h2 class="pg-title">自定义项目区</h2>
        <p class="pg-desc">手动设立集体项目（如入场式、广播操比赛等），为每个项目按班级打分；可设置是否计入合分总分。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openProjectDialog()">新建项目</el-button>
    </div>

    <!-- 项目列表 -->
    <el-card class="block" shadow="never">
      <template #header>
        <span class="block-title">项目列表</span>
        <el-button text size="small" :icon="Refresh" @click="loadProjects" style="float:right">刷新</el-button>
      </template>
      <el-table :data="projects" size="small" border v-loading="projLoading" empty-text="暂无项目，点击右上角新建">
        <el-table-column prop="name" label="项目名称" min-width="160" />
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.type)">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计入总分" width="110" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.countInTotal" @change="(v) => toggleCountInTotal(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="操作" min-width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain :icon="EditPen" @click="openProjectDialog(row)">编辑</el-button>
            <el-button size="small" :icon="Trophy" @click="selectProject(row)">班级打分</el-button>
            <el-popconfirm title="确认删除该项目？其打分记录保留（仅标记失效）。" @confirm="deleteProject(row)">
              <template #reference>
                <el-button size="small" type="danger" plain :icon="Delete">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 班级打分 -->
    <el-card class="block" shadow="never" v-if="currentProject">
      <template #header>
        <span class="block-title">
          班级打分 ·
          <span style="color:#409EFF">{{ currentProject.name }}</span>
          <el-tag size="small" :type="typeTag(currentProject.type)" style="margin-left:6px">{{ typeLabel(currentProject.type) }}</el-tag>
          <el-tag size="small" :type="currentProject.countInTotal ? 'success' : 'info'" style="margin-left:6px">
            {{ currentProject.countInTotal ? '计入总分' : '不计入总分' }}
          </el-tag>
        </span>
        <el-button text size="small" :icon="Close" @click="currentProject = null" style="float:right">收起</el-button>
      </template>

      <el-alert type="info" show-icon :closable="false" style="margin-bottom:12px"
        :title="`为「${currentProject.name}」按班级录入得分；合分排行中「含入场式/计入项」口径将纳入此项目得分。`" />

      <div style="display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap">
        <el-button type="primary" plain :icon="School" @click="loadScoreClasses">载入全部班级</el-button>
        <input type="file" accept=".xlsx,.xls,.csv" style="display:none" ref="scoreFile" @change="onScoreFile" />
        <el-button :icon="Upload" @click="scoreFile?.click()">导入 Excel（班级|得分 或 年级|班级|得分）</el-button>
        <el-popconfirm title="确认清空本项目全部班级得分？" @confirm="clearScores">
          <template #reference>
            <el-button type="warning" plain :icon="Delete">清空本项目</el-button>
          </template>
        </el-popconfirm>
      </div>

      <el-table :data="scoreRows" size="small" border max-height="420" v-loading="scoreLoading">
        <el-table-column prop="grade" label="年级" width="120" />
        <el-table-column prop="className" label="班级" min-width="150" />
        <el-table-column label="得分" width="180">
          <template #default="{ row }">
            <el-input-number v-model="row.score" :min="0" :max="1000" :step="0.1" size="small" style="width:140px" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row, $index }">
            <el-button size="small" type="danger" text :icon="Delete" @click="removeScoreRow($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:14px;text-align:right">
        <el-button @click="currentProject = null">关闭</el-button>
        <el-button type="primary" :loading="scoreSaving" :icon="Check" @click="saveScores">保存得分</el-button>
      </div>
    </el-card>

    <el-empty v-else description="选择一个项目，点击「班级打分」开始录入" style="margin-top:24px" />

    <!-- 项目新建/编辑对话框 -->
    <el-dialog v-model="projDialogVisible" :title="editingProject ? '编辑项目' : '新建项目'" width="520px" :close-on-click-modal="false">
      <el-form :model="projForm" label-width="92px" size="small">
        <el-form-item label="项目名称" required>
          <el-input v-model="projForm.name" placeholder="如：入场式 / 广播操比赛 / 趣味接力" maxlength="60" />
        </el-form-item>
        <el-form-item label="项目编码">
          <el-input v-model="projForm.code" placeholder="留空则默认取名称（同届唯一）" maxlength="40" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="projForm.type" style="width:100%">
            <el-option label="入场式" value="PARADE" />
            <el-option label="广播操比赛" value="GYMNASTICS" />
            <el-option label="其他自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="计入总分">
          <el-switch v-model="projForm.countInTotal" active-text="计入合分总分" inactive-text="不计入" />
          <span style="margin-left:8px;color:#909399;font-size:12px">关闭后该项目得分仅作展示，不影响最终总分</span>
        </el-form-item>
        <el-form-item label="展示排序">
          <el-input-number v-model="projForm.sortOrder" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="projSaving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, EditPen, Trophy, Delete, Close, School, Upload, Check } from '@element-plus/icons-vue'
import request from '@/utils/request'

const TYPE_MAP = {
  PARADE: '入场式',
  GYMNASTICS: '广播操比赛',
  CUSTOM: '其他自定义'
}
function typeLabel(t) { return TYPE_MAP[t] || '自定义' }
function typeTag(t) { return t === 'PARADE' ? 'warning' : t === 'GYMNASTICS' ? 'success' : 'info' }

const projects = ref([])
const projLoading = ref(false)

const currentProject = ref(null)
const scoreRows = ref([])
const scoreLoading = ref(false)
const scoreSaving = ref(false)
const scoreFile = ref(null)

const projDialogVisible = ref(false)
const projSaving = ref(false)
const editingProject = ref(null)
const projForm = reactive({ id: null, name: '', code: '', type: 'PARADE', countInTotal: true, sortOrder: 0 })

async function loadProjects() {
  projLoading.value = true
  try {
    const res = await request.get('/custom-project')
    projects.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    projects.value = []
  } finally {
    projLoading.value = false
  }
}

function openProjectDialog(row) {
  editingProject.value = row || null
  projForm.id = row ? row.id : null
  projForm.name = row ? row.name : ''
  projForm.code = row ? row.code : ''
  projForm.type = row ? row.type : 'PARADE'
  projForm.countInTotal = row ? !!row.countInTotal : true
  projForm.sortOrder = row ? (row.sortOrder || 0) : 0
  projDialogVisible.value = true
}

async function saveProject() {
  if (!projForm.name || !projForm.name.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  projSaving.value = true
  try {
    const payload = {
      name: projForm.name.trim(),
      code: (projForm.code || '').trim(),
      type: projForm.type,
      countInTotal: projForm.countInTotal,
      sortOrder: projForm.sortOrder || 0
    }
    if (projForm.id) payload.id = projForm.id
    await request.post('/custom-project', payload)
    ElMessage.success('项目已保存')
    projDialogVisible.value = false
    await loadProjects()
  } catch (e) {
    // 拦截器已提示
  } finally {
    projSaving.value = false
  }
}

async function deleteProject(row) {
  try {
    await request.delete(`/custom-project/${row.id}`)
    ElMessage.success('已删除')
    if (currentProject.value && currentProject.value.id === row.id) currentProject.value = null
    await loadProjects()
  } catch (e) {
    // 拦截器已提示
  }
}

async function toggleCountInTotal(row, val) {
  try {
    await request.post('/custom-project', {
      id: row.id,
      name: row.name,
      code: row.code || '',
      type: row.type,
      countInTotal: val,
      sortOrder: row.sortOrder || 0
    })
    ElMessage.success('已更新计入总分设置')
  } catch (e) {
    row.countInTotal = !val // 回滚
  }
}

async function selectProject(row) {
  currentProject.value = row
  await loadScoreClasses()
}

async function loadScoreClasses() {
  if (!currentProject.value) return
  scoreLoading.value = true
  try {
    const res = await request.get('/classes')
    const list = Array.isArray(res) ? res : (res.records || res.list || [])
    let existing = []
    try {
      const pr = await request.get('/parade-score', {
        params: { projectCode: currentProject.value.code, grade: undefined }
      })
      existing = Array.isArray(pr) ? pr : (pr.records || pr.list || [])
    } catch (e) { existing = [] }
    const map = {}
    existing.forEach(p => {
      const cid = p.classId || p.classInfoId
      if (cid != null) map[cid] = p.score
    })
    scoreRows.value = list
      .filter(c => c.isParticipating !== false)
      .map(c => ({
        classId: c.id,
        className: c.name,
        grade: c.grade,
        score: map[c.id] != null ? map[c.id] : 0,
        id: map[c.id] != null ? (existing.find(p => (p.classId || p.classInfoId) === c.id)?.id) : null
      }))
  } catch (e) {
    ElMessage.error('载入班级失败')
  } finally {
    scoreLoading.value = false
  }
}

function removeScoreRow(idx) { scoreRows.value.splice(idx, 1) }

async function saveScores() {
  if (!currentProject.value) return
  scoreSaving.value = true
  try {
    const items = scoreRows.value
      .filter(r => r.classId && r.score != null && Number(r.score) >= 0)
      .map(r => ({ classId: r.classId, score: Number(r.score) }))
    await request.post('/parade-score', items, { params: { projectCode: currentProject.value.code } })
    ElMessage.success('得分已保存')
    await loadScoreClasses()
  } catch (e) {
    // 拦截器已提示
  } finally {
    scoreSaving.value = false
  }
}

async function onScoreFile(e) {
  const file = e.target.files?.[0]
  if (!file || !currentProject.value) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const res = await request.post('/parade-score/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      params: { projectCode: currentProject.value.code }
    })
    ElMessage.success(`导入完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    e.target.value = ''
    await loadScoreClasses()
  } catch (err) {
    ElMessage.error('导入失败')
  }
}

async function clearScores() {
  if (!currentProject.value) return
  try {
    await request.delete('/parade-score', { params: { projectCode: currentProject.value.code } })
    ElMessage.success('已清空本项目得分')
    scoreRows.value = []
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(() => { loadProjects() })
</script>

<style scoped>
.custom-projects { padding: 4px; }
.page-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:14px; }
.pg-title { margin:0 0 4px; font-size:20px; }
.pg-desc { margin:0; color:#909399; font-size:13px; max-width:760px; }
.block { margin-bottom:16px; }
.block-title { font-weight:600; }
</style>
