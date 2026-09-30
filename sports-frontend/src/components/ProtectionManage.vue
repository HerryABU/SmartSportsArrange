<template>
  <el-dialog :model-value="visible" title="行政时间保护（规避时间）" width="760px"
    :close-on-click-modal="false" @update:model-value="v => emit('update:visible', v)">
    <div class="prot-tip">
      某些老师/裁判因特殊需要（监考、开会等）在特定时段不能参与协调或执裁：
      <b>全校</b>避让时段会切分时间窗（整段不可排项目），
      <b>班主任</b>个人时段会使其班级项目避开，
      <b>裁判</b>个人时段会使其不被分到落在该时段的组次。
    </div>

    <!-- 新增表单 -->
    <div class="prot-form">
      <el-select v-model="form.targetType" style="width: 130px">
        <el-option label="全校避让" value="GLOBAL" />
        <el-option label="班主任" value="TEACHER" />
        <el-option label="裁判" value="REFEREE" />
      </el-select>
      <el-select v-if="form.targetType === 'REFEREE'" v-model="form.targetId" filterable clearable
        placeholder="选择裁判" style="width: 150px">
        <el-option v-for="r in referees" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <el-select v-else-if="form.targetType === 'TEACHER'" v-model="form.targetId" filterable clearable
        placeholder="选择班主任" style="width: 150px">
        <el-option v-for="t in teachers" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-input-number v-model="form.day" :min="1" :max="30" placeholder="天" style="width: 110px" />
      <el-switch v-model="allDay" active-text="全部天" style="margin: 0 6px" />
      <el-time-select v-model="form.startTime" start="06:00" step="00:10" end="22:00"
        placeholder="开始" style="width: 120px" />
      <span>至</span>
      <el-time-select v-model="form.endTime" start="06:00" step="00:10" end="22:00"
        placeholder="结束" style="width: 120px" />
      <el-input v-model="form.reason" placeholder="原因（如 监考）" style="width: 150px" />
      <el-button type="primary" :icon="Plus" @click="save">添加</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="list" size="small" border stripe max-height="380" style="margin-top: 12px">
      <el-table-column label="对象" min-width="140">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTag(row.targetType)">{{ typeLabel(row.targetType) }}</el-tag>
          <span style="margin-left: 6px">{{ row.targetName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="天数" width="70" align="center">
        <template #default="{ row }">{{ row.day || '全部' }}</template>
      </el-table-column>
      <el-table-column label="时段" width="130" align="center">
        <template #default="{ row }">{{ row.startTime }} ~ {{ row.endTime }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip />
      <el-table-column label="启用" width="70" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="v => toggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" align="center">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!list.length" description="暂无规避时间，点击上方「添加」新增" :image-size="60" />
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import request from '@/utils/request'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible'])

const list = ref([])
const referees = ref([])
const teachers = ref([])
const allDay = ref(true)
const form = reactive({ targetType: 'GLOBAL', targetId: null, day: null, startTime: '', endTime: '', reason: '' })

watch(() => allDay.value, v => { if (v) form.day = null })

function typeLabel(t) {
  return { GLOBAL: '全校', TEACHER: '班主任', REFEREE: '裁判' }[t] || t
}
function typeTag(t) {
  return { GLOBAL: 'danger', TEACHER: 'warning', REFEREE: 'success' }[t] || 'info'
}

async function load() {
  try { list.value = await request.get('/protections') || [] } catch (e) { list.value = [] }
}
async function loadTargets() {
  try {
    const rs = await request.get('/system/referees')
    referees.value = (Array.isArray(rs) ? rs : []).map(r => ({ id: r.id, name: r.name }))
  } catch (e) { referees.value = [] }
  try {
    const res = await request.get('/classes', { params: { page: 1, size: 1000 } })
    const records = res?.records || res?.list || (Array.isArray(res) ? res : [])
    const map = new Map()
    records.forEach(c => {
      const tid = c?.teacherUser?.id || c?.teacherUserId
      const tname = c?.teacherName
      if (tid && !map.has(tid)) map.set(tid, { id: tid, name: tname || ('教师#' + tid) })
    })
    teachers.value = [...map.values()]
  } catch (e) { teachers.value = [] }
}

async function save() {
  if (!form.startTime || !form.endTime) { ElMessage.warning('请填写保护时段的起止时间'); return }
  const body = {
    targetType: form.targetType,
    targetId: form.targetType === 'GLOBAL' ? null : form.targetId,
    day: form.day,
    startTime: form.startTime,
    endTime: form.endTime,
    reason: form.reason,
    enabled: true
  }
  try {
    await request.post('/protections', body)
    ElMessage.success('已添加规避时间')
    form.targetId = null; form.day = null; form.startTime = ''; form.endTime = ''; form.reason = ''
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

async function toggle(row, v) {
  try {
    await request.put(`/protections/${row.id}`, { enabled: v })
    row.enabled = v
  } catch (e) { /* ignore */ }
}

async function remove(row) {
  try {
    await request.delete(`/protections/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* ignore */ }
}

watch(() => props.visible, v => { if (v) { load(); loadTargets() } })
onMounted(() => { if (props.visible) { load(); loadTargets() } })
</script>

<style scoped>
.prot-tip { font-size: 13px; color: #606266; background: #f4f7fb; border-radius: 8px; padding: 10px 12px; line-height: 1.7; margin-bottom: 12px; }
.prot-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
