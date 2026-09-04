<template>
  <div class="ct-results" v-loading="loading">
    <!-- 页面头 -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🏅</span>
        <div>
          <h3 class="pg-title">本班成绩</h3>
          <p class="pg-desc">已计算排名的比赛成绩与积分（名次 1/2/3 金/银/铜），破纪录自动标红</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#fef2f2;color:#dc2626">成绩由体育老师录入后自动同步</span>
      </div>
    </div>

    <!-- 班级奖牌榜概览 -->
    <div v-if="summary" class="score-cards">
      <div class="score-card main">
        <div class="sc-num">{{ num(summary.totalPoints) }}</div>
        <div class="sc-label">班级总积分</div>
      </div>
      <div class="score-card gold">
        <div class="sc-num">{{ summary.goldCount || 0 }}</div>
        <div class="sc-label">🥇 金牌</div>
      </div>
      <div class="score-card silver">
        <div class="sc-num">{{ summary.silverCount || 0 }}</div>
        <div class="sc-label">🥈 银牌</div>
      </div>
      <div class="score-card bronze">
        <div class="sc-num">{{ summary.bronzeCount || 0 }}</div>
        <div class="sc-label">🥉 铜牌</div>
      </div>
      <div class="score-card total">
        <div class="sc-num">{{ summary.medalCount || 0 }}</div>
        <div class="sc-label">奖牌合计</div>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>📋 成绩明细</span>
          <el-tag type="info" effect="plain" round>{{ tableData.length }} 条记录</el-tag>
        </div>
      </template>
      <el-table :data="tableData" border stripe max-height="540">
        <el-table-column type="index" label="#" width="52" align="center" />
        <el-table-column prop="athleteName" label="运动员" min-width="100" />
        <el-table-column prop="eventName" label="项目" min-width="150" />
        <el-table-column label="成绩" width="110" align="center">
          <template #default="{ row }">
            <span class="raw-score">{{ row.score || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="名次" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.rank" :type="rankType(row.rank)" size="small" effect="dark" round>
              {{ medalOf(row.rank) }} {{ row.rank }}
            </el-tag>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column label="积分" width="80" align="center">
          <template #default="{ row }">
            <span class="pts" v-if="row.points">{{ row.points }}</span>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="90">
          <template #default="{ row }">
            <el-tag v-if="row.isRecord" type="danger" size="small" effect="dark">⚡ 破纪录</el-tag>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tableData.length" description="暂无成绩 —— 比赛开始并录入成绩后会在此展示" :image-size="90" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const tableData = ref([])
const summary = ref(null)

function num(v) {
  const n = Number(v || 0)
  return Math.round(n * 100) / 100
}
function rankType(rank) {
  if (rank === 1) return 'danger'
  if (rank === 2) return 'warning'
  if (rank === 3) return 'success'
  return 'info'
}
function medalOf(rank) {
  return rank === 1 ? '🥇' : rank === 2 ? '🥈' : rank === 3 ? '🥉' : ''
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/results')
    tableData.value = res.records || res.results || []
    summary.value = res.summary || null
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.ct-results { display: flex; flex-direction: column; gap: 14px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.score-cards { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.score-card {
  border-radius: 16px; padding: 16px 10px; text-align: center;
  border: 1px solid #e2e8f0; background: linear-gradient(180deg, #ffffff, #f8fafc);
  transition: all .25s;
}
.score-card:hover { transform: translateY(-3px); box-shadow: 0 10px 22px rgba(15,23,42,.08); }
.sc-num { font-size: 30px; font-weight: 800; font-variant-numeric: tabular-nums; }
.sc-label { font-size: 12.5px; color: #64748b; margin-top: 4px; }
.score-card.main { background: linear-gradient(135deg, #6366f1, #3b82f6); border-color: transparent; }
.score-card.main .sc-num, .score-card.main .sc-label { color: #fff; }
.score-card.gold .sc-num { color: #b45309; } .score-card.gold { background: linear-gradient(160deg, #fffbeb, #fef3c7); }
.score-card.silver .sc-num { color: #475569; } .score-card.silver { background: linear-gradient(160deg, #f8fafc, #e2e8f0); }
.score-card.bronze .sc-num { color: #9a3412; } .score-card.bronze { background: linear-gradient(160deg, #fff7ed, #ffedd5); }
.score-card.total .sc-num { color: #be123c; } .score-card.total { background: linear-gradient(160deg, #fff1f2, #ffe4e6); }
.raw-score { font-weight: 700; font-size: 15px; color: #0f172a; }
.pts { font-weight: 800; color: #2563eb; font-size: 15px; }
@media (max-width: 900px) { .score-cards { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 560px) { .score-cards { grid-template-columns: repeat(2, 1fr); } }
</style>
