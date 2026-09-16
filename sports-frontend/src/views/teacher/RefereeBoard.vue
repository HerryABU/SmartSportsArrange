<template>
  <div class="referee-board">
    <div class="pg-head">
      <div>
        <h2 class="pg-title">
          <el-icon><Medal /></el-icon> 裁判工作安排
        </h2>
        <p class="pg-desc">
          按裁判聚合其在各项目 / 年级 / 性别 / 赛次 / 组次的编排分配（裁判为被编排的人力资源，独立于登录账号）；
          智能编排按「组次裁判数量」自动分配后在此汇总。
        </p>
      </div>
      <div class="pg-actions">
        <el-input v-model="keyword" placeholder="搜索裁判姓名/专长" clearable style="width: 220px" :prefix-icon="Search" />
        <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-num">{{ board.refereeCount || 0 }}</div>
        <div class="stat-label">裁判总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ board.totalAssignments || 0 }}</div>
        <div class="stat-label">分配组次总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ (board.idle || []).length }}</div>
        <div class="stat-label">未分配裁判</div>
      </div>
    </div>

    <el-card shadow="never" class="card">
      <template #header>
        <span class="card-title">已分配裁判（{{ filteredAssigned.length }}）</span>
      </template>
      <el-table v-if="filteredAssigned.length" :data="filteredAssigned" size="small" row-key="refereeId">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="assign-wrap">
              <el-table :data="row.assignments" size="small" border>
                <el-table-column label="项目" prop="eventName" min-width="120" />
                <el-table-column label="类别" prop="category" width="90" />
                <el-table-column label="年级" prop="grade" width="110" />
                <el-table-column label="性别" width="70">
                  <template #default="{ row: a }">{{ a.gender === 'M' ? '男' : (a.gender === 'F' ? '女' : '-') }}</template>
                </el-table-column>
                <el-table-column label="赛次" width="70">
                  <template #default="{ row: a }">{{ a.round === 'preliminary' ? '预赛' : (a.round === 'single' ? '直接决赛' : '决赛') }}</template>
                </el-table-column>
                <el-table-column label="组次" prop="heat" width="70" align="center" />
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="裁判" prop="refereeName" width="120" />
        <el-table-column label="电话" prop="phone" width="140" />
        <el-table-column label="专长项目">
          <template #default="{ row }">
            <el-tag v-for="s in row.specialties" :key="s" size="small" effect="plain" class="spec-tag">{{ s }}</el-tag>
            <span v-if="!row.specialties || !row.specialties.length" class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="分配组次" prop="count" width="90" align="center" />
      </el-table>
      <el-empty v-else description="暂无裁判分配（请先在智能编排中执行编排）" />
    </el-card>

    <el-card shadow="never" class="card">
      <template #header>
        <span class="card-title">未分配裁判（{{ filteredIdle.length }}）</span>
      </template>
      <el-table v-if="filteredIdle.length" :data="filteredIdle" size="small">
        <el-table-column label="裁判" prop="refereeName" width="140" />
        <el-table-column label="电话" prop="phone" width="150" />
        <el-table-column label="专长项目">
          <template #default="{ row }">
            <el-tag v-for="s in row.specialties" :key="s" size="small" effect="plain" class="spec-tag">{{ s }}</el-tag>
            <span v-if="!row.specialties || !row.specialties.length" class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="全部裁判均已有分配" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Medal, Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const keyword = ref('')
const board = ref({ referees: [], idle: [], refereeCount: 0, totalAssignments: 0 })

const match = (r) => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return true
  const name = (r.refereeName || '').toLowerCase()
  const specs = (r.specialties || []).join(',').toLowerCase()
  return name.includes(kw) || specs.includes(kw)
}
const filteredAssigned = computed(() => (board.value.referees || []).filter(match))
const filteredIdle = computed(() => (board.value.idle || []).filter(match))

const load = async () => {
  loading.value = true
  try {
    const r = await request.get('/arrange/referee-board')
    board.value = r || { referees: [], idle: [], refereeCount: 0, totalAssignments: 0 }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '加载裁判安排失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.pg-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.pg-title { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: 20px; font-weight: 700; color: #303133; }
.pg-desc { margin: 0; font-size: 13px; color: #909399; max-width: 720px; line-height: 1.6; }
.pg-actions { display: flex; align-items: center; gap: 10px; }

.stat-row { display: flex; gap: 14px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-card {
  flex: 1 1 160px; background: linear-gradient(135deg, #f0f7ff 0%, #e8f2fc 100%);
  border: 1px solid #d0e4f7; border-radius: 12px; padding: 14px 18px;
}
.stat-num { font-size: 26px; font-weight: 700; color: #1d6fc4; }
.stat-label { font-size: 12px; color: #909399; margin-top: 2px; }

.card { margin-bottom: 16px; }
.card-title { font-weight: 600; color: #303133; }
.assign-wrap { padding: 6px 16px 10px; background: #fafbfc; }
.spec-tag { margin: 0 6px 6px 0; }
.muted { color: #c0c4cc; }
</style>
