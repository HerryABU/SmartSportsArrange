<template>
  <div>
    <!-- ============ 裁判本人视角 ============ -->
    <template v-if="isReferee">
      <div class="role-hero">
        <h2 class="role-hero-title">🧑‍⚖️ {{ me.refereeName || authStore.user?.realName || '我的执裁安排' }}</h2>
        <p class="role-hero-desc">
          你在本届运动会中被分配到的执裁任务（项目 / 年级 / 性别 / 赛次 / 组次）。
          如需调整，请联系体育老师或管理员在编排中微调。
        </p>
        <div class="role-hero-tags">
          <span class="role-hero-tag">裁判账号</span>
          <span class="role-hero-tag">执裁 {{ me.count || 0 }} 个组次</span>
          <span v-if="(me.specialties || []).length" class="role-hero-tag">专长 {{ me.specialties.join(' / ') }}</span>
        </div>
      </div>

      <el-alert
        v-if="loaded && me.linked === false"
        type="warning"
        show-icon
        :closable="false"
        title="尚未绑定裁判档案"
        description="你的登录账号还未关联到裁判花名册，因此暂无执裁安排。请联系管理员在「裁判管理」中为你开通账号或绑定档案。"
        style="margin-bottom:16px"
      />

      <div class="role-stats">
        <div class="role-stat">
          <div class="role-stat-num">{{ me.count || 0 }}</div>
          <div class="role-stat-label">我的执裁组次</div>
        </div>
        <div class="role-stat">
          <div class="role-stat-num">{{ myEvents.length }}</div>
          <div class="role-stat-label">涉及项目数</div>
        </div>
        <div class="role-stat">
          <div class="role-stat-num">{{ myToday.length }}</div>
          <div class="role-stat-label">含预赛/决赛场次</div>
        </div>
      </div>

      <div class="role-card">
        <div class="role-card-head">
          <span class="role-card-title">我的执裁明细</span>
          <el-button link type="primary" size="small" :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
        </div>
        <el-table v-if="myRows.length" :data="myRows" size="small" border stripe>
          <el-table-column label="项目" prop="eventName" min-width="140" />
          <el-table-column label="类别" prop="category" width="90" />
          <el-table-column label="年级" prop="grade" width="120" />
          <el-table-column label="性别" width="70" align="center">
            <template #default="{ row }">{{ row.gender === 'M' ? '男' : (row.gender === 'F' ? '女' : '-') }}</template>
          </el-table-column>
          <el-table-column label="赛次" width="90" align="center">
            <template #default="{ row }">{{ roundText(row.round) }}</template>
          </el-table-column>
          <el-table-column label="组次" prop="heat" width="70" align="center" />
        </el-table>
        <el-empty v-else description="暂无执裁安排（编排完成后自动分配）" />
      </div>
    </template>

    <!-- ============ 管理端（管理员 / 体育老师）视角：全体裁判安排 ============ -->
    <template v-else>
      <div class="role-hero">
        <h2 class="role-hero-title">🧑‍⚖️ 裁判执裁看板</h2>
        <p class="role-hero-desc">
          查看每位裁判被分配到的「项目 / 年级 / 性别 / 赛次 / 组次」。裁判由智能编排按项目的
          <b>组次裁判数量</b>自动分配（专长优先 + 负载均衡），管理端也可手工微调；裁判可凭账号登录查看本人安排。
        </p>
        <div class="role-hero-tags">
          <span class="role-hero-tag">按裁判聚合</span>
          <span class="role-hero-tag">组次明细</span>
          <span class="role-hero-tag">未分配可查</span>
        </div>
      </div>

      <div class="role-stats">
        <div class="role-stat">
          <div class="role-stat-num">{{ board.refereeCount || 0 }}</div>
          <div class="role-stat-label">裁判总数</div>
        </div>
        <div class="role-stat">
          <div class="role-stat-num">{{ board.totalAssignments || 0 }}</div>
          <div class="role-stat-label">分配组次总数</div>
        </div>
        <div class="role-stat">
          <div class="role-stat-num">{{ (board.idle || []).length }}</div>
          <div class="role-stat-label">未分配裁判</div>
          <div class="role-stat-sub">编排后可在此核对</div>
        </div>
        <div class="role-stat">
          <div class="role-stat-num">{{ current ? current.count : 0 }}</div>
          <div class="role-stat-label">当前裁判组次</div>
          <div class="role-stat-sub">{{ current ? current.refereeName : '请选择裁判' }}</div>
        </div>
      </div>

      <div class="role-card">
        <div class="role-card-head">
          <span class="role-card-title">执裁明细</span>
          <span class="role-card-extra">共 {{ (board.referees || []).length }} 名裁判已分配</span>
        </div>

        <el-form :inline="true" style="margin-bottom:12px">
          <el-form-item label="选择裁判">
            <el-select v-model="selectedId" filterable clearable placeholder="全部 / 按裁判查看" style="width:220px">
              <el-option v-for="r in board.referees" :key="r.refereeId" :label="r.refereeName + '（' + r.count + ' 组次）'" :value="r.refereeId" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="keyword" placeholder="项目 / 年级" clearable style="width:180px" />
          </el-form-item>
          <el-form-item>
            <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
          </el-form-item>
        </el-form>

        <el-table v-if="rows.length" :data="rows" size="small" border stripe>
          <el-table-column label="裁判" prop="refereeName" width="110" />
          <el-table-column label="项目" prop="eventName" min-width="130" />
          <el-table-column label="类别" prop="category" width="90" />
          <el-table-column label="年级" prop="grade" width="110" />
          <el-table-column label="性别" width="70" align="center">
            <template #default="{ row }">{{ row.gender === 'M' ? '男' : (row.gender === 'F' ? '女' : '-') }}</template>
          </el-table-column>
          <el-table-column label="赛次" width="80" align="center">
            <template #default="{ row }">{{ roundText(row.round) }}</template>
          </el-table-column>
          <el-table-column label="组次" prop="heat" width="70" align="center" />
        </el-table>
        <el-empty v-else description="暂无裁判分配（请先在「道次编排」执行编排）" />
      </div>

      <div class="role-card">
        <div class="role-card-head">
          <span class="role-card-title">未分配裁判</span>
          <span class="role-card-extra">{{ (board.idle || []).length }} 人</span>
        </div>
        <div v-if="(board.idle || []).length" class="role-quick">
          <div v-for="r in board.idle" :key="r.refereeId" class="role-quick-item" style="cursor:default">
            <span class="role-quick-ico">🥇</span>
            <div>
              <div class="role-quick-title">{{ r.refereeName }}</div>
              <div class="role-quick-desc">{{ (r.specialties || []).join(' / ') || '暂无专长' }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="全部裁判均已有分配" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const isReferee = computed(() => !!authStore.isReferee)

const loading = ref(false)
const loaded = ref(false)
const keyword = ref('')
const selectedId = ref(null)

// 管理端：全体
const board = ref({ referees: [], idle: [], refereeCount: 0, totalAssignments: 0 })
// 裁判端：本人
const me = ref({ linked: null, count: 0, assignments: [], specialties: [] })

const flatten = computed(() => {
  const out = []
  for (const r of (board.value.referees || [])) {
    for (const a of (r.assignments || [])) {
      out.push({ refereeName: r.refereeName, refereeId: r.refereeId, ...a })
    }
  }
  return out
})

const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return flatten.value.filter(x => {
    if (selectedId.value != null && x.refereeId !== selectedId.value) return false
    if (!kw) return true
    return (x.eventName || '').toLowerCase().includes(kw)
        || (x.grade || '').toLowerCase().includes(kw)
  })
})

const current = computed(() =>
  selectedId.value == null
    ? null
    : ((board.value.referees || []).find(r => r.refereeId === selectedId.value) || null)
)

// 裁判端派生
const myRows = computed(() => me.value.assignments || [])
const myEvents = computed(() => new Set((me.value.assignments || []).map(a => a.eventId)).size)
const myToday = computed(() => new Set((me.value.assignments || []).map(a => (a.round || 'final') + '|' + a.heat)).size)

function roundText(r) {
  if (r === 'preliminary') return '预赛'
  if (r === 'single') return '直接决赛'
  return '决赛'
}

const load = async () => {
  loading.value = true
  try {
    if (isReferee.value) {
      me.value = (await request.get('/referee/me')) || { linked: false, count: 0, assignments: [] }
    } else {
      board.value = (await request.get('/arrange/referee-board'))
        || { referees: [], idle: [], refereeCount: 0, totalAssignments: 0 }
    }
    loaded.value = true
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '加载执裁安排失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
