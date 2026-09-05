<template>
  <div class="reports-page" v-loading="loading">
    <!-- 页面头（工作流 ③ 统计） -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">📋</span>
        <div>
          <h3 class="pg-title">秩序册 · 成绩册 · 统计报表</h3>
          <p class="pg-desc">一键生成并导出秩序册（项目按径赛/田赛分组 + 班级花名册）与成绩册（分项目排名 + 破纪录汇总），另提供报名与成绩统计看板</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#f5f3ff;color:#7c3aed">③ 统计排名</span>
      </div>
    </div>
    <el-tabs v-model="activeTab">
      <!-- Order Book -->
      <el-tab-pane label="秩序册" name="orderBook">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>秩序册</span>
              <div>
                <el-select v-model="obFilter.grade" placeholder="年级筛选" clearable style="width: 140px; margin-right: 8px">
                  <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
                </el-select>
                <el-button type="primary" @click="generateOrderBook">
                  <el-icon><Document /></el-icon>
                  生成秩序册
                </el-button>
                <el-button type="success" :disabled="!orderBookContent" @click="exportOrderBook">
                  <el-icon><Download /></el-icon>
                  导出Excel
                </el-button>
              </div>
            </div>
          </template>

          <div v-if="orderBookContent" class="report-content">
            <div class="report-header">
              <h2>运动会秩序册</h2>
              <p>{{ appStore.meetName }}</p>
            </div>

            <div v-for="section in orderBookContent" :key="section.title" class="report-section">
              <h3>{{ section.title }}</h3>
              <el-table :data="section.items" border stripe size="small">
                <el-table-column
                  v-for="col in section.columns"
                  :key="col.prop"
                  :prop="col.prop"
                  :label="col.label"
                  :width="col.width"
                />
              </el-table>
            </div>
          </div>

          <el-empty v-else description="请选择筛选条件后生成秩序册" :image-size="100" />
        </el-card>
      </el-tab-pane>

      <!-- Result Book -->
      <el-tab-pane label="成绩册" name="resultBook">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>成绩册</span>
              <div>
                <el-select v-model="rbFilter.grade" placeholder="年级筛选" clearable style="width: 140px; margin-right: 8px">
                  <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
                </el-select>
                <el-select v-model="rbFilter.eventType" placeholder="项目类型" clearable style="width: 120px; margin-right: 8px">
                  <el-option label="径赛" value="径赛" />
                  <el-option label="田赛" value="田赛" />
                </el-select>
                <el-button type="primary" @click="generateResultBook">
                  <el-icon><TrendCharts /></el-icon>
                  生成成绩册
                </el-button>
                <el-button type="success" :disabled="!resultBookContent" @click="exportResultBook">
                  <el-icon><Download /></el-icon>
                  导出Excel
                </el-button>
              </div>
            </div>
          </template>

          <div v-if="resultBookContent" class="report-content">
            <div class="report-header">
              <h2>运动会成绩册</h2>
              <p>{{ appStore.meetName }}</p>
            </div>

            <div v-for="section in resultBookContent" :key="section.eventName" class="report-section">
              <h3>{{ section.eventName }} ({{ section.gender }} - {{ section.gradeGroup }})</h3>
              <el-table :data="section.results" border stripe size="small">
                <el-table-column type="index" label="排名" width="60" />
                <el-table-column prop="athleteName" label="姓名" />
                <el-table-column prop="className" label="班级" />
                <el-table-column prop="score" label="成绩" />
                <el-table-column prop="points" label="积分" width="70" />
              </el-table>
            </div>
          </div>

          <el-empty v-else description="请选择筛选条件后生成成绩册" :image-size="100" />
        </el-card>
      </el-tab-pane>

      <!-- Statistics -->
      <el-tab-pane label="统计报表" name="statistics">
        <el-card shadow="never">
          <el-row :gutter="16" class="stat-cards">
            <el-col :span="6" v-for="stat in statCards" :key="stat.label">
              <el-card shadow="hover" class="stat-card-item">
                <div class="stat-value">{{ stat.value }}</div>
                <div class="stat-label">{{ stat.label }}</div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="16" style="margin-top: 16px">
            <el-col :span="12">
              <el-card shadow="never">
                <template #header><span>各年级参赛人数</span></template>
                <el-table :data="gradeStats" border size="small">
                  <el-table-column prop="grade" label="年级" />
                  <el-table-column prop="count" label="参赛人数" />
                  <el-table-column prop="percentage" label="占比">
                    <template #default="{ row }">
                      <el-progress :percentage="row.percentage" :stroke-width="16" :text-inside="true" />
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never">
                <template #header><span>各项目报名统计</span></template>
                <el-table :data="eventStats" border size="small">
                  <el-table-column prop="eventName" label="项目" />
                  <el-table-column prop="count" label="报名人数" />
                  <el-table-column prop="capacity" label="满额率">
                    <template #default="{ row }">
                      <el-progress
                        :percentage="row.capacity"
                        :stroke-width="16"
                        :text-inside="true"
                        :status="row.capacity >= 100 ? 'success' : undefined"
                      />
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const loading = ref(false)
const activeTab = ref('orderBook')

const gradeOptions = ref([])

const obFilter = reactive({ grade: '' })
const rbFilter = reactive({ grade: '', eventType: '' })

const orderBookContent = ref(null)
const resultBookContent = ref(null)

const statCards = ref([
  { label: '班级总数', value: 0 },
  { label: '运动员总数', value: 0 },
  { label: '项目总数', value: 0 },
  { label: '报名总数', value: 0 }
])

const gradeStats = ref([])
const eventStats = ref([])

