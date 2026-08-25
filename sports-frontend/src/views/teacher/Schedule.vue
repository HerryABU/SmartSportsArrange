<template>
  <div class="schedule-page" v-loading="loading">
    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-tag type="primary" effect="dark" size="large" round>📅 项目编排</el-tag>
          <span class="hint">将比赛项目自动调度到「天 × 时段 × 场地」时间表</span>
        </div>
        <div class="toolbar-right">
          <el-button type="primary" :icon="MagicStick" @click="showAutoDialog = true">自动编排</el-button>
          <el-button type="success" :icon="Download" @click="exportSheet" :disabled="!items.length">导出赛程表</el-button>
          <el-button type="warning" :icon="RefreshLeft" @click="clearAll" :disabled="!items.length">清空</el-button>
        </div>
      </div>
    </el-card>

    <!-- 空态 -->
    <el-card v-if="!items.length" shadow="never" class="empty-card">
      <el-empty description="暂无赛程，点击「自动编排」一键生成项目日程">
        <el-button type="primary" :icon="MagicStick" @click="showAutoDialog = true">立即自动编排</el-button>
      </el-empty>
    </el-card>

    <!-- 赛程展示（按天分组） -->
    <template v-else>
      <el-card v-for="(dayItems, day) in groupedByDay" :key="day" shadow="never" class="day-card">
        <template #header>
          <div class="day-header">
            <span class="day-title">🏅 第 {{ day }} 天</span>
            <el-tag type="info" effect="plain" round>{{ dayItems.length }} 个项目</el-tag>
          </div>
        </template>

        <!-- 按时段分组 -->
        <div v-for="(slotItems, slot) in groupBySlot(dayItems)" :key="slot" class="slot-block">
          <div class="slot-title">
            <el-tag :type="slotTag(slot)" effect="plain">{{ slot }}</el-tag>
          </div>
          <el-table :data="slotItems" size="small" border stripe>
            <el-table-column prop="startTime" label="开始" width="80" align="center" />
            <el-table-column prop="endTime" label="结束" width="80" align="center" />
            <el-table-column prop="venue" label="场地" width="110" align="center">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.venue }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="eventName" label="项目名称" min-width="160" />
            <el-table-column prop="eventCode" label="编码" width="90" align="center" />
            <el-table-column prop="category" label="类别" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.category === '径赛' ? 'success' : 'warning'">{{ row.category || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="durationMinutes" label="用时" width="80" align="center">
              <template #default="{ row }">{{ row.durationMinutes }} 分</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" :icon="EditPen" @click="openEdit(row)">调整</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>
    </template>

    <!-- 自动编排配置对话框 -->
    <el-dialog v-model="showAutoDialog" title="自动编排配置" width="500px" :close-on-click-modal="false">
      <el-form :model="autoConfig" label-width="120px" label-position="left">
        <el-form-item label="比赛天数">
          <el-input-number v-model="autoConfig.days" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="autoConfig.time_slots" multiple allow-create default-first-option style="width:100%"
            placeholder="如 上午/下午/晚上">
            <el-option v-for="s in ['上午','下午','晚上']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="场地">
          <el-select v-model="autoConfig.venues" multiple allow-create default-first-option style="width:100%"
            placeholder="如 田径场/田赛场地">
            <el-option v-for="v in ['田径场','田赛场地']" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="每时段时长(分)">
          <el-input-number v-model="autoConfig.slot_minutes" :min="30" :max="480" :step="30" />
        </el-form-item>
        <el-form-item label="默认项目用时(分)">
          <el-input-number v-model="autoConfig.per_event_minutes" :min="10" :max="120" :step="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAutoDialog = false">取消</el-button>
        <el-button type="primary" :loading="arranging" :icon="MagicStick" @click="doAutoSchedule">
          {{ arranging ? '编排中...' : '开始编排' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 手动调整对话框 -->
    <el-dialog v-model="showEditDialog" title="调整项目安排" width="440px" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="项目">
          <span class="edit-event-name">{{ editForm.eventName }}</span>
        </el-form-item>
        <el-form-item label="天数">
          <el-input-number v-model="editForm.day" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="editForm.timeSlot" style="width:100%">
            <el-option v-for="s in ['上午','下午','晚上']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-time-select v-model="editForm.startTime" start="07:00" step="00:10" end="21:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-time-select v-model="editForm.endTime" start="07:00" step="00:10" end="21:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="场地">
          <el-input v-model="editForm.venue" placeholder="如 田径场" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Download, RefreshLeft, EditPen } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const arranging = ref(false)
const items = ref([])
const showAutoDialog = ref(false)
const showEditDialog = ref(false)
const editingId = ref(null)

const autoConfig = reactive({
  days: 2,
  time_slots: ['上午', '下午'],
  venues: ['田径场'],
  slot_minutes: 180,
  per_event_minutes: 30
})

const editForm = reactive({
  eventId: null, eventName: '', day: 1, timeSlot: '上午',
  startTime: '', endTime: '', venue: '田径场'
})

// 按天分组（对象：day → items）
const groupedByDay = computed(() => {
  const groups = {}
  items.value.forEach(i => {
    const d = i.day || 1
    if (!groups[d]) groups[d] = []
    groups[d].push(i)
  })
  // 按 day 升序返回
  return Object.keys(groups).sort((a, b) => a - b).reduce((acc, k) => {
    acc[k] = groups[k].sort((a, b) => (a.startTime || '').localeCompare(b.startTime || ''))
    return acc
  }, {})
})

function groupBySlot(dayItems) {
  const groups = {}
  dayItems.forEach(i => {
    const s = i.timeSlot || '其他'
    if (!groups[s]) groups[s] = []
    groups[s].push(i)
  })
  return groups
}

function slotTag(slot) {
  if (slot === '上午') return 'primary'
  if (slot === '下午') return 'warning'
  if (slot === '晚上') return 'info'
  return ''
}

async function fetchList() {
  loading.value = true
  try {
    const res = await request.get('/schedule')
    items.value = res.items || []
  } catch (e) {
    console.error(e)
    items.value = []
  } finally {
    loading.value = false
  }
}

async function doAutoSchedule() {
  if (!autoConfig.time_slots.length) { ElMessage.warning('请至少选择一个时段'); return }
  if (!autoConfig.venues.length) { ElMessage.warning('请至少选择一个场地'); return }
  arranging.value = true
  try {
    const res = await request.post('/schedule/auto', { ...autoConfig })
    items.value = res.items || []
    showAutoDialog.value = false
    ElMessage.success('项目编排完成！共 ' + (res.total || 0) + ' 个项目')
  } catch (e) {
    console.error(e)
  } finally {
    arranging.value = false
  }
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(editForm, {
    eventId: row.eventId, eventName: row.eventName, day: row.day,
    timeSlot: row.timeSlot, startTime: row.startTime, endTime: row.endTime, venue: row.venue
  })
  showEditDialog.value = true
}

async function saveEdit() {
  try {
    // 更新本地对应项并整体保存
    const updated = items.value.map(i => i.id === editingId.value ? { ...i, ...editForm } : i)
    const res = await request.post('/schedule/save', updated)
    items.value = res.items || []
    showEditDialog.value = false
    ElMessage.success('调整已保存')
  } catch (e) {
    console.error(e)
  }
}

function exportSheet() {
  window.open(apiBase() + '/schedule/export', '_blank')
}

async function clearAll() {
  try {
    await ElMessageBox.confirm('确定清空全部项目赛程吗？', '确认清空', { type: 'warning' })
    await request.delete('/schedule')
    items.value = []
    ElMessage.success('赛程已清空')
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

onMounted(fetchList)
</script>

<style scoped>
.schedule-page { display: flex; flex-direction: column; gap: 12px; }
.toolbar-card { border-radius: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; flex-wrap: wrap; }
.hint { font-size: 12px; color: #909399; }
.empty-card { border-radius: 12px; }
.day-card { border-radius: 12px; }
.day-header { display: flex; align-items: center; gap: 10px; }
.day-title { font-size: 16px; font-weight: 700; color: #303133; }
.slot-block { margin-bottom: 16px; }
.slot-title { margin-bottom: 8px; }
.edit-event-name { font-weight: 600; color: #303133; }
@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: flex-start; }
  .toolbar-right { width: 100%; }
  .toolbar-right .el-button { flex: 1; }
}
</style>
