<template>
  <div class="ct-reg" v-loading="loading">
    <!-- ===== 页面头 ===== -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🎯</span>
        <div>
          <h3 class="pg-title">运动会 · 现场报名</h3>
          <p class="pg-desc">
            单人逐个报名（学号/姓名定位）或「按项目批量勾选」整队报名；已报好的整表可用「批量导入-后置」直接通过。
            逐个/现场报名提交后为待审核，由体育老师审核通过
          </p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">仅限本班</span>
        <el-button size="small" type="success" @click="exportRegistrations">
          <el-icon><Download /></el-icon> 导出报名表
        </el-button>
        <el-button size="small" plain @click="openImportDialog">
          <el-icon><Upload /></el-icon> 批量导入报名表
        </el-button>
      </div>
    </div>

    <!-- ===== 概览条 ===== -->
    <div class="overview">
      <div class="ov-item main"><b>{{ athletes.length }}</b><span>本班学生</span></div>
      <div class="ov-item"><b>{{ regStats.regCount }}</b><span>报名人次</span></div>
      <div class="ov-item warn"><b>{{ pendingCount }}</b><span>待审核</span></div>
      <div class="ov-item danger"><b>{{ unregisteredAthletes.length }}</b><span>尚未报名</span></div>
      <div class="ov-item ok"><b>{{ approvedCount }}</b><span>已通过</span></div>
      <div class="ov-progress">
        <span class="ov-progress-label">报名覆盖率</span>
        <div class="pg-track"><div class="pg-fill" :style="{ width: coveragePct + '%' }"></div></div>
        <span class="ov-progress-num">{{ coveragePct }}%</span>
      </div>
    </div>

    <!-- ===== 视图切换：默认方块 / 可切横排列表（作用于全页所有选择器） ===== -->
    <div class="view-toolbar">
      <span class="vt-label">选择器展示</span>
      <div class="vt-switch">
        <button type="button" class="vt-btn" :class="{ on: viewMode === 'grid' }" @click="viewMode = 'grid'">
          <span class="vt-ico">▦</span>方块
        </button>
        <button type="button" class="vt-btn" :class="{ on: viewMode === 'list' }" @click="viewMode = 'list'">
          <span class="vt-ico">☰</span>列表
        </button>
      </div>
      <span class="vt-hint">默认小方块，适合现场快速点选；点击「列表」一键切换为横排列表</span>
    </div>

    <!-- ===== 名单为空提醒（现场报名前先建花名册） ===== -->
    <el-alert v-if="!athletes.length" type="warning" show-icon :closable="false" class="empty-roster-alert">
      <template #title>
        尚未导入班级名单，请先导入全班花名册（Excel / CSV：学号,姓名,性别，自动识别 UTF-8 / GBK）：
        <span style="display:inline-flex;gap:8px;margin-left:8px;vertical-align:middle">
          <el-upload :action="importRosterUrl" :headers="uploadHeaders" :show-file-list="false"
            accept=".xlsx,.xls,.csv" :on-success="onImportSuccess" :on-error="onImportError"
            style="display:inline-block">
            <el-button type="primary" size="small">导入班级名单</el-button>
          </el-upload>
          <el-button size="small" plain @click="downloadRosterTemplate">下载模板</el-button>
        </span>
      </template>
    </el-alert>

    <!-- ===== 工作台（名单已就绪时展示） ===== -->
    <div v-if="athletes.length" class="workspace">
      <!-- 左侧：报名工作台 -->
      <section class="ws-main">
        <el-tabs v-model="workMode">
          <!-- ---------- 单人报名 ---------- -->
          <el-tab-pane name="single">
            <template #label><span class="tab-ico">👤</span> 单人报名</template>
            <div class="single-box">
              <el-input v-model="searchKw" size="large" placeholder="输入学号或姓名，自动定位学生" clearable
                class="search-input">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>

              <!-- 定位结果 -->
              <div v-if="searched" class="person-bar">
                <template v-if="foundAthlete">
                  <div class="person-avatar">{{ (foundAthlete.name || '?').slice(0, 1) }}</div>
                  <div class="person-info">
                    <div class="person-name">
                      {{ foundAthlete.name }}
                      <el-tag size="small" :type="foundAthlete.gender === 'M' ? 'primary' : 'danger'" effect="plain">
                        {{ foundAthlete.gender === 'M' ? '男' : '女' }}
                      </el-tag>
                      <el-tag v-if="regOf(foundAthlete).length" size="small" effect="plain">
                        已报 {{ regOf(foundAthlete).length }} 项
                      </el-tag>
                    </div>
                    <div class="person-sub">
                      学号 {{ foundAthlete.studentNo }} · {{ foundAthlete.grade || '' }}
                      <span v-if="regOf(foundAthlete).length" class="person-regs">
                        已报：{{ regOf(foundAthlete).map(r => r.eventName).join('、') }}
                      </span>
                    </div>
                  </div>
                  <el-tag v-if="regOf(foundAthlete).length >= 3" type="warning" effect="dark">已报满 3 项</el-tag>
                  <el-button v-else type="primary" round size="small" style="flex-shrink:0" @click="openPicker(foundAthlete)">
                    <el-icon><Check /></el-icon> 为该生多选报名
                  </el-button>
                </template>
                <div v-else class="person-none">未找到「{{ searchKw }}」，请核对学号或先导入班级名单</div>
              </div>

              <!-- 项目类别过滤 -->
              <div v-if="foundAthlete" class="cat-filter">
                <el-radio-group v-model="singleCat" size="small">
                  <el-radio-button value="">全部</el-radio-button>
                  <el-radio-button value="径赛">径赛</el-radio-button>
                  <el-radio-button value="田赛">田赛</el-radio-button>
                </el-radio-group>
                <span class="cat-hint">点击项目小方块 → 弹出报名框可继续多选其他项目</span>
              </div>

              <!-- 可报项目：方块/列表双视图 -->
              <div v-if="foundAthlete && singleProjects.length"
                class="pick-zone proj-zone" :class="viewMode === 'list' ? 'row' : ''">
                <div v-for="evt in singleProjects" :key="evt.id" class="pk"
                  :class="{
                    done: isRegSingle(evt),
                    off: !isRegSingle(evt) && !canSingle(evt)
                  }"
                  @click="isRegSingle(evt) ? ElMessage.info('该生已报「' + evt.name + '」') : openPicker(foundAthlete, evt)">
                  <div class="pk-name">{{ evt.name }}</div>
                  <div class="pk-tags">
                    <span class="pk-tag" :class="evtType(evt) === '径赛' ? 'run' : 'field'">{{ evtType(evt) }}</span>
                    <span class="pk-tag plain">{{ genderLabel(evt.gender) }}</span>
                  </div>
                  <div class="pk-state">
                    <span v-if="isRegSingle(evt)" class="ok">✓ 已报</span>
                    <span v-else-if="!canSingle(evt)" class="no">{{ whyOff(evt) }}</span>
                    <span v-else class="go">点击报名</span>
                  </div>
                </div>
              </div>
              <div v-else-if="foundAthlete" class="cat-empty">该项目类下暂无未报且性别符合的项目</div>
            </div>
          </el-tab-pane>

          <!-- ---------- 按项目批量 ---------- -->
          <el-tab-pane name="batch">
            <template #label><span class="tab-ico">👥</span> 按项目批量报名</template>
            <div class="batch-box">
              <!-- ① 选项目：方块/列表双视图（默认方块） -->
              <div class="batch-step-title">① 选择项目</div>
              <div class="batch-tools">
                <el-input v-model="batchEvtKw" placeholder="搜索项目名" clearable size="small" style="width: 180px">
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-radio-group v-model="batchEvtCat" size="small">
                  <el-radio-button value="">全部</el-radio-button>
                  <el-radio-button value="径赛">径赛</el-radio-button>
                  <el-radio-button value="田赛">田赛</el-radio-button>
                </el-radio-group>
              </div>
              <div class="pick-zone evt-zone" :class="viewMode === 'list' ? 'row' : ''">
                <div v-for="evt in batchEventOptions" :key="evt.id" class="pk"
                  :class="{ picked: batchEventId === evt.id }" @click="batchEventId = evt.id">
                  <div class="pk-name">{{ evt.name }}</div>
                  <div class="pk-tags">
                    <span class="pk-tag" :class="evtType(evt) === '径赛' ? 'run' : 'field'">{{ evtType(evt) }}</span>
                    <span class="pk-tag plain">{{ genderLabel(evt.gender) }}</span>
                  </div>
                  <div class="pk-state">
                    <span class="go">已报 {{ batchRegCountOf(evt) }}/3</span>
                  </div>
                  <span v-if="batchEventId === evt.id" class="pk-mark">✓</span>
                </div>
                <div v-if="!batchEventOptions.length" class="cat-empty">没有匹配的项目</div>
              </div>

              <!-- ② 勾选学生 -->
              <template v-if="batchEvent">
                <div class="batch-step-title">② 勾选学生（{{ genderLabel(batchEvent.gender) }} ·
                  剩余名额 {{ quotaLeft }} 人 · 本项目已报 {{ classEventRegs.length }} 人）</div>
                <div class="batch-tools">
                  <el-input v-model="batchKw" placeholder="过滤姓名 / 学号" clearable size="small" style="width: 200px">
                    <template #prefix><el-icon><Search /></el-icon></template>
                  </el-input>
                  <el-button size="small" :disabled="batchCandidates.length === 0 || quotaLeft <= 0"
                    @click="checkAllBatch">全选可报名</el-button>
                  <span class="batch-quota-tip">
                    每项目限报 3 人/班（勾选上限 = 剩余名额），提交后待审核
                  </span>
                </div>

                <div class="pick-zone people-zone" :class="viewMode === 'list' ? 'row' : ''">
                  <div v-for="a in batchCandidates" :key="a.id" class="pk"
                    :class="{
                      picked: checkedIds.includes(a.id),
                      off: !checkedIds.includes(a.id) && checkedIds.length >= quotaLeft
                    }"
                    @click="toggleBatchId(a.id)">
                    <span class="pk-ava" :class="a.gender === 'M' ? 'male' : 'female'">
                      {{ (a.name || '?').slice(0, 1) }}
                    </span>
                    <div class="pk-body">
                      <div class="pk-name">
                        {{ a.name }}
                        <span class="pk-gender" :class="a.gender === 'M' ? 'male' : 'female'">
                          {{ a.gender === 'M' ? '男' : '女' }}
                        </span>
                      </div>
                      <div class="pk-sub">学号 {{ a.studentNo || a.studentId || '' }} · 已报 {{ regOf(a).length }}/3</div>
                    </div>
                    <div class="pk-state">
                      <span v-if="checkedIds.includes(a.id)" class="ok">✓ 已勾选</span>
                      <span v-else-if="checkedIds.length >= quotaLeft" class="no">名额已满</span>
                      <span v-else class="go">可报</span>
                    </div>
                  </div>
                  <div v-if="!batchCandidates.length" class="batch-empty">没有可报该项目的学生（可能均已报或名额已满/性别不符）</div>
                </div>

                <div class="batch-actions">
                  <el-button type="primary" size="large" :loading="batchSubmitting" :disabled="!checkedIds.length"
                    @click="submitBatch">
                    <el-icon><Check /></el-icon> 为 {{ checkedIds.length }} 名同学提交报名
                  </el-button>
                </div>
              </template>
            </div>
          </el-tab-pane>
        </el-tabs>

        <!-- ===== 报名清单 ===== -->
        <el-card shadow="never" class="panel-card list-card">
          <template #header>
            <div class="card-head">
              <span>📋 报名清单（{{ registrations.length }} 条）</span>
              <el-tag type="info" effect="plain" round>待审核 {{ pendingCount }} · 已通过 {{ approvedCount }}</el-tag>
            </div>
          </template>
          <el-table :data="registrations" size="small" border max-height="300">
            <el-table-column label="姓名" width="100">
              <template #default="{ row }">{{ row.athleteName }}</template>
            </el-table-column>
            <el-table-column label="学号" width="110">
              <template #default="{ row }">{{ sidOf(row.athleteName) }}</template>
            </el-table-column>
            <el-table-column prop="eventName" label="项目" min-width="140" />
            <el-table-column label="类型" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.eventType === '径赛' ? 'danger' : 'warning'" effect="plain">
                  {{ row.eventType || '-' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 'approved' ? 'success' : row.status === 'withdrawn' ? 'info' : 'warning'"
                  size="small">{{ row.status === 'approved' ? '已通过' : row.status === 'withdrawn' ? '已取消' : '待审核' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status !== 'withdrawn'" type="primary" size="small" link
                  @click="openPickerByName(row.athleteName)">补报</el-button>
                <el-button v-if="row.status !== 'withdrawn'" type="danger" size="small" link @click="cancelReg(row)">取消</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </section>

      <!-- 右侧：快捷信息 -->
      <aside class="ws-side">
        <el-card shadow="never" class="panel-card side-card">
          <template #header><div class="card-head">⚠️ 尚未报名的学生</div></template>
          <div v-if="unregisteredAthletes.length"
            class="pick-zone side-zone" :class="viewMode === 'list' ? 'row' : ''">
            <div v-for="a in unregisteredAthletes" :key="a.id" class="pk unreg" @click="openPicker(a)">
              <span class="pk-ava" :class="a.gender === 'M' ? 'male' : 'female'">
                {{ (a.name || '?').slice(0, 1) }}
              </span>
              <div class="pk-body">
                <div class="pk-name">{{ a.name }}</div>
                <div class="pk-sub">学号 {{ a.studentNo || a.studentId || '' }}</div>
              </div>
              <div class="pk-state"><span class="go">去报名</span></div>
            </div>
          </div>
          <div v-else class="side-empty">太棒了，本班学生都已报名 🎉</div>
        </el-card>
        <el-card shadow="never" class="panel-card side-card">
          <template #header><div class="card-head">📌 报名说明</div></template>
          <ul class="side-tips">
            <li>现场/逐个报名 → <b>待审核</b>，体育老师通过后才生效；</li>
            <li>批量导入选「后置导入（已报好表）」可<b>直接通过</b>；</li>
            <li>每人最多报 3 项、每项目每班限 3 人（体育老师可在积分/系统配置调整）；</li>
            <li>性别不符 / 已报名 / 名额已满的项目会被拦截。</li>
          </ul>
        </el-card>
      </aside>
    </div>

    <!-- ===== 批量导入报名表（现场/后置） ===== -->
    <el-dialog v-model="showBatch" title="批量导入报名表" width="680px" :close-on-click-modal="false">
      <el-alert type="info" show-icon :closable="false" style="margin-bottom:12px"
        title="① 现场导入＝把现场收集的登记表整批导入（→待审核）；② 后置导入＝报名已定稿，直接置为已通过。" />
      <el-radio-group v-model="ctImportMode" style="margin-bottom:14px">
        <el-radio-button value="onsite">现场导入（→待审核）</el-radio-button>
        <el-radio-button value="offline">后置导入（已报好表 → 直接通过）</el-radio-button>
      </el-radio-group>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <input ref="ctFileInput" type="file" accept=".xlsx,.xls,.csv" style="display:none" @change="onCtFile" />
        <el-button type="primary" @click="ctFileInput && ctFileInput.click()"><el-icon><Upload /></el-icon> 选择报名表</el-button>
        <el-button link type="primary" @click="downloadSignupTemplate">下载表格1模板（年级|班级|姓名|性别|学号|项目|数量|成绩）</el-button>
      </div>
      <div v-if="ctFile" class="ct-file-line">
        <span>已选：{{ ctFile.name }}</span>
        <el-button type="success" :loading="ctImporting" @click="doCtImport">开始导入</el-button>
      </div>
      <el-alert v-if="ctResult" :type="ctResult.failed > 0 ? 'warning' : 'success'" show-icon style="margin-top:10px"
        :title="`导入完成：成功 ${ctResult.success} 条，重复跳过 ${ctResult.skipped} 条，失败 ${ctResult.failed} 条${ctResult.createdAthletes ? '，自动建档运动员 ' + ctResult.createdAthletes + ' 名' : ''}`" />
      <div v-if="ctResult && ctResult.errors && ctResult.errors.length" class="batch-errors">
        <div class="batch-errors-title">失败明细：</div>
        <div v-for="(e, i) in ctResult.errors" :key="i">第 {{ e.row }} 行：{{ e.message }}</div>
      </div>
    </el-dialog>

    <!-- ===== 学生多选报名弹窗（项目方块/列表双视图） ===== -->
    <el-dialog v-model="showPicker" width="820px" top="4vh" :close-on-click-modal="false">
      <template #header>
        <div class="picker-head">
          <span class="picker-avatar">{{ (picker.athlete?.name || '?').slice(0, 1) }}</span>
          <div class="picker-title">
            <b>{{ picker.athlete?.name }}</b>
            <span class="picker-sub">
              学号 {{ picker.athlete?.studentNo }} · {{ picker.athlete?.gender === 'M' ? '男' : '女' }} ·
              已报 {{ regOf(picker.athlete).length }}/3 项
            </span>
          </div>
          <span class="chip" style="background:#eff6ff;color:#2563eb">提交后待体育老师审核</span>
        </div>
      </template>

      <div class="picker-bar">
        <span class="picker-ok">已勾选 <b>{{ picker.checked.length }}</b> / 还可选 {{ pickerLeft }} 项</span>
        <el-radio-group v-model="pickerCat" size="small">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="径赛">径赛</el-radio-button>
          <el-radio-button value="田赛">田赛</el-radio-button>
        </el-radio-group>
        <el-input v-model="pickerKw" placeholder="搜索项目" clearable size="small" style="width:160px" />
        <div class="vt-switch sm">
          <button type="button" class="vt-btn" :class="{ on: viewMode === 'grid' }" @click="viewMode = 'grid'">▦</button>
          <button type="button" class="vt-btn" :class="{ on: viewMode === 'list' }" @click="viewMode = 'list'">☰</button>
        </div>
      </div>

      <!-- 项目堆表：小方块（默认）/ 横排列表，可多选 -->
      <div class="pick-zone picker-zone" :class="viewMode === 'list' ? 'row' : ''">
        <div v-for="evt in pickerProjects" :key="evt.id" class="pk"
          :class="{
            picked: picker.checked.includes(evt.id),
            off: !pickerCanPick(evt) && !picker.checked.includes(evt.id) && !isRegOf(picker.athlete, evt),
            done: isRegOf(picker.athlete, evt)
          }" @click="togglePick(evt)">
          <div class="pk-name">{{ evt.name }}</div>
          <div class="pk-tags">
            <span class="pk-tag" :class="evtType(evt) === '径赛' ? 'run' : 'field'">{{ evtType(evt) }}</span>
            <span class="pk-tag plain">{{ genderLabel(evt.gender) }}</span>
          </div>
          <div class="pk-state">
            <span v-if="isRegOf(picker.athlete, evt)" class="ok">✓ 已报</span>
            <span v-else-if="picker.checked.includes(evt.id)" class="ok">已勾选</span>
            <span v-else-if="!genderMatch(evt.gender, picker.athlete?.gender)" class="no">性别不符</span>
            <span v-else-if="classRegCount(evt) >= 3" class="no">班级已满(3)</span>
            <span v-else-if="regOf(picker.athlete).length + picker.checked.length >= 3" class="no">已达3项上限</span>
            <span v-else class="go">{{ classRegCount(evt) }}/3 班名额</span>
          </div>
          <span v-if="picker.checked.includes(evt.id)" class="pk-mark">✓</span>
        </div>
        <div v-if="!pickerProjects.length" class="picker-empty">
          {{ picker.athlete ? '没有更多可报项目（可能已报满 3 项，或所有项目性别/名额不符）' : '请先选择学生' }}
        </div>
      </div>

      <!-- 已选摘要 -->
      <div v-if="picker.checked.length" class="picker-selected">
        已选：
        <el-tag v-for="id in picker.checked" :key="id" closable type="primary" effect="light"
          @close="unpick(id)">{{ evtNameOf(id) }}</el-tag>
      </div>

      <template #footer>
        <el-button @click="closePicker">关闭</el-button>
        <el-button type="primary" :loading="pickerSubmitting" :disabled="!picker.checked.length"
          @click="submitPicker">
          <el-icon><Check /></el-icon> 为 {{ picker.athlete?.name }} 提交 {{ picker.checked.length }} 项报名
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Download, Upload, Check } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'

const loading = ref(false)
const athletes = ref([])
const events = ref([])
const registrations = ref([])

// 全页选择器视图：grid(方块，默认) | list(横排列表)
const viewMode = ref('grid')

// ---------- 概览 ----------
const regStats = computed(() => {
  const active = registrations.value.filter(r => r.status !== 'withdrawn')
  return { regCount: active.length, athleteCount: new Set(active.map(r => r.athleteName)).size }
})
const pendingCount = computed(() => registrations.value.filter(r => r.status === 'pending').length)
const approvedCount = computed(() => registrations.value.filter(r => r.status === 'approved').length)
const unregisteredAthletes = computed(() => {
  const has = new Set(registrations.value.filter(r => r.status !== 'withdrawn').map(r => r.athleteName))
  return athletes.value.filter(a => !has.has(a.name))
})
const coveragePct = computed(() => athletes.value.length
  ? Math.round(regStats.value.athleteCount / athletes.value.length * 100) : 0)

// ---------- 通用 ----------
function regOf(athlete) {
  if (!athlete) return []
  return registrations.value.filter(r => r.athleteName === athlete.name && r.status !== 'withdrawn')
}
function isRegOf(athlete, evt) {
  if (!athlete) return false
  return regOf(athlete).some(r => r.eventName === evt.name)
}
function sidOf(name) {
  const a = athletes.value.find(x => x.name === name)
  return a ? (a.studentNo || a.studentId || '') : ''
}
function evtType(evt) {
  if (evt.eventType) return evt.eventType
  if (evt.isTrack === false) return '田赛'
  return evt.isTrack ? '径赛' : (evt.category || '径赛')
}
function normGender(g) {
  const t = String(g || '').trim()
  if (['M', '男', '男子组', '男子'].includes(t)) return 'M'
  if (['F', '女', '女子组', '女子'].includes(t)) return 'F'
  return ''
}
function genderLabel(g) {
  const n = normGender(g)
  return n === 'M' ? '男子' : n === 'F' ? '女子' : '混合'
}
function genderMatch(limit, athleteGender) {
  const l = normGender(limit)
  if (!l) return true
  const a = normGender(athleteGender)
  return !a || a === l
}

// ---------- 单人现场报名 ----------
const workMode = ref('single')
const searchKw = ref('')
const singleCat = ref('')
const foundAthlete = computed(() => {
  const kw = searchKw.value.trim()
  if (!kw) return null
  return athletes.value.find(a => a.name === kw || (a.studentNo && a.studentNo === kw)) || null
})
const searched = computed(() => !!searchKw.value.trim())
const singleProjects = computed(() => {
  if (!foundAthlete.value) return []
  const cat = singleCat.value
  return events.value.filter(evt => {
    if (cat && evtType(evt) !== cat) return false
    if (isRegOf(foundAthlete.value, evt)) return true // 已报也展示（打勾态）
    return canReg(foundAthlete.value, evt)
  })
})
function canReg(athlete, evt) {
  if (!athlete) return false
  if (isRegOf(athlete, evt)) return false
  if (!genderMatch(evt.gender, athlete.gender)) return false
  if (regOf(athlete).length >= 3) return false
  return true
}
function isRegSingle(evt) { return isRegOf(foundAthlete.value, evt) }
function canSingle(evt) { return canReg(foundAthlete.value, evt) }
function whyOff(evt) {
  if (!genderMatch(evt.gender, foundAthlete.value?.gender)) return '性别不符'
  if (regOf(foundAthlete.value).length >= 3) return '已满3项'
  return '不可报'
}

// ---------- 学生多选报名（弹窗 + 项目堆表/列表） ----------
const showPicker = ref(false)
const pickerCat = ref('')
const pickerKw = ref('')
const pickerSubmitting = ref(false)
const picker = reactive({ athlete: null, checked: [] })

function openPicker(athlete, presetEvent) {
  if (!athlete) return
  picker.athlete = athlete
  picker.checked = presetEvent ? [presetEvent.id] : []
  pickerCat.value = ''
  pickerKw.value = ''
  showPicker.value = true
}
function openPickerByName(name) {
  const a = athletes.value.find(x => x.name === name)
  if (a) openPicker(a)
  else ElMessage.warning('未找到该学生，可能已从名单移除')
}
function closePicker() { showPicker.value = false; picker.athlete = null; picker.checked = [] }
function unpick(id) { picker.checked = picker.checked.filter(x => x !== id) }
function evtNameOf(id) { const e = events.value.find(x => x.id === id); return e ? e.name : '' }
function classRegCount(evt) {
  return registrations.value.filter(r => r.eventName === evt.name && r.status !== 'withdrawn').length
}
const pickerLeft = computed(() => {
  const used = picker.athlete ? regOf(picker.athlete).length + picker.checked.length : 0
  return Math.max(0, 3 - used)
})
const pickerProjects = computed(() => {
  const a = picker.athlete
  if (!a) return []
  const kw = pickerKw.value.trim()
  return events.value.filter(evt => {
    if (isRegOf(a, evt)) return true // 已报项目灰显占位（提示不可重复）
    if (kw && evt.name.indexOf(kw) < 0) return false
    if (pickerCat.value && evtType(evt) !== pickerCat.value) return false
    return true
  })
})
function pickerCanPick(evt) {
  const a = picker.athlete
  if (!a || !evt) return false
  if (picker.checked.includes(evt.id)) return true // 已勾选的可再点取消
  if (isRegOf(a, evt)) return false
  if (!genderMatch(evt.gender, a.gender)) return false
  if (classRegCount(evt) >= 3) return false
  if (regOf(a).length + picker.checked.length >= 3) return false
  return true
}
function togglePick(evt) {
  const a = picker.athlete
  if (!a) return
  if (picker.checked.includes(evt.id)) { unpick(evt.id); return }
  if (isRegOf(a, evt)) { ElMessage.info('该生已报「' + evt.name + '」'); return }
  if (!genderMatch(evt.gender, a.gender)) { ElMessage.warning('性别不符合「' + evt.name + '」'); return }
  if (classRegCount(evt) >= 3) { ElMessage.warning('该项目本班名额已满(3人)'); return }
  if (regOf(a).length + picker.checked.length >= 3) { ElMessage.warning('每名学生最多报 3 项，已达上限'); return }
  picker.checked = [...picker.checked, evt.id]
}
async function submitPicker() {
  const a = picker.athlete
  if (!a || !picker.checked.length) return
  pickerSubmitting.value = true
  let ok = 0
  const fails = []
  for (const id of picker.checked) {
    try {
      await request.post('/class-teacher/register', { athleteId: a.id, eventId: id })
      ok++
    } catch (e) {
      fails.push(evtNameOf(id) + '：' + (e?.response?.data?.message || e?.message || '失败'))
    }
  }
  ElMessage[ok ? 'success' : 'error'](ok
    ? `${a.name} 已提交 ${ok} 项报名（待审核）${fails.length ? '，' + fails.length + ' 项失败' : ''}`
    : '提交失败')
  if (fails.length) ElMessage.warning(fails[0])
  await refreshRegs()
  closePicker()
  pickerSubmitting.value = false
}

// ---------- 按项目批量报名 ----------
const batchEventId = ref('')
const batchEvtKw = ref('')
const batchEvtCat = ref('')
const batchKw = ref('')
const batchSubmitting = ref(false)
const checkedIds = ref([])
const batchEvent = computed(() => events.value.find(e => e.id === batchEventId.value) || null)
const batchEventOptions = computed(() => {
  const kw = batchEvtKw.value.trim()
  const cat = batchEvtCat.value
  return events.value.filter(evt => {
    if (kw && evt.name.indexOf(kw) < 0) return false
    if (cat && evtType(evt) !== cat) return false
    return true
  })
})
const classEventRegs = computed(() => batchEvent.value
  ? registrations.value.filter(r => r.eventName === batchEvent.value.name && r.status !== 'withdrawn')
  : [])
const quotaLeft = computed(() => Math.max(0, 3 - classEventRegs.value.length))
const batchCandidates = computed(() => {
  if (!batchEvent.value) return []
  const kw = batchKw.value.trim()
  return athletes.value.filter(a => {
    if (kw && a.name.indexOf(kw) < 0 && (a.studentNo || '').indexOf(kw) < 0) return false
    if (isRegOf(a, batchEvent.value)) return false
    if (!genderMatch(batchEvent.value.gender, a.gender)) return false
    return true
  })
})
function batchRegCountOf(evt) {
  return registrations.value.filter(r => r.eventName === evt.name && r.status !== 'withdrawn').length
}
watch(batchEventId, () => { checkedIds.value = []; batchKw.value = '' })
function toggleBatchId(id) {
  if (checkedIds.value.includes(id)) {
    checkedIds.value = checkedIds.value.filter(x => x !== id)
    return
  }
  if (checkedIds.value.length >= quotaLeft.value) {
    ElMessage.warning(`该项目每班限报 3 人，剩余名额 ${quotaLeft.value} 人，请先取消已勾选`)
    return
  }
  checkedIds.value = [...checkedIds.value, id]
}
function checkAllBatch() {
  if (batchCandidates.value.length <= quotaLeft.value) {
    checkedIds.value = batchCandidates.value.map(a => a.id)
  } else {
    checkedIds.value = batchCandidates.value.slice(0, quotaLeft.value).map(a => a.id)
    ElMessage.info('名额有限，已自动勾选前 ' + quotaLeft.value + ' 人')
  }
}
async function submitBatch() {
  if (!batchEvent.value) return
  if (!checkedIds.value.length) { ElMessage.warning('请先勾选学生'); return }
  batchSubmitting.value = true
  let ok = 0
  const fails = []
  for (const id of checkedIds.value) {
    try {
      await request.post('/class-teacher/register', { athleteId: id, eventId: batchEvent.value.id })
      ok++
    } catch (e) {
      const a = athletes.value.find(x => x.id === id)
      fails.push((a ? a.name : id) + '：' + (e?.response?.data?.message || e?.message || '失败'))
    }
  }
  ElMessage[ok ? 'success' : 'error'](ok
    ? `已为 ${ok} 名同学提交「${batchEvent.value.name}」报名（待审核）${fails.length ? '，' + fails.length + ' 人失败' : ''}`
    : '提交失败')
  if (fails.length) console.warn(fails)
  checkedIds.value = []
  await refreshRegs()
  batchSubmitting.value = false
}

// ---------- 取消报名 ----------
async function cancelReg(row) {
  try {
    await ElMessageBox.confirm(`确定取消「${row.athleteName}」的「${row.eventName}」报名？`, '提示', { type: 'warning' })
    await request.delete('/class-teacher/register/' + row.id)
    ElMessage.success('已取消')
    refreshRegs()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

// ---------- 导出 ----------
async function exportRegistrations() {
  try {
    const token = localStorage.getItem('token') || ''
    const res = await fetch(apiBase() + '/class-teacher/registrations/export', {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (!res.ok) throw new Error('导出失败')
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = '报名表.xlsx'; a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) { ElMessage.error('导出失败') }
}

// ---------- 名单导入 ----------
const token = localStorage.getItem('token') || ''
const importRosterUrl = apiBase() + '/class-teacher/import-roster'
const uploadHeaders = computed(() => ({ Authorization: token ? `Bearer ${token}` : '' }))
function onImportSuccess(res) {
  const d = res?.data || res
  ElMessage.success(`导入完成：新增学生 ${d?.createdUsers || 0}，运动员 ${d?.createdAthletes || 0}，跳过 ${d?.skipped || 0}`)
  fetchAthletes()
  refreshRegs()
}
function onImportError() { ElMessage.error('导入失败，请检查文件格式') }
function downloadRosterTemplate() {
  const csv = '学号,姓名,性别\n2024001,张三,男\n2024002,李四,女'
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = '班级名单模板.csv'; a.click()
  URL.revokeObjectURL(url)
}

// ---------- 批量导入报名表（表格1：现场/后置） ----------
const showBatch = ref(false)
const ctImportMode = ref('onsite')
const ctFile = ref(null)
const ctImporting = ref(false)
const ctResult = ref(null)
const ctFileInput = ref(null)
function openImportDialog() {
  showBatch.value = true
  ctImportMode.value = 'onsite'
  ctFile.value = null
  ctResult.value = null
}
function downloadSignupTemplate() { window.open(apiBase() + '/registrations/template', '_blank') }
function onCtFile(e) { ctFile.value = e.target.files?.[0] || null; ctResult.value = null }
async function doCtImport() {
  if (!ctFile.value) return
  ctImporting.value = true
  ctResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', ctFile.value)
    const res = await request.post('/registrations/import-sheet', fd, {
      params: { source: ctImportMode.value },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    ctResult.value = res || {}
    ElMessage.success(`导入完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    refreshRegs()
    fetchAthletes()
  } catch (e) { console.error(e) }
  finally { ctImporting.value = false }
}

// ---------- 数据 ----------
async function fetchAthletes() {
  try {
    const res = await request.get('/class-teacher/athletes', { params: { page: 1, size: 500 } })
    athletes.value = res.records || []
  } catch (e) { console.error(e) }
}
async function fetchEvents() {
  try {
    const res = await request.get('/class-teacher/events')
    events.value = Array.isArray(res) ? res : (res.records || [])
  } catch (e) { console.error(e) }
}
async function refreshRegs() {
  try {
    const res = await request.get('/class-teacher/registrations')
    registrations.value = res.records || []
  } catch (e) { console.error(e) }
}
async function fetchAll() {
  loading.value = true
  await Promise.all([fetchAthletes(), fetchEvents(), refreshRegs()])
  loading.value = false
}
onMounted(fetchAll)
</script>

<style scoped>
.ct-reg { display: flex; flex-direction: column; gap: 14px; }

/* 概览条 */
.overview {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  background: #fff; border: 1px solid #e2e8f0; border-radius: 16px; padding: 12px 16px;
}
.ov-item { text-align: center; padding: 0 12px; border-right: 1px solid #eef2f7; min-width: 74px; }
.ov-item.main { border-left: 3px solid #3b82f6; }
.ov-item b { display: block; font-size: 24px; font-weight: 800; line-height: 1.1; color: #0f172a; }
.ov-item span { font-size: 11.5px; color: #64748b; }
.ov-item.warn b { color: #d97706; } .ov-item.danger b { color: #dc2626; }
.ov-item.ok b { color: #16a34a; }
.ov-progress { flex: 1; min-width: 240px; display: flex; align-items: center; gap: 8px; margin-left: 8px; }
.ov-progress-label { font-size: 12px; color: #475569; white-space: nowrap; }
.pg-track { flex: 1; height: 10px; border-radius: 999px; background: #e2e8f0; overflow: hidden; }
.pg-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #3b82f6, #6366f1); transition: width .6s; }
.ov-progress-num { font-size: 13px; font-weight: 700; color: #3b82f6; min-width: 42px; text-align: right; }

/* 视图切换条 */
.view-toolbar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: linear-gradient(120deg, #fff7ed, #fffbeb);
  border: 1px solid #fde68a; border-radius: 14px; padding: 8px 14px;
}
.vt-label { font-size: 13px; font-weight: 700; color: #78350f; }
.vt-switch { display: inline-flex; background: #f1f5f9; border-radius: 10px; padding: 3px; gap: 2px; }
.vt-btn {
  border: none; cursor: pointer; font-size: 13px; color: #475569;
  padding: 5px 14px; border-radius: 8px; background: transparent;
  display: inline-flex; align-items: center; gap: 4px; transition: all .18s; line-height: 1;
}
.vt-btn .vt-ico { font-size: 14px; }
.vt-btn:hover { color: #2563eb; }
.vt-btn.on { background: #fff; color: #2563eb; font-weight: 700; box-shadow: 0 1px 4px rgba(15,23,42,.12); }
.vt-switch.sm .vt-btn { padding: 4px 8px; font-size: 12px; }
.vt-hint { font-size: 12px; color: #b45309; }

.empty-roster-alert { border-radius: 12px; }
.workspace { display: grid; grid-template-columns: 1fr 300px; gap: 14px; align-items: start; }

/* 左栏 */
.ws-main { background: #fff; border: 1px solid #e2e8f0; border-radius: 16px; padding: 4px 16px 16px; }
.tab-ico { font-size: 15px; margin-right: 4px; }
.single-box, .batch-box { padding-top: 6px; }
.search-input { width: 100%; max-width: 480px; }

.person-bar {
  display: flex; align-items: center; gap: 12px;
  margin: 14px 0; padding: 12px 14px; border-radius: 14px;
  background: linear-gradient(120deg, #eff6ff, #eef2ff);
  border: 1px solid #dbeafe;
}
.person-avatar {
  width: 44px; height: 44px; border-radius: 50%; color: #fff; font-size: 20px;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.person-info { flex: 1; }
.person-name { font-size: 17px; font-weight: 800; display: flex; gap: 8px; align-items: center; }
.person-sub { font-size: 12px; color: #475569; margin-top: 3px; }
.person-regs { margin-left: 6px; color: #64748b; }
.person-none { color: #dc2626; padding: 10px 0; }

.cat-filter { display: flex; align-items: center; gap: 14px; margin: 6px 0 10px; }
.cat-hint { font-size: 12px; color: #94a3b8; }
.cat-empty { grid-column: 1 / -1; color: #94a3b8; text-align: center; padding: 20px 0; }

/* ===== 统一选择器：方块（默认）/ 横排列表 ===== */
.pick-zone {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px; max-height: 340px; overflow-y: auto; padding: 2px; align-content: start;
}
.pick-zone.evt-zone { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); max-height: 250px; }
.pick-zone.side-zone { max-height: 320px; }
.pick-zone.row { display: flex; flex-direction: column; gap: 6px; max-height: 380px; }

/* 卡片：默认竖向方块 */
.pk {
  position: relative; display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 10px 8px 8px; border: 1.5px solid #dbe4f0; border-radius: 12px; cursor: pointer;
  background: #fbfdff; text-align: center; transition: all .18s;
}
.pk:hover { border-color: #60a5fa; transform: translateY(-2px); box-shadow: 0 6px 14px rgba(59,130,246,.12); }
.pk.picked { background: linear-gradient(160deg, #eff6ff, #e0edff); border-color: #2563eb; box-shadow: 0 4px 14px rgba(37,99,235,.18); }
.pk.off { opacity: .45; cursor: not-allowed; }
.pk.off:hover { transform: none; box-shadow: none; border-color: #dbe4f0; }
.pk.done { background: #f0fdf4; border-color: #86efac; cursor: not-allowed; }
.pk-name { font-size: 13px; font-weight: 700; color: #0f172a; line-height: 1.35; }
.pk-sub { font-size: 11px; color: #64748b; line-height: 1.4; }
.pk-body { display: flex; flex-direction: column; align-items: center; gap: 1px; }
.pk-tags { display: flex; justify-content: center; gap: 4px; margin: 3px 0 2px; flex-wrap: wrap; }
.pk-tag { font-size: 10px; border-radius: 6px; padding: 1px 5px; }
.pk-tag.run { color: #dc2626; background: #fee2e2; }
.pk-tag.field { color: #b45309; background: #fef3c7; }
.pk-tag.plain { color: #475569; background: #f1f5f9; }
.pk-state { font-size: 11px; line-height: 1.3; }
.pk-state span { display: inline-block; padding: 1px 8px; border-radius: 999px; }
.pk-state .ok { color: #16a34a; background: #dcfce7; font-weight: 700; }
.pk-state .no { color: #dc2626; background: #fee2e2; font-weight: 700; }
.pk-state .go { color: #2563eb; background: #dbeafe; font-weight: 700; }
.pk-ava {
  width: 38px; height: 38px; border-radius: 50%; font-size: 16px; font-weight: 800; color: #fff;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.pk-ava.male { background: linear-gradient(135deg, #3b82f6, #6366f1); }
.pk-ava.female { background: linear-gradient(135deg, #ec4899, #f472b6); }
.pk-gender { font-size: 10px; font-weight: 600; padding: 0 4px; border-radius: 4px; vertical-align: 1px; }
.pk-gender.male { color: #2563eb; background: #dbeafe; }
.pk-gender.female { color: #db2777; background: #fce7f3; }

/* 选中角标 ✓（方块模式：右上角；列表模式：行内右端） */
.pk-mark {
  position: absolute; top: -7px; right: -7px; width: 22px; height: 22px;
  border-radius: 50%; background: #2563eb; color: #fff; font-size: 13px; font-weight: 800;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 8px rgba(37,99,235,.4);
}

/* 横排列表变体 */
.pick-zone.row .pk { flex-direction: row; text-align: left; align-items: center; gap: 10px; padding: 8px 12px; }
.pick-zone.row .pk-body { align-items: flex-start; flex: 1; min-width: 0; }
.pick-zone.row .pk-name { font-size: 14px; }
.pick-zone.row .pk-tags { margin: 0; flex: none; }
.pick-zone.row .pk-state { flex: none; margin-left: auto; }
.pick-zone.row .pk-mark { position: static; margin-left: auto; flex: none; }
.pick-zone.row .pk.done .pk-mark, .pick-zone.row .pk.unreg .pk-mark { display: none; }

/* 批量 tab */
.batch-step-title { font-weight: 800; font-size: 14px; color: #0f172a; margin: 8px 0 6px; }
.batch-tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.batch-quota-tip { font-size: 12px; color: #64748b; }
.batch-empty { grid-column: 1 / -1; color: #94a3b8; text-align: center; padding: 24px 0; }
.batch-actions { margin-top: 12px; display: flex; gap: 10px; align-items: center; }

/* 清单卡 */
.list-card { margin-top: 8px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 700; }
.panel-card { border-radius: 16px; }

/* 右栏 */
.ws-side { display: flex; flex-direction: column; gap: 14px; position: sticky; top: 8px; }
.side-card :deep(.el-card__body) { padding: 12px; }
.side-empty { color: #94a3b8; font-size: 13px; text-align: center; padding: 16px 0; }
.side-tips { margin: 0; padding-left: 18px; color: #475569; font-size: 12.5px; line-height: 1.9; }
.side-tips b { color: #0f172a; }

.ct-file-line {
  margin-top: 10px; padding: 8px 10px; border-radius: 10px; background: #f8fafc;
  display: flex; justify-content: space-between; align-items: center; font-size: 13px; color: #475569;
}
.batch-errors {
  margin-top: 10px; max-height: 200px; overflow-y: auto;
  border: 1px solid #fcd34d; border-radius: 10px; padding: 8px 12px; background: #fffbeb;
  font-size: 12px; color: #92400e; line-height: 1.8;
}
.batch-errors-title { font-weight: 700; margin-bottom: 4px; }

@media (max-width: 1100px) { .workspace { grid-template-columns: 1fr; } .ws-side { position: static; } }
@media (max-width: 720px) {
  .overview { gap: 4px; }
  .ov-item { min-width: 0; padding: 0 8px; }
  .ov-item b { font-size: 18px; }
  .ws-main { padding: 4px 8px 12px; }
  .pick-zone { grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); }
}

/* ===== 多选报名弹窗 ===== */
.picker-head { display: flex; align-items: center; gap: 12px; }
.picker-avatar {
  width: 42px; height: 42px; border-radius: 50%; color: #fff; font-size: 19px;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.picker-title { flex: 1; line-height: 1.4; }
.picker-title b { font-size: 17px; }
.picker-sub { display: block; font-size: 12px; color: #64748b; }
.picker-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  flex-wrap: wrap; margin: 4px 0 10px;
}
.picker-ok { font-size: 13px; color: #334155; }
.picker-ok b { color: #2563eb; font-size: 16px; }
.picker-zone { grid-template-columns: repeat(auto-fill, minmax(138px, 1fr)); }
.picker-empty { grid-column: 1 / -1; color: #94a3b8; text-align: center; padding: 26px 0; }
.picker-selected { margin-top: 10px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12.5px; color: #475569; }
</style>
