<template>
  <div class="ct-schedule" v-loading="loading">
    <!-- 页面头 -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🗓️</span>
        <div>
          <h3 class="pg-title">本班赛程（道次表）</h3>
          <p class="pg-desc">已通过报名的运动员编排结果 —— 分组 / 道次实时同步；具体比赛时段以体育老师发布的秩序册为准</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">共 {{ totalRows }} 条安排</span>
      </div>
    </div>

    <!-- 概览卡片 -->
    <div v-if="grouped.length" class="ov-grid">
      <div class="ov-card" v-for="g in grouped.slice(0, 6)" :key="g.eventName">
        <div class="ov-head">
          <span class="ov-name">{{ g.eventName }}</span>
          <el-tag size="small" :type="g.eventType === '径赛' ? 'danger' : 'warning'" effect="light">
            {{ g.eventType }}
          </el-tag>
        </div>
        <div class="ov-sub">{{ g.gender }} · {{ g.rows.length }} 人次</div>
        <div class="ov-body">
          <div class="ov-person" v-for="(r, i) in g.rows" :key="i">
            <span class="ov-athlete">🏃 {{ r.athleteName }}</span>
            <span class="ov-meta">第{{ r.heat }}组 · {{ r.laneNumber }}道</span>
          </div>
        </div>
      </div>
      <div v-if="grouped.length > 6" class="more-tip">…还有 {{ grouped.length - 6 }} 个项目，详见下方完整列表</div>
    </div>

    <!-- 完整明细 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>📋 全部赛程明细</span>
          <el-tag type="info" effect="plain" round>{{ totalRows }} 条安排</el-tag>
        </div>
      </template>
      <el-table :data="tableData" border stripe max-height="560">
        <el-table-column label="项目" min-width="150">
          <template #default="{ row }">
            <span v-if="row.eventType === '径赛'" class="type-dot track"></span>
            <span v-else class="type-dot field"></span>
            {{ row.eventName }}
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.eventType === '径赛' ? 'danger' : 'warning'" effect="plain">
              {{ row.eventType || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="组别" width="80" align="center">
          <template #default="{ row }">{{ genderLabel(row.gender) }}</template>
        </el-table-column>
        <el-table-column prop="athleteName" label="参赛运动员" min-width="110" />
        <el-table-column label="组次" width="80" align="center">
          <template #default="{ row }">第 {{ row.heat }} 组</template>
        </el-table-column>
        <el-table-column label="道次" width="70" align="center">
          <template #default="{ row }">
            <span class="lane-badge">{{ row.laneNumber || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tableData.length" description="暂无本班赛程——请先完成报名并由体育老师编排" :image-size="90" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const tableData = ref([])

const totalRows = computed(() => tableData.value.length)
const grouped = computed(() => {
  const map = {}
  tableData.value.forEach(r => {
    if (!map[r.eventName]) map[r.eventName] = { eventName: r.eventName, eventType: r.eventType, gender: r.gender, rows: [] }
    map[r.eventName].rows.push(r)
  })
  return Object.values(map)
})

function genderLabel(g) {
  const t = String(g || '').trim()
  if (['M', '男', '男子组'].includes(t)) return '男子'
  if (['F', '女', '女子组'].includes(t)) return '女子'
  return t || '混合'
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/schedule')
    tableData.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.ct-schedule { display: flex; flex-direction: column; gap: 14px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.ov-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.ov-card {
  background: linear-gradient(160deg, rgba(59,130,246,.06), rgba(99,102,241,.04));
  border: 1px solid #e2e8f0; border-radius: 14px; padding: 12px 14px;
  transition: all .25s;
}
.ov-card:hover { transform: translateY(-2px); box-shadow: 0 8px 18px rgba(15,23,42,.08); }
.ov-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.ov-name { font-weight: 700; color: #0f172a; }
.ov-sub { font-size: 12px; color: #64748b; margin: 2px 0 8px; }
.ov-body { display: flex; flex-direction: column; gap: 4px; max-height: 96px; overflow-y: auto; }
.ov-person {
  display: flex; justify-content: space-between; font-size: 12px;
  background: #fff; border-radius: 8px; padding: 3px 8px;
}
.ov-athlete { font-weight: 600; }
.ov-meta { color: #64748b; }
.more-tip { font-size: 12px; color: #94a3b8; grid-column: 1 / -1; text-align: center; }
.type-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.type-dot.track { background: #f43f5e; } .type-dot.field { background: #f59e0b; }
.lane-badge {
  display: inline-flex; min-width: 26px; height: 22px; align-items: center; justify-content: center;
  border-radius: 8px; background: #eff6ff; color: #2563eb; font-weight: 700; font-size: 12px;
}
@media (max-width: 900px) { .ov-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 640px) { .ov-grid { grid-template-columns: 1fr; } }
</style>
