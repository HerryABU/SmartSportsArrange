<template>
  <div class="schedule-page" v-loading="loading">
    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-tag type="primary" effect="dark" size="large" round>📅 赛程编排</el-tag>
          <span class="hint">先配置运动会日期/时段/年级顺序，再一键生成赛程（径赛串行、田赛并行）</span>
        </div>
        <div class="toolbar-right">
          <el-button :icon="Setting" @click="openMeetConfig">运动会日程配置</el-button>
          <el-button type="primary" :icon="MagicStick" @click="doAutoSchedule">一键编排赛程</el-button>
          <el-button type="success" :icon="Download" @click="exportSheet" :disabled="!items.length">导出赛程表</el-button>
          <el-button type="warning" :icon="RefreshLeft" @click="clearAll" :disabled="!items.length">清空</el-button>
        </div>
      </div>
    </el-card>

    <el-alert type="info" show-icon :closable="false"
      title="编排规则：项目按年级出场顺序展开（可在「运动会日程配置」中自定义，或跟随系统设置的年级管理）；径赛默认串行独占跑道依次进行，田赛默认并行多场地同时开赛；时长按报名人数估算并受项目最大用时封顶，项目之间留出间隔。日期/时段全部来自日程配置，可每天不同。"
      style="border-radius: 10px" />

    <!-- 空态 -->
    <el-card v-if="!items.length" shadow="never" class="empty-card">
      <el-empty description="暂无赛程，先配置运动会日程，再点击「一键编排赛程」">
        <el-button type="primary" :icon="MagicStick" @click="doAutoSchedule">立即编排</el-button>
      </el-empty>
    </el-card>

    <!-- 赛程展示（按天分组） -->
    <template v-else>
      <el-card v-for="(dayItems, day) in groupedByDay" :key="day" shadow="never" class="day-card">
        <template #header>
          <div class="day-header">
            <span class="day-title">🏅 第 {{ day }} 天</span>
            <el-tag type="info" effect="plain" round v-if="dayItems[0]?.scheduleDate">
              {{ dayItems[0].scheduleDate }}
            </el-tag>
            <el-tag type="info" effect="plain" round>{{ dayItems.length }} 个单元</el-tag>
          </div>
        </template>

        <!-- 按时段分组 -->
        <div v-for="(slotItems, slot) in groupBySlot(dayItems)" :key="slot" class="slot-block">
          <div class="slot-title">
            <el-tag :type="slotTag(slot)" effect="plain">{{ slot }}</el-tag>
            <span class="slot-time" v-if="slotItems[0]?.startTime">{{ slotItems[0].startTime }} 起</span>
          </div>
          <el-table :data="slotItems" size="small" border stripe>
            <el-table-column prop="grade" label="年级" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="info" effect="plain">{{ row.grade || '不分年级' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="eventName" label="项目名称" min-width="170">
              <template #default="{ row }">
                <span v-if="row.isTeam" class="team-mark" title="团体赛">团</span>
                {{ row.eventName }}
              </template>
            </el-table-column>
            <el-table-column prop="startTime" label="开始" width="90" align="center" />
            <el-table-column prop="endTime" label="结束" width="90" align="center" />
            <el-table-column prop="venue" label="场地" width="110" align="center">
              <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.venue }}</el-tag></template>
            </el-table-column>
            <el-table-column label="类别/道次" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isTrack ? 'success' : 'warning'">
                  {{ row.isTrack ? '径赛' : '田赛' }}{{ row.isTrack ? ` ·${row.laneCount}道` : '' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="durationMinutes" label="用时" width="80" align="center">
              <template #default="{ row }">{{ row.durationMinutes }} 分</template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">
                <span class="row-remark">{{ row.remark || '—' }}</span>
              </template>
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

    <!-- 运动会日程配置对话框（日期/时段/年级顺序/串行并行 全部可配置，不硬编码） -->
    <el-dialog v-model="showConfigDialog" title="运动会日程配置" width="860px" :close-on-click-modal="false"
      top="4vh">
      <el-form label-width="130px" label-position="left">
        <el-form-item label="运动会名称">
          <el-input v-model="meetForm.meetName" maxlength="40" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="meetForm.startDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择第一天日期" style="width: 220px" @change="syncDates" />
          <span class="hint" style="margin-left:12px">共 {{ meetForm.days }} 天，每天具体日期自动顺延</span>
        </el-form-item>
        <el-form-item label="比赛天数">
          <el-input-number v-model="meetForm.days" :min="1" :max="10" @change="syncDays" />
        </el-form-item>
        <el-form-item label="年级出场顺序">
          <div style="width:100%">
            <el-switch v-model="useCustomOrder" inline-prompt active-text="自定义顺序" inactive-text="跟随年级设置"
              style="margin-bottom:8px" @change="onCustomOrderChange" />
            <template v-if="!useCustomOrder">
              <div>
                <el-tag v-for="g in meetForm.gradeOrder" :key="g" style="margin-right:6px" size="small">
                  {{ g }}
                </el-tag>
              </div>
              <span class="hint" style="display:block;margin-top:6px">
                出场顺序实时跟随「系统设置 → 年级管理」的 sortOrder；调整后重新点击「一键编排赛程」即生效。
              </span>
            </template>
            <template v-else>
              <div class="grade-order-edit">
                <div v-for="(g, gi) in meetForm.gradeOrder" :key="g" class="grade-order-row">
                  <span class="go-idx">{{ gi + 1 }}</span>
                  <span class="go-name">{{ g }}</span>
                  <el-button-group>
                    <el-button :icon="Top" size="small" :disabled="gi === 0" title="上移" @click="moveGrade(gi, -1)" />
                    <el-button :icon="Bottom" size="small" :disabled="gi === meetForm.gradeOrder.length - 1"
                      title="下移" @click="moveGrade(gi, 1)" />
                  </el-button-group>
                </div>
                <el-button link type="primary" @click="useCustomOrder = false">恢复跟随年级设置</el-button>
              </div>
              <span class="hint" style="display:block;margin-top:4px">
                已启用自定义出场顺序（保存后以本处顺序为准）。
              </span>
            </template>
          </div>
        </el-form-item>

        <!-- 每天时段（各自独立，可不同） -->
        <el-form-item label="每天时段">
          <div style="width:100%">
            <div v-for="dc in meetForm.dayConfigs" :key="dc.day" class="day-config-block">
              <div class="day-config-title">
                第 {{ dc.day }} 天
                <span v-if="dc.date" class="hint">{{ dc.date }}</span>
              </div>
              <div v-for="(sl, si) in dc.slots" :key="sl.key" class="slot-row">
                <el-select v-model="sl.name" style="width:100px">
                  <el-option label="上午" value="上午" />
                  <el-option label="下午" value="下午" />
                  <el-option label="晚上" value="晚上" />
                </el-select>
                <el-time-select v-model="sl.start" start="06:00" step="00:10" end="22:00" style="width:130px"
                  placeholder="开始" />
                <span style="color:#909399">至</span>
                <el-time-select v-model="sl.end" start="06:00" step="00:10" end="22:00" style="width:130px"
                  placeholder="结束" />
                <el-button link type="danger" :icon="Delete" @click="removeSlot(dc, si)" />
              </div>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addSlot(dc)">添加时段</el-button>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="并行/串行">
          <div style="display:flex;gap:24px;width:100%">
            <div>
              <div class="hint" style="margin-bottom:4px">径赛</div>
              <el-radio-group v-model="meetForm.trackMode" size="small">
                <el-radio-button value="serial">串行（推荐）</el-radio-button>
                <el-radio-button value="parallel">并行</el-radio-button>
              </el-radio-group>
            </div>
            <div>
              <div class="hint" style="margin-bottom:4px">田赛</div>
              <el-radio-group v-model="meetForm.fieldMode" size="small">
                <el-radio-button value="parallel">并行（推荐）</el-radio-button>
                <el-radio-button value="serial">串行</el-radio-button>
              </el-radio-group>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="时长/间隔(分)">
          <div style="display:flex;gap:8px;align-items:center;width:100%">
            <span>项目上限</span>
            <el-input-number v-model="meetForm.defaultDurationMinutes" :min="5" :max="300" :step="5" />
            <span>间隔</span>
            <el-input-number v-model="meetForm.defaultIntervalMinutes" :min="0" :max="60" />
            <span>单组(径赛)</span>
            <el-input-number v-model="meetForm.heatMinutes" :min="1" :max="60" />
            <span>每人次(田赛)</span>
            <el-input-number v-model="meetForm.fieldPerAthleteMinutes" :min="1" :max="60" />
          </div>
        </el-form-item>

        <el-form-item label="场地">
          <el-select v-model="meetForm.venues" multiple allow-create default-first-option style="width:100%"
            placeholder="第 1 个为主场地（径赛串行用），其余供田赛并行">
            <el-option v-for="v in defaultVenues" :key="v" :label="v" :value="v" />
          </el-select>
          <div class="hint" style="width:100%">第一个场地默认留给径赛串行；田赛并行会在其余场地间并行开赛。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showConfigDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveMeetConfig">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- 手动调整对话框 -->
    <el-dialog v-model="showEditDialog" title="调整项目安排" width="460px" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="项目">
          <span class="edit-event-name">{{ editForm.eventName }}</span>
        </el-form-item>
        <el-form-item label="天数">
          <el-input-number v-model="editForm.day" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="日期" v-if="editForm.scheduleDate">
          <span>{{ editForm.scheduleDate }}</span>
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="editForm.timeSlot" style="width:100%">
            <el-option v-for="s in ['上午', '下午', '晚上']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-time-select v-model="editForm.startTime" start="06:00" step="00:10" end="22:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-time-select v-model="editForm.endTime" start="06:00" step="00:10" end="22:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="场地">
          <el-input v-model="editForm.venue" placeholder="如 田径场" />
        </el-form-item>
        <el-form-item label="年级">
          <el-select v-model="editForm.grade" style="width:100%" clearable placeholder="不分年级">
            <el-option v-for="g in meetForm.gradeOrder" :key="g" :label="g" :value="g" />
          </el-select>
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
import { MagicStick, Download, RefreshLeft, EditPen, Setting, Plus, Delete, Top, Bottom } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'

const loading = ref(false)
const arranging = ref(false)
const savingConfig = ref(false)
const items = ref([])
const showEditDialog = ref(false)
const showConfigDialog = ref(false)
const editingId = ref(null)
// 年级出场顺序是否自定义（false=跟随系统设置·年级管理的 sortOrder，保存时回传空数组避免冻结）
const useCustomOrder = ref(false)

const defaultVenues = ['田径场', '田赛A区', '田赛B区']

const emptySlot = { key: 'AM', name: '上午', start: '08:00', end: '11:30' }

const meetForm = reactive({
  meetName: '',
  startDate: '',
  days: 2,
  gradeOrder: [],
  dayConfigs: [
    { day: 1, date: '', slots: [{ ...emptySlot }, { key: 'PM', name: '下午', start: '14:00', end: '17:30' }] },
    { day: 2, date: '', slots: [{ ...emptySlot }, { key: 'PM', name: '下午', start: '14:00', end: '17:30' }] }
  ],
  trackMode: 'serial',
  fieldMode: 'parallel',
  defaultDurationMinutes: 30,
  defaultIntervalMinutes: 5,
  heatMinutes: 6,
  fieldPerAthleteMinutes: 3,
  venues: [...defaultVenues]
})

const editForm = reactive({
  id: null, eventId: null, eventName: '', day: 1, scheduleDate: '', grade: '',
  timeSlot: '上午', startTime: '', endTime: '', venue: '田径场'
})

// 按天分组
const groupedByDay = computed(() => {
  const groups = {}
  items.value.forEach(i => {
    const d = i.day || 1
    if (!groups[d]) groups[d] = []
    groups[d].push(i)
  })
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
    items.value = []
  } finally {
    loading.value = false
  }
}

// ==================== 运动会日程配置 ====================
function blankSlots() {
  return [{ key: 'AM', name: '上午', start: '08:00', end: '11:30' },
          { key: 'PM', name: '下午', start: '14:00', end: '17:30' }]
}

async function openMeetConfig() {
  showConfigDialog.value = true
  try {
    const res = await request.get('/system/meet-schedule')
    Object.assign(meetForm, res)
    // 规范化 dayConfigs / slots
    meetForm.dayConfigs = (res.dayConfigs || []).map((dc, i) => ({
      day: dc.day || i + 1,
      date: dc.date || '',
      slots: (dc.slots && dc.slots.length ? dc.slots : blankSlots()).map(s => ({
        key: s.key || s.name, name: s.name || '上午', start: s.start || '08:00', end: s.end || '11:30'
      }))
    }))
    meetForm.venues = res.venues && res.venues.length ? res.venues : [...defaultVenues]
    // 服务端已自动填充 gradeOrder（跟随年级设置或已显式定制）
    useCustomOrder.value = !!res.gradeOrderCustom
    meetForm.gradeOrder = (res.gradeOrder && res.gradeOrder.length) ? [...res.gradeOrder] : []
    defaultOrderSnapshot.value = [...meetForm.gradeOrder]
  } catch (e) {
    // 读取失败仍可编辑默认值
  }
}

function onCustomOrderChange(val) {
  // 从“跟随”切到“自定义”时，以当前（推导）顺序为底稿
  if (val && !meetForm.gradeOrder.length) {
    try {
      meetForm.gradeOrder = [...defaultOrderSnapshot.value]
    } catch (e) { /* ignore */ }
  }
}

function moveGrade(index, dir) {
  const target = index + dir
  if (target < 0 || target >= meetForm.gradeOrder.length) return
  const arr = [...meetForm.gradeOrder]
  ;[arr[index], arr[target]] = [arr[target], arr[index]]
  meetForm.gradeOrder = arr
}

// 打开配置时抓一份“跟随”底稿，供切到自定义时回填
const defaultOrderSnapshot = ref([])

function syncDates() {
  if (!meetForm.startDate) return
  meetForm.dayConfigs.forEach((dc, i) => {
    const d = new Date(meetForm.startDate)
    d.setDate(d.getDate() + i)
    dc.date = d.toISOString().slice(0, 10)
  })
}

function syncDays() {
  const n = Number(meetForm.days) || 2
  while (meetForm.dayConfigs.length < n) {
    meetForm.dayConfigs.push({
      day: meetForm.dayConfigs.length + 1, date: '', slots: blankSlots()
    })
  }
  meetForm.dayConfigs = meetForm.dayConfigs.slice(0, n)
  meetForm.dayConfigs.forEach((dc, i) => { dc.day = i + 1 })
  syncDates()
}

function addSlot(dc) {
  const keys = 'ABCDEFG'.split('')
  const key = keys[dc.slots.length] || 'X' + dc.slots.length
  const start = dc.slots.length ? dc.slots[dc.slots.length - 1].end : '14:00'
  dc.slots.push({ key, name: '下午', start, end: '17:30' })
}

function removeSlot(dc, si) {
  if (dc.slots.length <= 1) return
  dc.slots.splice(si, 1)
}

async function saveMeetConfig() {
  if (!meetForm.startDate) { ElMessage.warning('请选择运动会开始日期'); return }
  if (!meetForm.venues.length) { ElMessage.warning('请至少配置一个场地'); return }
  for (const dc of meetForm.dayConfigs) {
    if (!dc.slots.length) { ElMessage.warning(`第 ${dc.day} 天至少需要一个时段`); return }
  }
  savingConfig.value = true
  try {
    const payload = {
      meetName: meetForm.meetName,
      startDate: meetForm.startDate,
      days: meetForm.dayConfigs.length,
      dayConfigs: meetForm.dayConfigs,
      // 未自定义时回传空数组 → 服务端归一化，使“年级管理”调整 sortOrder 仍可传导，防止冻结
      gradeOrder: useCustomOrder.value ? meetForm.gradeOrder : [],
      venues: meetForm.venues,
      trackMode: meetForm.trackMode,
      fieldMode: meetForm.fieldMode,
      defaultDurationMinutes: meetForm.defaultDurationMinutes,
      defaultIntervalMinutes: meetForm.defaultIntervalMinutes,
      heatMinutes: meetForm.heatMinutes,
      fieldPerAthleteMinutes: meetForm.fieldPerAthleteMinutes
    }
    await request.put('/system/meet-schedule', payload)
    ElMessage.success('运动会日程配置已保存')
    showConfigDialog.value = false
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingConfig.value = false
  }
}

// ==================== 一键编排（赛程 + 自动道次） ====================
async function doAutoSchedule() {
  arranging.value = true
  try {
    const res = await request.post('/schedule/auto', {})
    items.value = res.items || []
    const auto = res.autoArrange || null
    let autoTip = ''
    if (auto) {
      autoTip = `；已自动生成道次编排 ${auto.ok} 个（性别组）${auto.failed ? '，' + auto.failed + ' 个失败' : ''}`
    }
    if (res.warnings && res.warnings.length) {
      ElMessage.warning('编排完成，但有 ' + res.warnings.length + ' 条提示：' + res.warnings[0] + autoTip)
    } else {
      ElMessage.success('赛程编排完成！共 ' + (res.total || 0) + ' 个单元' + autoTip)
    }
    if (auto && auto.fails && auto.fails.length) console.warn('自动道次失败明细', auto.fails)
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  } finally {
    arranging.value = false
  }
}

// ==================== 手动调整 ====================
function openEdit(row) {
  editingId.value = row.id
  Object.assign(editForm, {
    id: row.id, eventId: row.eventId, eventName: row.eventName, day: row.day,
    scheduleDate: row.scheduleDate || '', grade: row.grade || '',
    timeSlot: row.timeSlot, startTime: row.startTime, endTime: row.endTime, venue: row.venue
  })
  showEditDialog.value = true
}

async function saveEdit() {
  try {
    const updated = items.value.map(i => i.id === editingId.value ? { ...i, ...editForm } : i)
    const res = await request.post('/schedule/save', updated)
    items.value = res.items || []
    showEditDialog.value = false
    ElMessage.success('调整已保存')
  } catch (e) {
    console.error(e)
  }
}

async function exportSheet() {
  try { await downloadApi('/schedule/export', '赛程总表.xlsx'); ElMessage.success('导出成功') }
  catch (e) { ElMessage.error(e?.message || '导出失败，请重新登录后再试') }
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
.slot-title { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.slot-time { font-size: 12px; color: #909399; }
.edit-event-name { font-weight: 600; color: #303133; }
.team-mark {
  display: inline-block;
  width: 18px; height: 18px; line-height: 18px;
  text-align: center; border-radius: 4px;
  background: #f56c6c; color: #fff; font-size: 11px;
  margin-right: 4px;
}
.row-remark { font-size: 12px; color: #909399; }
.day-config-block {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.grade-order-edit { display: flex; flex-direction: column; gap: 6px; }
.grade-order-row {
  display: flex; align-items: center; gap: 10px;
  background: #f8fafc; border: 1px solid #e4e7ed; border-radius: 8px; padding: 4px 10px;
}
.grade-order-row .go-idx {
  width: 22px; height: 22px; border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6, #6366f1); color: #fff;
  font-size: 12px; display: inline-flex; align-items: center; justify-content: center; font-weight: 600;
}
.grade-order-row .go-name { flex: 1; font-size: 14px; color: #303133; }
.day-config-title { font-weight: 600; margin-bottom: 8px; color: #303133; }
.slot-row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: flex-start; }
  .toolbar-right { width: 100%; }
}
</style>
