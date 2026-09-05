<template>
  <div class="arrange-page">
    <!-- Desktop left panel -->
    <el-card class="left-panel" shadow="hover">
      <template #header>
        <div class="panel-header">
          <el-icon><Trophy /></el-icon>
          <span>比赛项目</span>
        </div>
      </template>
      <el-input v-model="searchKeyword" placeholder="搜索项目..." size="small" clearable :prefix-icon="Search" />
      <el-tree
        :data="eventTree"
        node-key="id"
        :props="{ label: 'label', children: 'children' }"
        :filter-node-method="filterNode"
        @node-click="onEventClick"
        ref="treeRef"
        style="margin-top: 10px"
        highlight-current
        :default-expand-all="true"
      />
    </el-card>

    <!-- Mobile drawer for project list -->
    <el-drawer v-model="mobileDrawer" direction="ltr" size="280px" :show-close="false">
      <template #header>
        <div class="panel-header">
          <el-icon><Trophy /></el-icon>
          <span>比赛项目</span>
        </div>
      </template>
      <el-input v-model="searchKeyword" placeholder="搜索项目..." size="small" clearable :prefix-icon="Search" />
      <el-tree
        :data="eventTree"
        node-key="id"
        :props="{ label: 'label', children: 'children' }"
        :filter-node-method="filterNode"
        @node-click="(data) => { onEventClick(data); mobileDrawer = false }"
        ref="treeRefMobile"
        style="margin-top: 10px"
        highlight-current
        :default-expand-all="true"
      />
    </el-drawer>

    <!-- 右侧：编排详情 -->
    <div class="right-panel">
      <el-card v-if="!selectedEvent" class="empty-card" shadow="hover">
        <el-empty description="请从左侧选择一个项目开始编排">
          <template #image>
            <div class="empty-icon">🏟️</div>
          </template>
        </el-empty>
      </el-card>

      <template v-if="selectedEvent">
        <!-- 操作栏：专属头部 + 功能按钮（避免与下方"道次编排结果"header 视觉粘连） -->
        <el-card class="toolbar" shadow="hover">
          <template #header>
            <div class="toolbar-header">
              <span class="th-ico">⚙️</span>
              <span class="th-title">编排功能</span>
              <span class="th-tip">先选「执行编排」生成最终道次，再在此处调整</span>
            </div>
          </template>
          <div class="toolbar-content">
            <div class="toolbar-left">
              <el-button class="mobile-project-btn" @click="mobileDrawer = true" :icon="Trophy" size="small">项目列表</el-button>
              <el-tag type="primary" size="large" effect="dark" round>
                {{ selectedEvent.name }}
              </el-tag>
              <el-tag v-if="selectedEvent.gender === 'M'" type="info" size="large" effect="plain" round>🏃 男子组</el-tag>
              <el-tag v-else-if="selectedEvent.gender === 'F'" type="danger" size="large" effect="plain" round>🏃‍♀️ 女子组</el-tag>
              <el-tag v-if="arranged" type="success" size="large" effect="plain" round>
                已编排 {{ heats.length }} 组
              </el-tag>
              <el-tag v-else type="warning" size="large" effect="plain" round>未编排</el-tag>
            </div>
            <div class="toolbar-right">
              <el-button-group>
                <el-button type="primary" @click="showArrangeDialog" :icon="MagicStick">
                  执行编排
                </el-button>
                <el-button @click="previewArrange" :icon="View">
                  预览
                </el-button>
                <el-button type="success" @click="exportSheet" :disabled="!arranged" :icon="Download">
                  导出道次表
                </el-button>
                <el-button type="warning" @click="rollbackArrange" :disabled="!arranged" :icon="RefreshLeft">
                  回滚
                </el-button>
                <el-button type="danger" @click="clearArrange" :disabled="!arranged" :icon="Delete">
                  清除
                </el-button>
              </el-button-group>
            </div>
          </div>
        </el-card>

        <!-- 编排预览提示 -->
        <el-card v-if="previewData" class="preview-card" shadow="hover">
          <template #header>
            <div class="preview-header">
              <el-icon><View /></el-icon>
              <span>编排预览</span>
              <el-tag type="warning" size="small">未保存</el-tag>
            </div>
          </template>
          <div class="heat-grid">
            <HeatGrid :heats="previewData.heats" :statistics="previewData.statistics" />
          </div>
        </el-card>

        <!-- 道次网格 -->
        <el-card v-if="arranged" class="heat-grid-card" shadow="hover">
          <template #header>
            <div class="grid-header">
              <el-icon><Grid /></el-icon>
              <span>道次编排结果</span>
              <el-radio-group v-if="roundsData.length > 1" v-model="activeRound" size="small"
                @change="applyActiveRound">
                <el-radio-button value="preliminary">预赛</el-radio-button>
                <el-radio-button value="final">决赛</el-radio-button>
              </el-radio-group>
              <el-tag v-if="statistics && statistics.version != null" type="info" size="small">
                版本 {{ statistics.version }}
              </el-tag>
            </div>
          </template>
          <div class="heat-grid">
            <HeatGrid :heats="heats" :statistics="statistics" />
          </div>
        </el-card>

        <!-- 预赛淘汰（径赛 needHeats）：生成预赛 → 录入预赛成绩 → 立即计算晋级 -->
        <el-card v-if="selectedEvent && prelimEnabled" class="prelim-card" shadow="hover">
          <template #header>
            <div class="prelim-header">
              <el-icon><Timer /></el-icon>
              <span>预赛淘汰（径赛 · 需要预赛时走此流程）</span>
              <el-tag type="warning" size="small">① 生成预赛 → ② 录入预赛成绩 → ③ 立即计算晋级并排决赛</el-tag>
            </div>
          </template>
          <div class="prelim-body">
            <div class="prelim-filters">
              <el-select v-model="prelimGrade" size="small" style="width:130px">
                <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
              </el-select>
              <el-radio-group v-model="prelimGender" size="small">
                <el-radio-button value="M">男子</el-radio-button>
                <el-radio-button value="F">女子</el-radio-button>
              </el-radio-group>
              <el-button size="small" type="primary" plain :loading="prelimWorking" @click="generatePrelim">
                ① 生成预赛
              </el-button>
              <el-button size="small" type="success" plain :loading="prelimWorking"
                :disabled="!prelimHeats.length" @click="submitPrelimTimes">② 录入预赛成绩</el-button>
              <el-button size="small" type="danger" plain :loading="prelimWorking"
                :disabled="!prelimHeats.length" @click="computeQualify">③ 立即计算晋级并排决赛</el-button>
              <span class="prelim-advance-label">晋级前</span>
              <el-input-number v-model="prelimAdvance" :min="1" :max="99" size="small" style="width:80px"
                :title="'晋级人数（默认 ' + (selectedEvent.advanceCount || 8) + ' 名）'" />
              <span class="hint" style="font-size:12px">名</span>
              <el-button size="small" plain :disabled="!prelimHeats.length" @click="loadQualifiers">查看晋级名单</el-button>
            </div>

            <!-- 预赛分组：每组输入预赛成绩 -->
            <div v-if="prelimHeats.length" class="prelim-times">
              <div v-for="h in prelimHeats" :key="'h' + h.heat" class="prelim-heat-row">
                <div class="prelim-heat-label">第 {{ h.heat }} 组</div>
                <el-table :data="h.lanes.filter(l => l.athleteId)" size="small" border>
                  <el-table-column prop="lane" label="道" width="50" align="center" />
                  <el-table-column prop="athleteName" label="运动员" min-width="90" />
                  <el-table-column prop="className" label="班级" min-width="110" />
                  <el-table-column label="预赛成绩" width="180">
                    <template #default="{ row }">
                      <el-input v-model="prelimTimeMap[row.athleteId]" size="small" placeholder="如 12.34 / 1:02.5"
                        style="width:150px" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>

            <!-- 晋级名单 -->
            <div v-if="qualifiers.length" class="prelim-qualifiers">
              <div class="prelim-qualifiers-title">✅ 晋级名单（{{ qualifiers.length }} 人，将进入决赛）</div>
              <el-tag v-for="q in qualifiers" :key="q.athleteId" class="qualifier-tag" size="large">
                {{ q.prelimRank }}. {{ q.athleteName }}
                <span style="opacity:.7">（{{ q.className }}）</span>
                <span v-if="q.prelimTime" style="opacity:.6;margin-left:4px">{{ q.prelimTime }}</span>
              </el-tag>
            </div>
          </div>
        </el-card>
      </template>
    </div>

    <!-- 编排配置对话框 -->
    <el-dialog v-model="dialogVisible" title="编排配置" width="520px" :close-on-click-modal="false">
      <el-form :model="arrangeConfig" label-width="110px" label-position="left">
        <div class="config-section">
          <div class="section-title">
            <el-icon><Setting /></el-icon>
            基本参数
          </div>
          <el-form-item label="年级">
            <el-select v-model="arrangeConfig.grade" style="width: 100%" filterable>
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
          <el-form-item label="性别">
            <el-radio-group v-model="arrangeConfig.gender">
              <el-radio-button value="M">🏃 男子组</el-radio-button>
              <el-radio-button value="F">🏃‍♀️ 女子组</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="跑道数">
            <el-input-number v-model="arrangeConfig.lanes" :min="2" :max="10" />
          </el-form-item>
        </div>

        <el-divider />

        <div class="config-section">
          <div class="section-title">
            <el-icon><Switch /></el-icon>
            编排规则
          </div>
          <el-form-item label="同班尽量不同组">
            <el-switch v-model="arrangeConfig.ruleConfig.preferDiffHeat" active-color="#13ce66" />
          </el-form-item>
          <el-form-item label="同班尽量不同道">
            <el-switch v-model="arrangeConfig.ruleConfig.preferDiffLane" active-color="#13ce66" />
          </el-form-item>
          <el-form-item label="禁止同班同组">
            <el-switch v-model="arrangeConfig.ruleConfig.banSameClassSameLane" active-color="#ff4949" />
            <span class="rule-desc">严格禁止同一班级在同一组中出现</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="executeArrange" :loading="arranging" :icon="MagicStick">
          {{ arranging ? '编排中...' : '开始编排' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Trophy, MagicStick, View, Download, Delete, Grid, Setting, Switch, RefreshLeft, Timer } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'
import HeatGrid from './components/HeatGrid.vue'

// 年级列表：动态来自 系统设置·年级管理（严禁硬编码）
const gradeOptions = ref([])
const searchKeyword = ref('')
const eventTree = ref([])
const treeRef = ref(null)
const treeRefMobile = ref(null)
const selectedEvent = ref(null)
const arranged = ref(false)
const heats = ref([])
const statistics = ref(null)
const dialogVisible = ref(false)
const arranging = ref(false)
const previewData = ref(null)
const mobileDrawer = ref(false)

// ===== 预赛淘汰 =====
const roundsData = ref([])
const activeRound = ref('final')
const prelimGrade = ref('')
const prelimGender = ref('M')
const prelimWorking = ref(false)
const prelimAdvance = ref(8)
const qualifiers = ref([])
const prelimTimeMap = reactive({})
const prelimEnabled = computed(() =>
  selectedEvent.value && selectedEvent.value.isTrack !== false && selectedEvent.value.needHeats !== false
)
const prelimHeats = computed(() => {
  const r = roundsData.value.find(x => x.round === 'preliminary')
  return (r && r.heats) || []
})

const applyActiveRound = () => {
  const r = roundsData.value.find(x => x.round === activeRound.value)
  if (r) {
    heats.value = r.heats || []
    statistics.value = r.statistics || null
  }
}

/** 拉取编排结果并解析 rounds（兼容旧数据：单一赛次无 rounds） */
const refreshArrangement = async () => {
  try {
    const result = await request.get('/arrange/events/' + selectedEvent.value.id)
    roundsData.value = result.rounds || []
    if (result.rounds && result.rounds.length) {
      // 默认展示决赛
      const def = result.rounds.find(r => r.round === 'final') || result.rounds[result.rounds.length - 1]
      activeRound.value = def.round
      heats.value = def.heats || []
      statistics.value = def.statistics || null
      arranged.value = heats.value.length > 0
    } else if (result.heats && result.heats.length) {
      roundsData.value = [{ round: 'final', heats: result.heats, statistics: result.statistics || null }]
      heats.value = result.heats
      statistics.value = result.statistics || null
      arranged.value = true
    } else {
      roundsData.value = []
      heats.value = []
      statistics.value = null
      arranged.value = false
    }
  } catch (e) {
    roundsData.value = []
    heats.value = []
    statistics.value = null
    arranged.value = false
  }
}

const arrangeConfig = reactive({
  grade: '',
  gender: 'M',
  lanes: 8,
  ruleConfig: {
    preferDiffHeat: true,
    preferDiffLane: true,
    banSameClassSameLane: true
  }
})

// 搜索过滤
watch(searchKeyword, (val) => {
  treeRef.value?.filter(val)
})

onMounted(async () => {
  // 年级列表：来自系统设置·年级管理（不硬编码），默认选第一个
  try {
    const gs = await request.get('/system/grades')
    const list = Array.isArray(gs) ? gs : (gs?.records || [])
    gradeOptions.value = list.map(g => (g && g.name) || '').filter(Boolean)
    if (gradeOptions.value.length) {
      arrangeConfig.grade = arrangeConfig.grade || gradeOptions.value[0]
      prelimGrade.value = prelimGrade.value || gradeOptions.value[0]
    }
  } catch (e) {
    gradeOptions.value = []
  }

  try {
    const events = await request.get('/events')
    if (Array.isArray(events)) {
      const groups = {}
      events.forEach(e => {
        const cat = e.category || '其他'
        if (!groups[cat]) groups[cat] = []
        groups[cat].push({ id: e.id, label: e.name, event: e })
      })
      eventTree.value = Object.entries(groups).map(([cat, items]) => ({
        id: 'cat_' + cat,
        label: cat + ' (' + items.length + ')',
        children: items
      }))
    }
  } catch (e) {
    console.error('加载项目列表失败', e)
  }

  // 加载已保存的编排规则，作为默认编排参数
  try {
    const rule = await request.get('/system/arrange-rule')
    if (rule && rule.soft_constraints) {
      const s = rule.soft_constraints
      if (s.prefer_diff_heat !== undefined) arrangeConfig.ruleConfig.preferDiffHeat = !!s.prefer_diff_heat
      if (s.prefer_diff_lane !== undefined) arrangeConfig.ruleConfig.preferDiffLane = !!s.prefer_diff_lane
      if (s.ban_same_class_same_lane !== undefined) arrangeConfig.ruleConfig.banSameClassSameLane = !!s.ban_same_class_same_lane
    }
  } catch (e) {
    console.error('加载编排规则失败', e)
  }
})

const filterNode = (value, data) => data.label.includes(value)

const onEventClick = async (data) => {
  if (!data.event) return
  selectedEvent.value = {
    id: data.event.id,
    name: data.event.name,
    gender: data.event.genderLimit,
    isTrack: data.event.isTrack !== false && data.event.category !== '田赛',
    needHeats: data.event.needHeats !== false,
    advanceCount: data.event.advanceCount ?? 8
  }
  prelimAdvance.value = selectedEvent.value.advanceCount
  previewData.value = null
  qualifiers.value = []
  Object.keys(prelimTimeMap).forEach(k => delete prelimTimeMap[k])
  activeRound.value = 'final'
  await refreshArrangement()
}

const showArrangeDialog = () => {
  if (!selectedEvent.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  dialogVisible.value = true
}

const executeArrange = async () => {
  arranging.value = true
  try {
    const result = await request.post(
      '/arrange/events/' + selectedEvent.value.id,
      {
        grade: arrangeConfig.grade,
        gender: arrangeConfig.gender,
        lanes: arrangeConfig.lanes,
        ruleConfig: arrangeConfig.ruleConfig
      }
    )
    await refreshArrangement()
    previewData.value = null
    dialogVisible.value = false
    const timeInfo = result.executionTimeMs != null
      ? '，耗时 ' + result.executionTimeMs + 'ms'
      : ''
    ElMessage.success('编排完成！共 ' + (result.statistics?.totalHeats || 0) + ' 组' + timeInfo)
  } catch (e) {
    ElMessage.error('编排失败: ' + (e.response?.data?.message || e.message || '未知错误'))
  } finally {
    arranging.value = false
  }
}

const previewArrange = async () => {
  if (!selectedEvent.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  try {
    const result = await request.post('/arrange/preview', {
      eventId: selectedEvent.value.id,
      grade: arrangeConfig.grade,
      gender: arrangeConfig.gender,
      lanes: arrangeConfig.lanes
    })
    previewData.value = result
    ElMessage.success('预览生成成功（未保存）')
  } catch (e) {
    ElMessage.error('预览失败: ' + (e.response?.data?.message || e.message || '未知错误'))
  }
}

const clearArrange = async () => {
  try {
    await ElMessageBox.confirm('确定要清除编排结果吗？此操作不可恢复。', '确认清除', { type: 'warning' })
    await request.delete('/arrange/events/' + selectedEvent.value.id)
    roundsData.value = []
    heats.value = []
    statistics.value = null
    arranged.value = false
    previewData.value = null
    qualifiers.value = []
    ElMessage.success('编排已清除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('清除失败')
  }
}

const rollbackArrange = async () => {
  try {
    await ElMessageBox.confirm('确定要回滚到上一版本的编排结果吗？', '确认回滚', { type: 'warning' })
    await request.post('/arrange/events/' + selectedEvent.value.id + '/rollback')
    ElMessage.success('回滚成功')
    await refreshArrangement()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('回滚失败: ' + (e.response?.data?.message || e.message || '未知错误'))
  }
}

// ==================== 预赛淘汰动作 ====================
const generatePrelim = async () => {
  if (!selectedEvent.value) return
  prelimWorking.value = true
  try {
    await request.post('/arrange/events/' + selectedEvent.value.id + '/preliminary', {
      grade: prelimGrade.value,
      gender: prelimGender.value
    })
    await refreshArrangement()
    activeRound.value = 'preliminary'
    applyActiveRound()
    // 预赛成绩回填到输入框
    const items = []
    prelimHeats.value.forEach(h => (h.lanes || []).forEach(l => {
      if (l.athleteId && l.prelimTime) {
        items.push([l.athleteId, l.prelimTime])
      }
    }))
    Object.keys(prelimTimeMap).forEach(k => delete prelimTimeMap[k])
    items.forEach(([id, t]) => { prelimTimeMap[id] = t })
    qualifiers.value = []
    ElMessage.success('预赛编排完成，请在下方录入各组预赛成绩')
  } catch (e) {
    ElMessage.error('生成预赛失败: ' + (e.response?.data?.message || e.message || ''))
  } finally {
    prelimWorking.value = false
  }
}

const submitPrelimTimes = async () => {
  const items = []
  prelimHeats.value.forEach(h => (h.lanes || []).forEach(l => {
    const t = (prelimTimeMap[l.athleteId] || '').trim()
    if (l.athleteId && t) items.push({ athleteId: l.athleteId, time: t })
  }))
  if (!items.length) {
    ElMessage.warning('请先填写预赛成绩')
    return
  }
  prelimWorking.value = true
  try {
    await request.post('/arrange/events/' + selectedEvent.value.id + '/prelim-results', {
      grade: prelimGrade.value,
      gender: prelimGender.value,
      items
    })
    ElMessage.success(`已保存 ${items.length} 条预赛成绩，点击「③ 立即计算晋级」生成决赛`)
    await loadQualifiers(true)
  } catch (e) {
    ElMessage.error('保存预赛成绩失败: ' + (e.response?.data?.message || e.message || ''))
  } finally {
    prelimWorking.value = false
  }
}

const computeQualify = async () => {
  prelimWorking.value = true
  try {
    const res = await request.post('/arrange/events/' + selectedEvent.value.id + '/qualify', {
      grade: prelimGrade.value,
      gender: prelimGender.value,
      advanceCount: prelimAdvance.value
    })
    await refreshArrangement()
    qualifiers.value = res.qualifiers || []
    activeRound.value = 'final'
    applyActiveRound()
    ElMessage.success(`晋级计算完成：${res.participants || 0} 人参赛，前 ${res.qualifierCount || 0} 名晋级，决赛已自动排好`)
  } catch (e) {
    ElMessage.error('晋级计算失败: ' + (e.response?.data?.message || e.message || ''))
  } finally {
    prelimWorking.value = false
  }
}

const loadQualifiers = async (silent) => {
  try {
    const res = await request.get('/arrange/events/' + selectedEvent.value.id + '/qualifiers', {
      params: { grade: prelimGrade.value, gender: prelimGender.value }
    })
    qualifiers.value = Array.isArray(res) ? res : (res.records || [])
    if (!silent) ElMessage.success('晋级名单已加载')
  } catch (e) {
    qualifiers.value = []
  }
}

const exportSheet = async () => {
  if (!selectedEvent.value) { ElMessage.warning('请先选择项目'); return }
  try {
    await downloadApi('/arrange/events/' + selectedEvent.value.id + '/export', '编排道次.xlsx')
    ElMessage.success('导出成功')
  } catch (e) { ElMessage.error(e?.message || '导出失败，请重新登录后再试') }
}
</script>

<style scoped>
.arrange-page {
  display: flex;
  gap: 16px;
  height: calc(100vh - 140px);
  padding: 4px;
}

.left-panel {
  width: 260px;
  flex-shrink: 0;
  overflow-y: auto;
  border-radius: 12px;
}

.left-panel :deep(.el-card__header) {
  padding: 14px 16px;
  background: linear-gradient(135deg, #f5f7fa 0%, #e8ecf1 100%);
  border-radius: 12px 12px 0 0;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}

.right-panel {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-bottom: 24px;
}

.empty-card {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}

.empty-icon {
  font-size: 80px;
  opacity: 0.4;
}

.toolbar {
  border-radius: 12px;
  border-top: 3px solid #409eff;
  position: relative;
  z-index: 5; /* 永远高于下方各卡片的 header 背景，避免视觉粘连 */
}
.toolbar :deep(.el-card__header) {
  background: linear-gradient(135deg, #f5f7fa 0%, #e8ecf1 100%);
  border-radius: 12px 12px 0 0;
  padding: 12px 18px;
}
.toolbar-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}
.toolbar-header .th-ico { font-size: 16px; }
.toolbar-header .th-title { font-size: 14px; }
.toolbar-header .th-tip {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
  font-weight: 400;
}
@media (max-width: 760px) {
  .toolbar-header .th-tip { display: none; }
}

.toolbar :deep(.el-card__body) {
  padding: 16px 20px;
}

.toolbar-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.toolbar-right {
  display: flex;
  align-items: center;
}

.preview-card {
  border-radius: 12px;
  border: 2px dashed #e6a23c;
}

.preview-card :deep(.el-card__header) {
  background: linear-gradient(135deg, #fef3e2 0%, #fde2b3 100%);
  border-radius: 12px 12px 0 0;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #e6a23c;
}

.heat-grid-card {
  border-radius: 12px;
  position: relative;
  z-index: 1;
}

.heat-grid-card :deep(.el-card__header) {
  background: linear-gradient(135deg, #fafbfc 0%, #f1f4f8 100%);
  border-bottom: 1px solid #e4e7ed;
  border-radius: 12px 12px 0 0;
  padding: 12px 18px;
}

.grid-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #409eff;
  flex-wrap: wrap;
}

.grid-header .el-tag { vertical-align: middle; }
.grid-header .el-radio-group { margin-left: auto; }
@media (max-width: 900px) {
  .grid-header .el-radio-group { margin-left: 0; }
  .heat-grid-card :deep(.el-card__header), .prelim-card :deep(.el-card__header) { padding: 12px; }
}

/* 长项目名在工具条中不挤压按钮区 */
.toolbar-left .el-tag--large { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.heat-grid { padding: 8px 2px; }
.lane-athlete .athlete-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.heat-grid {
  padding: 8px 0;
}

.config-section {
  margin-bottom: 8px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.rule-desc {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

/* 滚动条美化 */
.right-panel::-webkit-scrollbar,
.left-panel::-webkit-scrollbar {
  width: 6px;
}
.right-panel::-webkit-scrollbar-thumb,
.left-panel::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 3px;
}

/* Mobile responsive */
.mobile-project-btn { display: none; }
@media (max-width: 768px) {
  .arrange-page {
    flex-direction: column;
    height: auto;
    min-height: calc(100vh - 140px);
  }
  .left-panel { display: none !important; }
  .mobile-project-btn { display: inline-flex !important; }
  .toolbar-content { flex-direction: column; align-items: flex-start !important; }
  .toolbar-right { width: 100%; }
  .toolbar-right .el-button-group { display: flex; flex-wrap: wrap; }
  .toolbar-right .el-button-group .el-button { flex: 1; min-width: 0; }
}

/* 预赛淘汰面板 */
.prelim-card {
  border-radius: 12px;
  border-top: 3px solid #e6a23c;
}
.prelim-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #b88230;
}
.prelim-body { display: flex; flex-direction: column; gap: 12px; }
.prelim-filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.prelim-advance-label { font-size: 13px; color: #606266; margin-left: 4px; }
.prelim-times { display: flex; flex-direction: column; gap: 10px; }
.prelim-heat-row { border: 1px solid #ebeef5; border-radius: 8px; padding: 6px 8px; }
.prelim-heat-label { font-size: 13px; font-weight: 600; color: #303133; margin: 2px 0 6px; }
.prelim-qualifiers {
  border: 1px solid #67c23a;
  background: #f0f9eb;
  border-radius: 8px;
  padding: 10px 12px;
}
.prelim-qualifiers-title { font-size: 13px; font-weight: 600; color: #67c23a; margin-bottom: 8px; }
.qualifier-tag { margin: 0 6px 6px 0; }
</style>
