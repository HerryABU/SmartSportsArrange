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
        <!-- 操作栏 -->
        <el-card class="toolbar" shadow="hover">
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
              <el-tag v-if="statistics" type="info" size="small">
                版本 {{ statistics.version }}
              </el-tag>
            </div>
          </template>
          <div class="heat-grid">
            <HeatGrid :heats="heats" :statistics="statistics" />
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
            <el-select v-model="arrangeConfig.grade" style="width: 100%">
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
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Trophy, MagicStick, View, Download, Delete, Grid, Setting, Switch, RefreshLeft } from '@element-plus/icons-vue'
import request from '@/utils/request'
import HeatGrid from './components/HeatGrid.vue'

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

const arrangeConfig = reactive({
  grade: '高一年级',
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
    gender: data.event.genderLimit
  }
  previewData.value = null

  try {
    const result = await request.get('/arrange/events/' + data.event.id)
    if (result && result.heats && result.heats.length > 0) {
      heats.value = result.heats || []
      statistics.value = result.statistics || null
      arranged.value = true
    } else {
      heats.value = []
      arranged.value = false
      statistics.value = null
    }
  } catch (e) {
    arranged.value = false
    heats.value = []
    statistics.value = null
  }
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
    heats.value = result.heats || []
    statistics.value = result.statistics || null
    arranged.value = true
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
    arranged.value = false
    heats.value = []
    statistics.value = null
    previewData.value = null
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
    onEventClick({ event: { id: selectedEvent.value.id, name: selectedEvent.value.name, genderLimit: selectedEvent.value.gender } })
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('回滚失败: ' + (e.response?.data?.message || e.message || '未知错误'))
  }
}

const exportSheet = () => {
  window.open('/api/arrange/events/' + selectedEvent.value.id + '/export', '_blank')
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
  gap: 12px;
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
}

.heat-grid-card :deep(.el-card__header) {
  background: linear-gradient(135deg, #e8f4fd 0%, #d0e8f7 100%);
  border-radius: 12px 12px 0 0;
}

.grid-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #409eff;
}

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
</style>
