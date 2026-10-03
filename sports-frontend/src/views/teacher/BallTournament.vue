<template>
  <div class="ball-page">
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-header">
          <span class="title">球类赛程编排</span>
          <span class="sub">赛制可由 AI 决策（format=auto），结构由规则引擎生成</span>
        </div>
      </template>

      <el-form :model="form" label-width="120px" class="form">
        <el-form-item label="参赛队伍">
          <div class="teams">
            <div v-for="(t, i) in form.teams" :key="i" class="team-row">
              <el-input v-model="t.name" placeholder="队伍名（如 高三3班）" style="width: 200px" />
              <el-input-number v-model="t.strength" :min="0" :max="1" :step="0.05"
                               :precision="2" placeholder="实力 0~1" style="width: 130px" />
              <el-input v-model="t.unit" placeholder="所属单位（班）" style="width: 160px" />
              <el-input v-model="t.venuePref" placeholder="场地偏好" style="width: 110px" />
              <el-button type="danger" link @click="removeTeam(i)">删除</el-button>
            </div>
            <el-button size="small" @click="addTeam">+ 添加队伍</el-button>
            <span class="hint">实力用于「强队不相遇」的种子排序；缺省按 0.5 处理</span>
          </div>
        </el-form-item>

        <el-form-item label="赛制">
          <el-radio-group v-model="form.format">
            <el-radio-button label="auto">AI 决策</el-radio-button>
            <el-radio-button label="group">小组赛</el-radio-button>
            <el-radio-button label="round_robin">循环赛</el-radio-button>
            <el-radio-button label="knockout">淘汰赛</el-radio-button>
            <el-radio-button label="hybrid">混合</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="时间目标">
          <el-select v-model="form.daysLimit" style="width: 260px">
            <el-option :value="-1" label="尽可能压缩工期（-1）" />
            <el-option :value="0" label="不限制总天数（0）" />
            <el-option :value="2" label="必须在 2 天内（2）" />
            <el-option :value="3" label="必须在 3 天内（3）" />
            <el-option :value="4" label="必须在 4 天内（4）" />
          </el-select>
          <span class="hint">与模型契约一致：>=1 硬约束 / 0 不限 / -1 最小化</span>
        </el-form-item>

        <el-form-item label="其它参数">
          <el-input-number v-model="form.groups" :min="2" :max="8" placeholder="小组数" style="width: 120px" />
          <el-input-number v-model="form.advancePerGroup" :min="1" :max="4" placeholder="每组晋级" style="width: 130px" />
          <el-input-number v-model="form.minutesPerMatch" :min="10" :max="240" :step="10" placeholder="单场分钟" style="width: 130px" />
          <el-checkbox v-model="form.doubleLeg" style="margin-left: 16px">双回合</el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">生成赛程</el-button>
          <el-button :disabled="!result" @click="runResecond">淘汰赛二次编排</el-button>
          <el-button v-if="result" @click="exportCsv">导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="advice && advice.degraded" type="warning" show-icon :closable="false"
              class="mb" :title="'AI 不可用，已回退默认赛制' + (advice.reason ? '：' + advice.reason : '')" />

    <el-card v-if="advice && advice.recommendedFormat" shadow="never" class="mb">
      <template #header><span class="title">AI 决策</span></template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="推荐赛制">
          <el-tag type="success">{{ advice.recommendedFormat }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="预计天数">{{ advice.daysEstimate ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="总场次">{{ result?.matchCount ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="赛制打分" :span="3">
          <el-tag v-for="(v, k) in advice.formatLogits" :key="k" class="tag-item" type="info">
            {{ k }}: {{ v }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="种子顺序" :span="3">
          <span class="seed">{{ (advice.seedOrder || []).join(' → ') }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="模型关注的约束" :span="3">
          <el-tag v-for="(v, k) in topTasks(advice.taskWeights)" :key="k" class="tag-item">
            {{ k }} {{ v }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="result" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">对阵表（{{ result.format }}）</span>
          <span class="sub">{{ result.matchCount }} 场 / {{ result.rounds || '-' }} 轮</span>
        </div>
      </template>
      <el-table :data="pagedMatches" size="small" border stripe height="420">
        <el-table-column prop="round" label="轮次" width="70" />
        <el-table-column prop="group" label="小组" width="80" />
        <el-table-column prop="home" label="主队" />
        <el-table-column prop="away" label="客队" />
        <el-table-column prop="leg" label="回合" width="60" />
        <el-table-column label="操作" width="90">
          <template #default="s">
            <el-button link type="primary" @click="markWinner(s.row)">标记胜方</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="result.matches.length > 20" v-model:current-page="page"
                     :page-size="20" :total="result.matches.length" layout="total, prev, pager, next"
                     style="margin-top: 10px; justify-content: flex-end" />
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const loading = ref(false)
const result = ref(null)
const advice = ref(null)
const page = ref(1)

const form = reactive({
  teams: [
    { name: '高三1班', strength: 0.9, unit: '高三1班', venuePref: 'A' },
    { name: '高三2班', strength: 0.75, unit: '高三2班', venuePref: 'A' },
    { name: '高二1班', strength: 0.6, unit: '高二1班', venuePref: 'B' },
    { name: '高二2班', strength: 0.45, unit: '高二2班', venuePref: 'B' }
  ],
  format: 'auto',
  groups: 2,
  advancePerGroup: 2,
  doubleLeg: false,
  minutesPerMatch: 40,
  daysLimit: 0
})

const pagedMatches = computed(() => {
  const ms = result.value?.matches || []
  return ms.slice((page.value - 1) * 20, page.value * 20)
})

function addTeam() {
  form.teams.push({ name: '', strength: 0.5, unit: '', venuePref: 'A' })
}

function removeTeam(i) {
  form.teams.splice(i, 1)
}

function topTasks(taskWeights) {
  if (!taskWeights) return []
  return Object.entries(taskWeights).sort((a, b) => b[1] - a[1]).slice(0, 5)
}

async function run() {
  if (!form.teams.length || form.teams.some(t => !t.name)) {
    ElMessage.warning('请先填好队伍名')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/ball/arrange', {
      teams: form.teams.map(t => ({
        name: t.name,
        strength: t.strength ?? 0.5,
        unit: t.unit || '',
        venuePref: t.venuePref || 'A'
      })),
      format: form.format,
      groups: form.groups,
      advancePerGroup: form.advancePerGroup,
      doubleLeg: form.doubleLeg,
      minutesPerMatch: form.minutesPerMatch,
      daysLimit: form.daysLimit,
      ai: true
    })
    const data = res.data || res
    result.value = data
    advice.value = data.aiAdvice || null
    page.value = 1
    ElMessage.success(`已生成 ${data.matchCount} 场（${data.format}）`)
  } catch (e) {
    ElMessage.error('生成失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function runResecond() {
  try {
    const res = await request.post('/ball/resecond', {
      format: form.format === 'auto' ? 'knockout' : form.format,
      groups: form.groups,
      advancePerGroup: form.advancePerGroup,
      doubleLeg: form.doubleLeg,
      daysLimit: form.daysLimit
    })
    const data = res.data || res
    ElMessage.info(`二次编排：${data.note || '已生成'}`)
  } catch (e) {
    ElMessage.error('二次编排失败：' + (e?.message || e))
  }
}

function markWinner(row) {
  ElMessage.info(`已标记：${row.home} vs ${row.away}`)
}

function exportCsv() {
  const ms = result.value?.matches || []
  if (!ms.length) return
  const head = '轮次,小组,主队,客队,回合'
  const body = ms.map(m => [m.round, m.group || '', m.home, m.away, m.leg || 1].join(','))
  const blob = new Blob(['﻿' + [head, ...body].join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `球类赛程_${result.value.format}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<style scoped>
.ball-page { padding: 12px; }
.mb { margin-bottom: 12px; }
.card-header { display: flex; align-items: baseline; gap: 12px; }
.title { font-weight: 600; }
.sub { color: #909399; font-size: 12px; }
.teams { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.team-row { display: flex; gap: 8px; align-items: center; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.tag-item { margin-right: 6px; }
.seed { font-family: monospace; font-size: 12px; word-break: break-all; }
</style>