async function generateOrderBook() {
  loading.value = true
  try {
    const res = await request.post('/statistics/order-book', { grade: obFilter.grade || null })
    const data = res || {}
    // 后端返回统一 sections（竞赛日程 / 径赛·田赛项目 / 参赛班级 / 各项目道次名单），直接渲染
    if (data.sections && data.sections.length) {
      orderBookContent.value = data.sections
    } else {
      // 兼容兜底
      orderBookContent.value = [
        { title: '径赛项目', columns: [
          { prop: 'name', label: '项目名称' },
          { prop: 'code', label: '编码' },
          { prop: 'genderLimit', label: '性别' },
          { prop: 'arrangedCount', label: '已编排人数' }
        ], items: (data.events?.径赛 || []) },
        { title: '田赛项目', columns: [
          { prop: 'name', label: '项目名称' },
          { prop: 'code', label: '编码' },
          { prop: 'genderLimit', label: '性别' },
          { prop: 'arrangedCount', label: '已编排人数' }
        ], items: (data.events?.田赛 || []) },
        { title: '参赛班级', columns: [
          { prop: 'name', label: '班级名称' },
          { prop: 'grade', label: '年级' },
          { prop: 'teacherName', label: '班主任' },
          { prop: 'studentCount', label: '人数' }
        ], items: (data.classes || []) }
      ]
    }
    ElMessage.success('秩序册生成成功' + (orderBookContent.value.length ? `（${orderBookContent.value.length} 个分册）` : ''))
  } catch (e) {
    console.error('生成秩序册失败', e)
  } finally {
    loading.value = false
  }
}

async function exportOrderBook() {
  try { await downloadApi('/excel/export/order-book', '秩序册.xlsx'); ElMessage.success('导出成功') }
  catch (e) { ElMessage.error(e?.message || '导出失败，请重新登录后再试') }
}

async function generateResultBook() {
  loading.value = true
  try {
    const params = {}
    if (rbFilter.grade) params.grade = rbFilter.grade
    if (rbFilter.eventType) params.eventType = rbFilter.eventType
    const res = await request.post('/statistics/result-book', params)
    const data = res || {}
    resultBookContent.value = (data.eventResults || []).map(er => ({
      eventName: er.eventName,
      gender: er.category || '',
      gradeGroup: er.grade || '',
      results: (er.rankings || [])
    }))
    ElMessage.success('成绩册生成成功')
  } catch (e) {
    console.error('生成成绩册失败', e)
  } finally {
    loading.value = false
  }
}

async function exportResultBook() {
  try { await downloadApi('/excel/export/result-book', '成绩册.xlsx'); ElMessage.success('导出成功') }
  catch (e) { ElMessage.error(e?.message || '导出失败，请重新登录后再试') }
}

async function fetchStatistics() {
  loading.value = true
  try {
    const [regStats, scoreStats] = await Promise.all([
      request.get('/statistics/registration'),
      request.get('/statistics/score')
    ])
    if (regStats) {
      statCards.value = [
        { label: '报名总数', value: regStats.total || 0 },
        { label: '已通过', value: regStats.byStatus?.approved || 0 },
        { label: '待审核', value: regStats.byStatus?.pending || 0 },
        { label: '成绩记录', value: scoreStats?.totalResults || 0 }
      ]
    }
    // 按年级统计参赛人数（含占比）
    if (regStats?.byGrade) {
      const rows = Object.entries(regStats.byGrade).map(([grade, count]) => ({
        grade, count: Number(count) || 0
      }))
      const sum = rows.reduce((acc, r) => acc + r.count, 0)
      gradeStats.value = rows.map(r => ({
        grade: r.grade,
        count: r.count,
        percentage: sum > 0 ? Math.round(r.count / sum * 100) : 0
      }))
    }
    // 按项目统计（含满额率）
    if (regStats?.byEvent) {
      eventStats.value = Object.entries(regStats.byEvent).map(([name, counts]) => {
        const total = Number(counts.total) || 0
        const cap = Number(counts.capacity) || 0
        return {
          eventName: name,
          count: total,
          approved: Number(counts.approved) || 0,
          capacity: cap > 0 ? Math.min(100, Math.round(total / cap * 100)) : 0
        }
      })
    }
  } catch (e) {
    console.error('获取统计数据失败', e)
  } finally {
    loading.value = false
  }
}

// 当切换到统计报表tab时加载数据
watch(activeTab, (tab) => {
  if (tab === 'statistics') {
    fetchStatistics()
  }
}, { immediate: true })

async function loadGradeOptions() {
  try {
    const res = await request.get('/system/grades')
    const list = Array.isArray(res) ? res : (res?.records || [])
    gradeOptions.value = list.map(g => (g && g.name) || '').filter(Boolean)
  } catch {
    gradeOptions.value = []
  }
}

onMounted(() => { loadGradeOptions() })
</script>

<style scoped>
.reports-page {
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.report-content {
  padding: 16px 0;
}

.report-header {
  text-align: center;
  margin-bottom: 24px;
}

.report-header h2 {
  font-size: 22px;
  color: #303133;
  margin-bottom: 8px;
}

.report-header p {
  font-size: 14px;
  color: #909399;
}

.report-section {
  margin-bottom: 24px;
}

.report-section h3 {
  font-size: 16px;
  color: #409EFF;
  margin-bottom: 12px;
  padding-left: 8px;
  border-left: 3px solid #409EFF;
}

.stat-cards .stat-card-item {
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409EFF;
  margin-bottom: 8px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}
@media(max-width:768px) {
  .card-header { flex-direction: column; align-items: flex-start; gap: 8px; }
  .stat-value { font-size: 24px; }
}
</style>
