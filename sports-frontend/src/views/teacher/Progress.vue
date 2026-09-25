<template>
  <div class="progress-page">
    <div class="page-head">
      <div>
        <h2>跨届进步榜</h2>
        <p class="sub">同一学生跨多届同一项目的名次进步（最新两届对比：进步 = 上一届名次 − 本届名次，正值=名次提前）。</p>
      </div>
    </div>

    <el-card shadow="never" style="margin-bottom:14px">
      <el-form :inline="true">
        <el-form-item label="统计口径">
          <el-radio-group v-model="mode">
            <el-radio value="event">指定项目</el-radio>
            <el-radio value="all">全部项目汇总</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="mode==='event'" label="项目">
          <el-select v-model="eventId" filterable placeholder="选择项目" style="width:220px" clearable>
            <el-option v-for="e in events" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="条数">
          <el-input-number v-model="limit" :min="0" :max="200" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="rows" border stripe empty-text="暂无跨届成绩（至少需同一学生在两届均有有效名次）">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="athleteName" label="姓名" width="100" />
        <el-table-column prop="className" label="班级" min-width="120" />
        <el-table-column prop="grade" label="年级" width="80" align="center" />
        <template v-if="mode==='event'">
          <el-table-column prop="prevMeet" label="上一届" min-width="160" />
          <el-table-column prop="prevRank" label="上届名次" width="90" align="center" />
          <el-table-column prop="curMeet" label="本届" min-width="160" />
          <el-table-column prop="curRank" label="本届名次" width="90" align="center" />
        </template>
        <template v-else>
          <el-table-column prop="events" label="覆盖项目数" width="100" align="center" />
        </template>
        <el-table-column label="进步" width="100" align="center" prop="imp">
          <template #header v-if="mode==='event'">名次进步</template>
          <template #header v-else>综合进步</template>
          <template #default="{ row }">
            <el-tag :type="row.rankImprovement>0?'success':(row.rankImprovement<0?'danger':'info')" effect="light">
              {{ row.rankImprovement>0?('+'+row.rankImprovement):row.rankImprovement }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const mode = ref('event')
const eventId = ref(null)
const limit = ref(50)
const events = ref([])
const rows = ref([])
const loading = ref(false)

function mapRows(data) {
  const r = data?.ranked || []
  return r.map(x => ({
    athleteName: x.athleteName, className: x.className, grade: x.grade,
    prevMeet: x.prevMeet + '（' + x.prevYear + '）' + ' 第' + x.prevRank + '名',
    prevRank: x.prevRank,
    curMeet: x.curMeet + '（' + x.curYear + '）' + ' 第' + x.curRank + '名',
    curRank: x.curRank,
    rankImprovement: x.rankImprovement,
    events: x.events
  }))
}
async function load() {
  loading.value = true
  try {
    const params = { limit: limit.value }
    if (mode.value === 'event') params.eventId = eventId.value || ''
    const data = await request.get('/statistics/progress', { params })
    rows.value = mapRows(data)
  } catch (e) {} finally { loading.value = false }
}
onMounted(async () => {
  try { events.value = await request.get('/events') } catch (e) {}
  await load()
})
</script>

<style scoped>
.progress-page { max-width: 1100px; margin: 0 auto; }
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 20px; color: var(--text-primary); }
.sub { margin: 0; font-size: 13px; color: var(--text-secondary); }
</style>
