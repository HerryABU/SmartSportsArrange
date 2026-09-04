<template>
  <div class="meet-board">
    <!-- ===== 顶栏 ===== -->
    <header class="bd-head">
      <div class="bd-title">
        <span class="bd-logo">🏃</span>
        <div class="bd-title-text">
          <div class="bd-name">{{ meetName }}</div>
          <div class="bd-sub">现场综合数据大屏 · SmartSportsArrange</div>
        </div>
      </div>
      <div class="bd-nav">
        <button :class="['bd-nav-btn', mode === 'overview' && 'on']" @click="switchMode('overview')">
          📊 综合数据
        </button>
        <button :class="['bd-nav-btn', mode === 'ranking' && 'on']" @click="switchMode('ranking')">
          🏆 排行榜
        </button>
      </div>
      <div class="bd-tools">
        <span class="bd-refresh">
          刷新
          <select v-model="refreshSec" @change="applyTimer">
            <option :value="0">停</option>
            <option :value="5">5s</option>
            <option :value="10">10s</option>
            <option :value="15">15s</option>
            <option :value="30">30s</option>
          </select>
        </span>
        <button class="bd-nav-btn" @click="toggleFullscreen">{{ isFullscreen ? '⛶ 退出全屏' : '⛶ 全屏' }}</button>
        <button class="bd-nav-btn ghost" @click="goBack">✕ 返回后台</button>
        <span class="bd-clock">
          <span class="bd-clock-time">{{ clockTime }}</span>
          <span class="bd-clock-date">{{ clockDate }}</span>
        </span>
      </div>
    </header>

    <!-- ===== 综合数据大屏 ===== -->
    <main v-if="mode === 'overview'" class="bd-body" v-loading="loading">
      <!-- 顶部 KPI -->
      <section class="kpi-row">
        <div class="kpi-card grad-blue">
          <div class="kpi-ico">📥</div>
          <div>
            <div class="kpi-val">{{ regStats.totalRegistrations || 0 }}</div>
            <div class="kpi-label">报名总数</div>
            <div class="kpi-sub">已通过 {{ regStats.byStatus?.approved || 0 }} · 待审 {{ regStats.byStatus?.pending || 0 }}</div>
          </div>
        </div>
        <div class="kpi-card grad-green">
          <div class="kpi-ico">🏁</div>
          <div>
            <div class="kpi-val">{{ doneEvents }}<span class="kpi-slash">/{{ regStats.totalEvents || 0 }}</span></div>
            <div class="kpi-label">已完成排名项目</div>
            <div class="kpi-sub">未编排 {{ todo.unarrangedEvents || 0 }} · 待录成绩 {{ todo.pendingScores || 0 }}</div>
          </div>
        </div>
        <div class="kpi-card grad-violet">
          <div class="kpi-ico">🏫</div>
          <div>
            <div class="kpi-val">{{ regStats.totalClasses || 0 }}<span class="kpi-slash">·</span>{{ regStats.totalAthletes || 0 }}</div>
            <div class="kpi-label">班级 / 运动员</div>
            <div class="kpi-sub">比赛项目 {{ regStats.totalEvents || 0 }} 个</div>
          </div>
        </div>
        <div class="kpi-card grad-amber">
          <div class="kpi-ico">💥</div>
          <div>
            <div class="kpi-val">{{ scoreStats.totalRecords || 0 }}</div>
            <div class="kpi-label">破纪录</div>
            <div class="kpi-sub">累计成绩记录 {{ scoreStats.totalResults || 0 }} 条</div>
          </div>
        </div>
        <div class="kpi-card grad-pink">
          <div class="kpi-ico">⏰</div>
          <div>
            <div class="kpi-val">{{ nowRunning }}</div>
            <div class="kpi-label">进行中项目</div>
            <div class="kpi-sub">今日赛程 {{ todaySchedule.length }} 项</div>
          </div>
        </div>
      </section>

      <div class="bd-grid">
        <!-- 今日赛程（滚动） -->
        <section class="panel panel-today">
          <header class="panel-head"><span>📅 今日赛程</span><span class="panel-tag">{{ todayDate }}</span></header>
          <div class="today-scroll">
            <div v-if="!todaySchedule.length" class="empty-tip">今日暂无已编排赛程</div>
            <div v-for="s in todaySchedule" :key="s.id" class="today-item" :class="{ running: s.statusCode === 'in_progress' }">
              <span class="today-time">{{ s.time }}</span>
              <span class="today-dot"></span>
              <div class="today-info">
                <span class="today-name">{{ s.eventName }}</span>
                <span class="today-meta">{{ s.gender }} · {{ s.heat }} · {{ s.location }}</span>
              </div>
              <span class="today-status" :class="s.statusCode === 'in_progress' ? 'live' : ''">{{ s.status }}</span>
            </div>
          </div>
        </section>

        <!-- 报名进度（按年级） -->
        <section class="panel panel-progress">
          <header class="panel-head"><span>📈 报名进度（按年级）</span><span class="panel-tag">已通过人次/学生数</span></header>
          <div v-if="!progressList.length" class="empty-tip">暂无报名数据</div>
          <div v-for="p in progressList" :key="p.name" class="pg-row">
            <span class="pg-name">{{ p.name }}</span>
            <div class="pg-track">
              <div class="pg-fill" :style="{ width: p.pct + '%' }"></div>
            </div>
            <span class="pg-num">{{ p.registered }} / {{ p.total }}</span>
          </div>
        </section>

        <!-- 待办 + 项目完成度 -->
        <section class="panel panel-todo">
          <header class="panel-head"><span>🧭 流程待办 & 项目完成度</span></header>
          <div class="todo-grid">
            <div class="todo-chip warn"><b>{{ todo.pendingRegistrations || 0 }}</b><span>待审核报名</span></div>
            <div class="todo-chip info"><b>{{ todo.unarrangedEvents || 0 }}</b><span>未编排项目</span></div>
            <div class="todo-chip danger"><b>{{ todo.pendingScores || 0 }}</b><span>待录入成绩</span></div>
          </div>
          <div class="evt-progress">
            <div v-for="e in eventProgressRows" :key="e.name" class="evt-row">
              <span class="evt-name" :title="e.name">{{ e.name }}</span>
              <div class="evt-track">
                <div class="evt-fill" :class="e.hasRanking ? 'done' : ''" :style="{ width: (e.hasRanking ? 100 : 12) + '%' }"></div>
              </div>
              <span class="evt-state" :class="e.hasRanking ? 'done' : ''">{{ e.hasRanking ? '✓ 已排名' : '待排名' }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- 底部：团体总分 TOP + 破纪录 -->
      <div class="bd-grid bottom">
        <section class="panel panel-team">
          <header class="panel-head"><span>🥇 团体总分 TOP 6</span><span class="panel-tag">按总分 · 自动刷新</span></header>
          <div v-if="!teamTop.length" class="empty-tip">暂无成绩，先录入成绩并计算排名</div>
          <div v-for="(row, i) in teamTop" :key="row.className" class="team-row" :class="'tk' + (i + 1)">
            <span class="team-rank">{{ i + 1 }}</span>
            <span class="team-name">{{ row.className }}</span>
            <div class="team-bar-bg"><div class="team-bar" :style="{ width: barWidth(row, i) }"></div></div>
            <span class="team-score">{{ row.totalPoints }}</span>
            <span class="team-medals">🥇{{ row.goldCount || 0 }} 🥈{{ row.silverCount || 0 }} 🥉{{ row.bronzeCount || 0 }}</span>
          </div>
        </section>

        <section class="panel panel-records">
          <header class="panel-head"><span>⚡ 破纪录时刻</span></header>
          <div v-if="!recordsTop.length" class="empty-tip">暂无破纪录记录</div>
          <div class="record-ticker">
            <div v-for="r in recordsTop" :key="r.athleteId + r.eventName" class="record-item">
              <span class="record-badge">新纪录</span>
              <span class="record-name">{{ r.athleteName }}</span>
              <span class="record-cls">{{ r.className }}</span>
              <span class="record-evt">{{ r.eventName }}</span>
              <span class="record-score">{{ r.score }}</span>
              <span class="record-old">原 {{ r.oldRecord }}</span>
            </div>
          </div>
        </section>
      </div>
    </main>

    <!-- ===== 排行榜大屏 ===== -->
    <main v-else class="bd-body rank-body" v-loading="loading">
      <div class="rank-toolbar">
        <label>年级
          <select v-model="rkGrade">
            <option value="">全部年级</option>
            <option v-for="g in gradeOptions" :key="g" :value="g">{{ g }}</option>
          </select>
        </label>
        <label>项目实时前三
          <select v-model="rkEventId">
            <option value="">— 选择项目 —</option>
            <option v-for="e in eventList" :key="e.id" :value="e.id">{{ e.name }}</option>
          </select>
        </label>
        <label class="parade-switch">总榜含入场式
          <input type="checkbox" v-model="rkIncludeParade" @change="loadScoreboards" />
        </label>
        <button class="bd-nav-btn small" @click="loadAll(true)">⟳ 立即刷新</button>
      </div>

      <!-- 总榜 podium + 列表 -->
      <section class="panel panel-podium">
        <header class="panel-head"><span>🏆 合分总榜（{{ rkIncludeParade ? '含入场式' : '去除入场式' }} · {{ rkGrade || '全校' }}）</span></header>
        <div v-if="boardRows.length" class="podium">
          <div class="podium-stage">
            <template v-for="(row, i) in podiumTop3" :key="row.className">
              <div class="podium-col" :class="'pc' + (i + 1)" v-if="i < 3">
                <div class="podium-medal">{{ ['🥇', '🥈', '🥉'][i] }}</div>
                <div class="podium-name">{{ row.className }}</div>
                <div class="podium-score">{{ row.totalWithParade ?? row.totalScore }}</div>
                <div class="podium-bar" :style="{ height: podiumH(row, i) }"></div>
              </div>
            </template>
          </div>
          <div v-if="boardRows.length > 3" class="rank-list">
            <div v-for="(row, i) in boardRows.slice(3)" :key="row.className" class="rank-item">
              <span class="rk-no">{{ i + 4 }}</span>
              <span class="rk-name">{{ row.className }}</span>
              <span class="rk-grade">{{ row.grade }}</span>
              <span class="rk-m">男 {{ row.maleScore }}</span>
              <span class="rk-f">女 {{ row.femaleScore }}</span>
              <span class="rk-total">{{ row.totalWithParade ?? row.totalScore }}</span>
            </div>
          </div>
          <div v-else class="empty-tip">暂无排行数据</div>
        </div>
        <div v-else class="empty-tip">暂无排行数据 —— 请先在「成绩录入 / 合分排行」中录入成绩并计算排名</div>
      </section>

      <div class="bd-grid rank-grid">
        <!-- 男/女单性别榜 -->
        <section class="panel">
          <header class="panel-head"><span>🏃 男生榜 TOP5</span></header>
          <div v-for="(r, i) in maleTop" :key="'m' + r.classId" class="gb-row">
            <span class="gb-rank" :class="'r' + (i + 1)">{{ i + 1 }}</span>
            <span class="gb-name">{{ r.className }}</span>
            <span class="gb-score">{{ r.totalWithParade ?? r.totalScore }}</span>
          </div>
          <div v-if="!maleTop.length" class="empty-tip">暂无</div>
        </section>
        <section class="panel">
          <header class="panel-head"><span>🏃‍♀️ 女生榜 TOP5</span></header>
          <div v-for="(r, i) in femaleTop" :key="'f' + r.classId" class="gb-row">
            <span class="gb-rank" :class="'r' + (i + 1)">{{ i + 1 }}</span>
            <span class="gb-name">{{ r.className }}</span>
            <span class="gb-score">{{ r.totalWithParade ?? r.totalScore }}</span>
          </div>
          <div v-if="!femaleTop.length" class="empty-tip">暂无</div>
        </section>

        <!-- 项目实时前三 -->
        <section class="panel">
          <header class="panel-head"><span>⚡ {{ currentEventName || '项目实时前三' }}</span></header>
          <div v-if="eventRankings.length" class="evt-podium">
            <div v-for="(r, i) in eventRankings.slice(0, 3)" :key="'e' + i" class="ep-row">
              <span class="ep-medal">{{ ['🥇', '🥈', '🥉'][i] }}</span>
              <span class="ep-name">{{ r.athleteName }}</span>
              <span class="ep-cls">{{ r.className }}</span>
              <span class="ep-score">{{ r.rawTime || r.score || '-' }}</span>
            </div>
            <div v-if="eventRankings.length > 3" class="ep-more">
              第 {{ eventRankings[3]?.rank ?? 4 }} 名：{{ eventRankings[3]?.athleteName }}
            </div>
          </div>
          <div v-else class="empty-tip">{{ rkEventId ? '该项目暂无排名数据' : '请选择要展示的项目' }}</div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const route = useRoute()
const router = useRouter()

// ==================== 状态 ====================
const mode = ref('overview')
const loading = ref(false)
const meetName = ref('校园运动会')
const gradeOptions = ref([])
const eventList = ref([])

const refreshSec = ref(10)
let timer = null
let clockTimer = null

const clockTime = ref('')
const clockDate = ref('')
const isFullscreen = ref(false)
const todayDate = ref('')

// ---- overview 数据 ----
const regStats = reactive({})
const scoreStats = reactive({})
const todo = reactive({ pendingRegistrations: 0, unarrangedEvents: 0, pendingScores: 0 })
const progressList = ref([])
const todaySchedule = ref([])
const teamTop = ref([])
const recordsTop = ref([])

// ---- ranking 数据 ----
const rkGrade = ref('')
const rkEventId = ref('')
const rkIncludeParade = ref(false)
const boardRows = ref([])
const maleTop = ref([])
const femaleTop = ref([])
const eventRankings = ref([])

const currentEventName = computed(() => {
  const e = eventList.value.find(x => String(x.id) === String(rkEventId.value))
  return e ? e.name : ''
})

// ==================== 计算 ====================
const doneEvents = computed(() => {
  if (!scoreStats.byEvent) return 0
  return Object.values(scoreStats.byEvent).filter(e => e.hasRanking).length
})
const nowRunning = computed(() => todaySchedule.value.filter(s => s.statusCode === 'in_progress').length)

const eventProgressRows = computed(() => {
  const ev = regStats.byEvent || {}
  return Object.entries(ev).slice(0, 8).map(([name, counts]) => ({
    name,
    hasRanking: !!(scoreStats.byEvent && scoreStats.byEvent[name]?.hasRanking)
  }))
})

const podiumTop3 = computed(() => boardRows.value.slice(0, 3))

function podiumH(row, i) {
  const max = Math.max(...boardRows.value.map(r => Number(r.totalWithParade ?? r.totalScore)), 1)
  const base = 70 + (2 - i) * 34
  return Math.max(46, Math.round((Number(row.totalWithParade ?? row.totalScore) / max) * base)) + 'px'
}
function barWidth(row, i) {
  const max = Math.max(...teamTop.value.map(r => Number(r.totalPoints)), 1)
  const w = Math.round((Number(row.totalPoints) / max) * 100)
  return Math.max(8, w - i * 2) + '%'
}

function switchMode(m) {
  mode.value = m
  loadAll(true)
}

// ==================== 数据加载 ====================
async function loadAll(force) {
  if (force) loading.value = true
  try {
    const tasks = [loadMeta(), loadCommon(), loadRecords()]
    if (mode.value === 'overview') {
      tasks.push(loadToday())
    } else {
      tasks.push(loadScoreboards(), loadEventRanking())
    }
    await Promise.all(tasks)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadMeta() {
  try {
    const ms = await request.get('/system/meet-schedule')
    if (ms?.meetName) meetName.value = ms.meetName
  } catch (e) { /* ignore */ }
  try {
    const gs = await request.get('/system/grades')
    const list = Array.isArray(gs) ? gs : (gs?.records || [])
    gradeOptions.value = list.map(g => (g && g.name) || '').filter(Boolean)
  } catch (e) { gradeOptions.value = [] }
  try {
    const es = await request.get('/events')
    eventList.value = Array.isArray(es) ? es : (es.records || [])
  } catch (e) { eventList.value = [] }
}

async function loadCommon() {
  try {
    const r = await request.get('/statistics/registration')
    Object.assign(regStats, r || {})
  } catch (e) { /* ignore */ }
  try {
    const s = await request.get('/statistics/score')
    Object.assign(scoreStats, s || {})
  } catch (e) { /* ignore */ }
  try {
    const t = await request.get('/statistics/todo')
    Object.assign(todo, t || {})
  } catch (e) { /* ignore */ }
  try {
    const p = await request.get('/statistics/registration-progress')
    const list = Array.isArray(p) ? p : (p?.records || [])
    progressList.value = list.map(x => {
      const total = Number(x.total) || 0
      return { ...x, pct: total > 0 ? Math.min(100, Math.round(Number(x.registered || 0) / total * 100)) : 0 }
    })
  } catch (e) { progressList.value = [] }
  try {
    const tm = await request.get('/ranking/team-score')
    teamTop.value = (Array.isArray(tm) ? tm : (tm?.records || [])).slice(0, 6)
  } catch (e) { teamTop.value = [] }
}

async function loadToday() {
  try {
    const s = await request.get('/statistics/today-schedule')
    todaySchedule.value = Array.isArray(s) ? s : (s?.records || [])
  } catch (e) { todaySchedule.value = [] }
}

async function loadRecords() {
  try {
    const rs = await request.get('/ranking/records')
    recordsTop.value = (Array.isArray(rs) ? rs : (rs.records || [])).slice(0, 5)
  } catch (e) { recordsTop.value = [] }
}

async function loadScoreboards() {
  const base = { grade: rkGrade.value, includeParade: rkIncludeParade.value, topN: 10 }
  try {
    const b = await request.get('/ranking/scoreboard', { params: { ...base } })
    boardRows.value = (b?.rows || []).slice(0, 10)
  } catch (e) { boardRows.value = [] }
  try {
    const m = await request.get('/ranking/scoreboard', { params: { ...base, gender: '男', topN: 5 } })
    maleTop.value = (m?.rows || []).slice(0, 5)
  } catch (e) { maleTop.value = [] }
  try {
    const f = await request.get('/ranking/scoreboard', { params: { ...base, gender: '女', topN: 5 } })
    femaleTop.value = (f?.rows || []).slice(0, 5)
  } catch (e) { femaleTop.value = [] }
}

async function loadEventRanking() {
  if (!rkEventId.value) { eventRankings.value = []; return }
  try {
    const r = await request.get('/ranking/events/' + rkEventId.value)
    const rows = (r?.rankings || []).filter(x => x.rank).sort((a, b) => (a.rank || 99) - (b.rank || 99))
    eventRankings.value = rows
  } catch (e) { eventRankings.value = [] }
}

// ==================== 定时/时钟/全屏 ====================
function applyTimer() {
  clearInterval(timer)
  if (Number(refreshSec.value) > 0) {
    timer = setInterval(() => loadAll(false), Number(refreshSec.value) * 1000)
  }
}

function tickClock() {
  const n = new Date()
  clockTime.value = n.toLocaleTimeString('zh-CN', { hour12: false })
  clockDate.value = `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')} ${'日一二三四五六'[n.getDay()]}`
  todayDate.value = `${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`
}

async function toggleFullscreen() {
  try {
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen()
    } else {
      await document.exitFullscreen()
    }
    isFullscreen.value = !!document.fullscreenElement
  } catch (e) { ElMessage.warning('全屏需在浏览器投屏时使用') }
}

function goBack() {
  router.push('/teacher/dashboard')
}

// ==================== 初始化 ====================
watch(rkGrade, () => loadScoreboards())
watch(rkEventId, () => loadEventRanking())

onMounted(async () => {
  const m = route.query.mode
  mode.value = (m === 'ranking' || m === 'overview') ? m : 'overview'
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  applyTimer()
  await loadAll(true)
  document.addEventListener('fullscreenchange', onFsChange)
})
function onFsChange() { isFullscreen.value = !!document.fullscreenElement }
onBeforeUnmount(() => {
  clearInterval(timer)
  clearInterval(clockTimer)
  document.removeEventListener('fullscreenchange', onFsChange)
})
</script>

<style scoped>
/* ===== 投屏专用：页面自带深色主题（与系统亮暗无关） ===== */
.meet-board {
  min-height: 100vh;
  background:
    radial-gradient(1100px 500px at 10% -5%, rgba(56, 189, 248, .16), transparent 60%),
    radial-gradient(900px 480px at 100% 0%, rgba(139, 92, 246, .16), transparent 55%),
    linear-gradient(160deg, #050d1d 0%, #0a1428 55%, #0c1b33 100%);
  color: #e2e8f0;
  display: flex;
  flex-direction: column;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* 顶栏 */
.bd-head {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 10px 18px;
  background: rgba(10, 20, 40, .72);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(148, 163, 184, .16);
  flex-wrap: wrap;
}
.bd-title { display: flex; align-items: center; gap: 10px; }
.bd-logo {
  width: 42px; height: 42px; border-radius: 12px;
  background: linear-gradient(135deg, #38bdf8, #6366f1);
  display: inline-flex; align-items: center; justify-content: center; font-size: 22px;
  box-shadow: 0 6px 16px rgba(56, 189, 248, .3);
}
.bd-name { font-size: 20px; font-weight: 800; letter-spacing: .5px; }
.bd-sub { font-size: 11px; color: #7d8ea8; }
.bd-nav { display: flex; gap: 8px; }
.bd-nav-btn {
  border: 1px solid rgba(148, 163, 184, .25);
  background: rgba(148, 163, 184, .08);
  color: #cbd5e1; border-radius: 10px;
  padding: 7px 14px; font-size: 13px; cursor: pointer;
  transition: all .2s;
}
.bd-nav-btn:hover { background: rgba(148, 163, 184, .18); }
.bd-nav-btn.on { background: linear-gradient(135deg, #38bdf8, #6366f1); color: #fff; border-color: transparent; }
.bd-nav-btn.small { padding: 5px 10px; font-size: 12px; }
.bd-nav-btn.ghost { background: transparent; }
.bd-tools { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.bd-refresh { font-size: 12px; color: #94a3b8; display: inline-flex; gap: 6px; align-items: center; }
.bd-refresh select,
.rank-toolbar select {
  background: #0f1e38; color: #e2e8f0; border: 1px solid rgba(148, 163, 184, .3);
  border-radius: 8px; padding: 4px 8px; font-size: 12px;
}
.bd-clock { text-align: right; line-height: 1.2; }
.bd-clock-time { display: block; font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; color: #7dd3fc; }
.bd-clock-date { font-size: 11px; color: #94a3b8; }

/* 主体 */
.bd-body { flex: 1; padding: 14px 18px 22px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }

/* KPI */
.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.kpi-card {
  display: flex; align-items: center; gap: 12px;
  border-radius: 16px; padding: 14px 16px;
  border: 1px solid rgba(255, 255, 255, .09);
  background: rgba(15, 30, 56, .66);
  box-shadow: 0 8px 24px rgba(0, 0, 0, .25);
  animation: bdIn .5s ease both;
}
.kpi-ico {
  width: 46px; height: 46px; border-radius: 14px; font-size: 24px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, .1);
}
.kpi-val { font-size: 30px; font-weight: 800; line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-slash { font-size: 16px; opacity: .55; }
.kpi-label { font-size: 13px; color: #94a3b8; }
.kpi-sub { font-size: 11px; color: #64748b; margin-top: 2px; }
.grad-blue { background: linear-gradient(120deg, rgba(56, 189, 248, .18), rgba(15, 30, 56, .6)); }
.grad-green { background: linear-gradient(120deg, rgba(52, 211, 153, .16), rgba(15, 30, 56, .6)); }
.grad-violet { background: linear-gradient(120deg, rgba(139, 92, 246, .18), rgba(15, 30, 56, .6)); }
.grad-amber { background: linear-gradient(120deg, rgba(251, 191, 36, .16), rgba(15, 30, 56, .6)); }
.grad-pink { background: linear-gradient(120deg, rgba(244, 114, 182, .16), rgba(15, 30, 56, .6)); }

/* 中部网格 */
.bd-grid { display: grid; grid-template-columns: 1.25fr 1fr 1.05fr; gap: 14px; }
.bd-grid.bottom { grid-template-columns: 1.35fr 1fr; }
.panel {
  background: rgba(15, 30, 56, .6);
  border: 1px solid rgba(148, 163, 184, .14);
  border-radius: 16px;
  padding: 12px 14px;
  animation: bdIn .5s ease both;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 14px; font-weight: 700; color: #f1f5f9;
  padding-bottom: 10px; margin-bottom: 10px;
  border-bottom: 1px solid rgba(148, 163, 184, .12);
}
.panel-tag { font-size: 11px; color: #64748b; font-weight: 400; }

.empty-tip { color: #526079; font-size: 13px; text-align: center; padding: 18px 0; }

/* 今日赛程滚动 */
.today-scroll { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; }
.today-item { display: flex; align-items: center; gap: 10px; padding: 8px 4px; border-radius: 10px; }
.today-item:hover { background: rgba(148, 163, 184, .06); }
.today-item.running { background: rgba(56, 189, 248, .08); }
.today-time { font-size: 15px; font-weight: 800; color: #7dd3fc; min-width: 44px; font-variant-numeric: tabular-nums; }
.today-dot { width: 8px; height: 8px; border-radius: 50%; background: #334155; flex-shrink: 0; }
.today-item.running .today-dot { background: #34d399; box-shadow: 0 0 0 4px rgba(52, 211, 153, .2); animation: pulse 1.4s infinite; }
.today-info { flex: 1; display: flex; flex-direction: column; line-height: 1.3; }
.today-name { font-size: 14px; font-weight: 600; }
.today-meta { font-size: 11px; color: #64748b; }
.today-status { font-size: 11px; color: #94a3b8; border: 1px solid rgba(148,163,184,.2); border-radius: 999px; padding: 2px 8px; }
.today-status.live { color: #34d399; border-color: rgba(52,211,153,.4); animation: pulse 1.4s infinite; }

/* 报名进度 */
.pg-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.pg-name { width: 78px; font-size: 13px; color: #cbd5e1; flex-shrink: 0; }
.pg-track { flex: 1; height: 12px; border-radius: 999px; background: rgba(148,163,184,.14); overflow: hidden; }
.pg-fill {
  height: 100%; border-radius: 999px;
  background: linear-gradient(90deg, #38bdf8, #6366f1);
  transition: width .8s ease;
}
.pg-num { font-size: 12px; color: #7dd3fc; min-width: 78px; text-align: right; font-variant-numeric: tabular-nums; }

/* 待办+项目完成 */
.todo-grid { display: flex; gap: 10px; margin-bottom: 14px; }
.todo-chip {
  flex: 1; text-align: center; border-radius: 12px; padding: 10px 6px;
  background: rgba(148, 163, 184, .08); border: 1px solid rgba(148,163,184,.14);
}
.todo-chip b { display: block; font-size: 24px; font-weight: 800; }
.todo-chip span { font-size: 11px; color: #94a3b8; }
.todo-chip.warn b { color: #fbbf24; } .todo-chip.info b { color: #38bdf8; } .todo-chip.danger b { color: #fb7185; }
.evt-progress { display: flex; flex-direction: column; gap: 7px; max-height: 240px; overflow-y: auto; }
.evt-row { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.evt-name { width: 120px; color: #cbd5e1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.evt-track { flex: 1; height: 8px; border-radius: 999px; background: rgba(148,163,184,.14); overflow: hidden; }
.evt-fill { height: 100%; border-radius: 999px; background: #64748b; transition: width .6s ease; }
.evt-fill.done { background: linear-gradient(90deg, #34d399, #38bdf8); }
.evt-state { width: 58px; text-align: right; color: #64748b; }
.evt-state.done { color: #34d399; }

/* 团体 TOP */
.team-row { display: flex; align-items: center; gap: 10px; padding: 7px 4px; font-size: 13px; }
.team-rank {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(148,163,184,.12); color: #cbd5e1; font-weight: 800;
}
.team-row.tk1 .team-rank { background: linear-gradient(135deg, #fbbf24, #f59e0b); color: #111827; box-shadow: 0 0 12px rgba(251,191,36,.5); }
.team-row.tk2 .team-rank { background: linear-gradient(135deg, #e2e8f0, #94a3b8); color: #111827; }
.team-row.tk3 .team-rank { background: linear-gradient(135deg, #fdba74, #c2703a); color: #111827; }
.team-name { width: 130px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-weight: 600; }
.team-bar-bg { flex: 1; height: 12px; border-radius: 999px; background: rgba(148,163,184,.14); overflow: hidden; }
.team-bar {
  height: 100%; border-radius: 999px;
  background: linear-gradient(90deg, #fbbf24, #f472b6);
  transition: width .8s ease;
}
.team-score { font-size: 18px; font-weight: 800; color: #7dd3fc; min-width: 44px; text-align: right; font-variant-numeric: tabular-nums; }
.team-medals { font-size: 11px; color: #94a3b8; min-width: 110px; }

/* 破纪录 */
.record-ticker { display: flex; flex-direction: column; gap: 8px; }
.record-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  background: rgba(251,191,36,.06); border: 1px solid rgba(251,191,36,.18);
  border-radius: 10px; font-size: 12px; animation: bdIn .4s ease both;
}
.record-badge { color: #111827; background: linear-gradient(135deg,#fbbf24,#f59e0b); border-radius: 6px; padding: 1px 6px; font-weight: 700; }
.record-name { font-weight: 700; }
.record-cls { color: #94a3b8; }
.record-evt { flex: 1; text-align: right; color: #cbd5e1; }
.record-score { font-weight: 800; color: #fbbf24; font-size: 14px; }
.record-old { font-size: 10px; color: #64748b; }

/* ===== 排行榜 ===== */
.rank-toolbar {
  display: flex; gap: 14px; align-items: center; flex-wrap: wrap;
  font-size: 13px; color: #94a3b8;
}
.rank-toolbar label { display: inline-flex; gap: 6px; align-items: center; }
.parade-switch { cursor: pointer; }
.parade-switch input { accent-color: #38bdf8; }
.panel-podium { min-height: 340px; }
.podium-stage { display: flex; align-items: flex-end; justify-content: center; gap: 20px; height: 210px; padding: 16px 0 4px; }
.podium-col { width: 190px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.podium-medal { font-size: 40px; line-height: 1; filter: drop-shadow(0 4px 8px rgba(0,0,0,.35)); }
.podium-name { font-size: 16px; font-weight: 800; color: #f1f5f9; max-width: 180px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.podium-score { font-size: 24px; font-weight: 900; color: #fbbf24; font-variant-numeric: tabular-nums; }
.podium-bar {
  width: 100%; border-radius: 10px 10px 0 0;
  transition: height .7s ease;
}
.pc1 .podium-bar { background: linear-gradient(180deg, #fbbf24, #b45309); box-shadow: 0 -4px 18px rgba(251,191,36,.45); }
.pc2 .podium-bar { background: linear-gradient(180deg, #e2e8f0, #64748b); }
.pc3 .podium-bar { background: linear-gradient(180deg, #fdba74, #b45309); }
.rank-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 16px; margin-top: 8px; }
.rank-item {
  display: flex; align-items: center; gap: 8px; padding: 6px 8px;
  border-radius: 8px; background: rgba(148,163,184,.06); font-size: 12px;
}
.rk-no { width: 22px; color: #64748b; font-weight: 800; }
.rk-name { font-weight: 700; flex: 1; }
.rk-grade { color: #64748b; }
.rk-m { color: #7dd3fc; } .rk-f { color: #f9a8d4; }
.rk-total { font-weight: 800; color: #fbbf24; }

.rank-grid { grid-template-columns: repeat(3, 1fr); }
.gb-row { display: flex; align-items: center; gap: 8px; padding: 7px 6px; font-size: 13px; }
.gb-rank { width: 24px; height: 24px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; background: rgba(148,163,184,.14); font-weight: 800; }
.gb-rank.r1 { background: linear-gradient(135deg,#fbbf24,#f59e0b); color: #111827; }
.gb-rank.r2 { background: linear-gradient(135deg,#e2e8f0,#94a3b8); color: #111827; }
.gb-rank.r3 { background: linear-gradient(135deg,#fdba74,#c2703a); color: #111827; }
.gb-name { flex: 1; }
.gb-score { font-weight: 800; color: #7dd3fc; font-variant-numeric: tabular-nums; }

.ev-podium { display: flex; flex-direction: column; gap: 8px; }
.ep-row {
  display: flex; align-items: center; gap: 10px;
  border: 1px solid rgba(148,163,184,.16);
  border-radius: 12px; padding: 10px 12px;
  background: rgba(148,163,184,.05);
}
.ep-medal { font-size: 26px; }
.ep-name { font-weight: 800; }
.ep-cls { flex: 1; color: #94a3b8; font-size: 12px; }
.ep-score { font-size: 20px; font-weight: 900; color: #7dd3fc; font-variant-numeric: tabular-nums; }
.ep-more { font-size: 12px; color: #64748b; text-align: center; padding-top: 4px; }

/* 动效 */
@keyframes bdIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }

/* 滚动条 */
.meet-board ::-webkit-scrollbar { width: 6px; height: 6px; }
.meet-board ::-webkit-scrollbar-thumb { background: #263c60; border-radius: 999px; }

@media (max-width: 1200px) {
  .kpi-row { grid-template-columns: repeat(3, 1fr); }
  .bd-grid, .bd-grid.bottom, .rank-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 900px) {
  .kpi-row, .bd-grid, .bd-grid.bottom, .rank-grid { grid-template-columns: 1fr; }
}
</style>
