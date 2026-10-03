<template>
  <div class="schedule-page" v-loading="loading">
    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-tag type="primary" effect="dark" size="large" round>📅 赛程编排</el-tag>
          <span class="hint">先配置运动会日期/时段/年级顺序，再一键生成赛程（径赛串行、田赛并行）</span>
        </div>
        <div class="toolbar-right">
          <!-- U39/B36：规则模式 / 优化模式 / AI 模式 切换（三级求解梯度的最低层 vs 全链路求解 vs 全 AI 编排） -->
          <el-tooltip placement="top" effect="light">
            <template #content>
              <div style="max-width: 360px; line-height: 1.7">
                <b>规则模式</b>：确定性规则编排（蛇形分组 + 固定分道 + 时间栅格顺序放置），毫秒级出结果、完全可复现、参数透明可解释——与豪杰/索美同级<br/>
                <b>优化模式</b>：Timefold 约束求解 + 遗传算法 + 大邻域搜索，权衡兼项冲突与场地利用率，秒级出更优方案——本项目独有<br/>
                <b>AI 模式</b>：在优化链之上再跑 <b>ONNX 全本地推理</b>——算法选择器判「硬解 / 取消」、冲突簇 GNN 给单元定着色优先级、
                推理时自对抗（生成器 G↔判别器 D 多轮博弈择优）、道次由 AI 派遣款型排布，并回传可解性诊断与 AI 自检报告；
                模型缺失自动降级为优化模式，不会编排失败<br/>
                三种模式都经过同一套自检（场地重叠 / 赶场 / 漏排）与下界 gap 评估
              </div>
            </template>
            <el-radio-group v-model="arrangeMode" size="default" class="mode-switch" @change="onArrangeModeChange">
              <el-radio-button value="rule">规则模式</el-radio-button>
              <el-radio-button value="optimize">优化模式</el-radio-button>
              <el-radio-button value="ai" :class="{ 'is-ai-mode': true }">AI 模式</el-radio-button>
            </el-radio-group>
          </el-tooltip>
          <el-button :icon="Setting" @click="openMeetConfig">运动会日程配置</el-button>
          <el-button :icon="Calendar" @click="showProtection = true">规避时间</el-button>
          <el-button type="primary" :icon="MagicStick" @click="doAutoSchedule">
            {{ arrangeMode === 'rule' ? '按规则编排' : (arrangeMode === 'ai' ? 'AI 智能编排' : '一键编排赛程') }}
          </el-button>
          <el-button type="success" :icon="Download" @click="exportSheet" :disabled="!items.length">导出赛程表</el-button>
          <el-button type="warning" :icon="RefreshLeft" @click="clearAll" :disabled="!items.length">清空</el-button>
        </div>
      </div>
    </el-card>

    <!-- 编排进度：编排要跑「求解 → GA/LNS/MNSA/ALNS/Fix-opt 精修链 → 对抗自检」，秒级到十秒级。
         进度条把「看不见的等待」变成「看得见的阶段」，也是长链路卡死时唯一的现场信号。 -->
    <el-card v-if="arrangeProgress.active" shadow="never" style="border-radius: 10px; margin-bottom: 12px">
      <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px">
        <span style="font-weight: 600">
          <el-icon class="is-loading" style="vertical-align: -2px; margin-right: 6px"><MagicStick /></el-icon>
          {{ arrangeProgress.stage || '编排中' }}
        </span>
        <span style="color: #909399; font-size: 13px">{{ arrangeProgress.message }}</span>
        <span style="font-weight: 600; color: #409eff">{{ arrangeProgress.percent }}%</span>
      </div>
      <el-progress :percentage="arrangeProgress.percent" :stroke-width="14" striped striped-flow
                   :status="arrangeProgress.percent >= 100 ? 'success' : ''" />
    </el-card>

    <!-- 可解性诊断：编排完成不等于「排得下」。这里如实回显「哪些排不下、为什么、怎么办」——
         容量缺口/超大单元/团下界三类原因分开呈现，并给出加天/加场地/取消报名的建议。 -->
    <el-alert v-if="lastFeasibility && !lastFeasibility.feasible" type="warning" show-icon
              :closable="false" style="border-radius: 10px; margin-bottom: 12px">
      <template #title>
        可解性诊断：{{ lastFeasibility.summary?.placed }}/{{ lastFeasibility.summary?.tasks }} 个组次可排
        （{{ Math.round((lastFeasibility.summary?.placedRatio || 0) * 100) }}%），
        {{ lastFeasibility.summary?.unplaced }} 个组次排不下
      </template>
      <template #default>
        <div style="line-height: 1.9; font-size: 13px">
          <div v-for="(c, i) in (lastFeasibility.conflicts || []).slice(0, 3)" :key="i">
            · <b>{{ conflictKindLabel(c.type) }}</b>：{{ c.message }}
          </div>
          <div v-if="(lastFeasibility.actions || []).length" style="margin-top: 4px; color: #b88230">
            建议：{{ actionSummary(lastFeasibility.actions) }}
          </div>
        </div>
      </template>
    </el-alert>

    <el-alert type="info" show-icon :closable="false" style="border-radius: 10px">
      <template #title>
        编排规则：项目按年级出场顺序展开（可在「运动会日程配置」中自定义，或跟随系统设置的年级管理）；
        径赛默认串行独占跑道依次进行，田赛默认并行多场地同时开赛；时长按报名人数估算并受项目最大用时封顶，项目之间留出间隔。日期/时段全部来自日程配置，可每天不同。
      </template>
      <div v-if="lastArrangeMode" style="margin-top: 4px">
        <el-tag size="small" :type="lastArrangeMode === 'rule' ? 'warning' : (lastArrangeMode === 'ai' ? 'danger' : 'success')" effect="plain">
          当前赛程由「{{ modeLabel(lastArrangeMode) }}」生成
          <template v-if="lastArrangeMode === 'rule' && lastRuleInfo">
            · 耗时 {{ lastRuleInfo.elapsedMillis }}ms · 残余兼项冲突 {{ lastRuleInfo.residualConflicts }} 处
          </template>
        </el-tag>
        <span v-if="lastArrangeMode === 'rule'" style="margin-left: 8px; font-size: 12px; color: #909399">
          想要更优的兼项规避与场地利用率？切换「优化模式 / AI 模式」重新编排
        </span>
        <!-- AI 模式专属：把「模型到底跑了没有 / 博弈了几轮 / 候选方案是否更优」如实回显，
             避免「点了 AI 模式其实静默回退规则」却看不出来（模型缺失时后端会降级并写在 note 里）。 -->
        <template v-if="lastMode === 'ai' && lastAiReport">
          <el-tag size="small" :type="lastAiReport.adversarial === 'enabled' ? 'danger' : 'info'" effect="plain"
                  style="margin-left: 8px">
            AI 自对抗：{{ lastAiReport.adversarial === 'enabled'
              ? `${lastAiReport.roundsUsed || 0} 轮 · D 分 ${lastAiReport.dScore} · 候选冲突 ${lastAiReport.conflict}${lastAiReport.improved ? '（优于单次生成）' : ''}`
              : (lastAiReport.adversarial === 'error' ? '执行异常' : '不可用（已降级）') }}
          </el-tag>
          <span v-if="lastAiReport.note" style="margin-left: 8px; font-size: 12px; color: #909399">
            {{ lastAiReport.note }}
          </span>
        </template>
      </div>
    </el-alert>

    <!-- 空态 -->
    <el-card v-if="!items.length" shadow="never" class="empty-card">
      <el-empty description="暂无赛程，先配置运动会日程，再点击「一键编排赛程」">
        <el-button type="primary" :icon="MagicStick" @click="doAutoSchedule">立即编排</el-button>
      </el-empty>
    </el-card>

    <!-- 赛程展示（按天分组） -->
    <template v-else>
      <el-card v-for="(dayItems, day) in groupedByDay" :key="day" shadow="never" class="day-card">
        <template #header>
          <div class="day-header">
            <span class="day-title">🏅 第 {{ day }} 天</span>
            <el-tag type="info" effect="plain" round v-if="dayItems[0]?.scheduleDate">
              {{ dayItems[0].scheduleDate }}
            </el-tag>
            <el-tag type="info" effect="plain" round>{{ dayItems.length }} 个单元</el-tag>
          </div>
        </template>

        <!-- 按时段分组 -->
        <div v-for="(slotItems, slot) in groupBySlot(dayItems)" :key="slot" class="slot-block">
          <div class="slot-title">
            <el-tag :type="slotTag(slot)" effect="plain">{{ slot }}</el-tag>
            <span class="slot-time" v-if="slotItems[0]?.startTime">{{ slotItems[0].startTime }} 起</span>
          </div>
          <el-table :data="slotItems" size="small" border stripe>
            <el-table-column prop="grade" label="年级" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="info" effect="plain">{{ row.grade || '不分年级' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="eventName" label="项目名称" min-width="170">
              <template #default="{ row }">
                <span v-if="row.isTeam" class="team-mark" title="团体赛">团</span>
                {{ row.eventName }}
              </template>
            </el-table-column>
            <el-table-column label="轮次" width="76" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.round === 'preliminary' ? 'warning' : 'primary'" effect="plain">
                  {{ row.round === 'preliminary' ? '预赛' : '决赛' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="startTime" label="开始" width="90" align="center" />
            <el-table-column prop="endTime" label="结束" width="90" align="center" />
            <el-table-column label="场地" min-width="150" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">
                  {{ row.venue }}<template v-if="venueCode(row.venue)">（{{ venueCode(row.venue) }}）</template>
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类别/道次" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isTrack ? 'success' : 'warning'">
                  {{ row.isTrack ? '径赛' : '田赛' }}{{ row.isTrack ? ` ·${row.laneCount}道` : '' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="durationMinutes" label="用时" width="80" align="center">
              <template #default="{ row }">{{ row.durationMinutes }} 分</template>
            </el-table-column>
            <el-table-column label="备注" min-width="140">
              <template #default="{ row }">
                <span class="row-remark">{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="205" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" :icon="EditPen" @click="openEdit(row)">调整</el-button>
                <!-- 已录入成绩的项目：查看成绩 / 再次排道 -->
                <template v-if="hasResult(row)">
                  <el-button type="success" link size="small" @click="openResults(row)">查看成绩</el-button>
                  <el-button type="warning" link size="small" @click="rearrangeRow(row)">再次排道</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>
    </template>

    <!-- 兼项自动统计：哪些运动员兼了项、兼了几项（编排前自动汇总，不需要点按钮） -->
    <el-card shadow="never" class="conflict-card">
      <template #header>
        <div class="conflict-header">
          <span>📊 兼项自动统计</span>
          <span class="hint">进入本页即自动汇总已审核报名里的兼项运动员；兼项越多越易撞车，据此设定「项目编排顺序」错峰</span>
          <div class="conflict-actions">
            <el-tag v-if="coSummary" size="small" effect="plain">
              兼项 {{ coSummary.multiEventAthletes }} 人（{{ coRatioText }}）· 最高兼 {{ coSummary.maxEvents }} 项
            </el-tag>
            <el-button size="small" type="primary" plain :icon="Refresh" :loading="coLoading" @click="loadCooccurrence(false)">
              刷新
            </el-button>
            <el-tooltip placement="top" effect="light">
              <template #content>导出「兼项运动员统计.xlsx」：姓名 / 号码布 / 班级 / 兼项数 / 涉及项目，方便现场排表与临时改项</template>
              <el-button size="small" type="success" plain :icon="Download"
                :disabled="!coAthletes.length" @click="exportMultiEvent">
                导出名单
              </el-button>
            </el-tooltip>
          </div>
        </div>
      </template>

      <!-- 概览：4 个关键数字 + 兼项项数分布 -->
      <div v-if="coSummary" class="co-overview">
        <div class="co-metric">
          <div class="co-metric-num">{{ coSummary.totalApproved }}</div>
          <div class="co-metric-label">已审核报名</div>
        </div>
        <div class="co-metric">
          <div class="co-metric-num">{{ coSummary.athleteCount }}</div>
          <div class="co-metric-label">参赛运动员</div>
        </div>
        <div class="co-metric is-hot">
          <div class="co-metric-num">{{ coSummary.multiEventAthletes }}</div>
          <div class="co-metric-label">兼项运动员</div>
        </div>
        <div class="co-metric is-hot">
          <div class="co-metric-num">{{ coSummary.maxEvents }}</div>
          <div class="co-metric-label">最高兼项数</div>
        </div>
        <div class="co-dist">
          <div class="co-dist-title">兼项项数分布</div>
          <div v-for="d in coSummary.distribution" :key="d.label" class="co-dist-row">
            <span class="co-dist-label">{{ d.label }}</span>
            <el-progress :percentage="distPercent(d)" :stroke-width="9" :show-text="false" color="#7c3aed" />
            <span class="co-dist-num">{{ d.count }} 人</span>
          </div>
        </div>
      </div>

      <el-empty
        v-if="!coSummary && !coLoading"
        description="暂无已审核报名 —— 报名审核通过后，兼项统计会自动出现在这里"
        :image-size="64" />

      <el-skeleton v-if="coLoading && !coSummary" animated :rows="3" />

      <el-tabs v-if="coSummary" v-model="coTab" class="co-tabs">
        <!-- 兼项运动员名单（自动统计的主角） -->
        <el-tab-pane name="athletes">
          <template #label>兼项运动员（{{ coAthletes.length }}）</template>
          <div class="co-toolbar">
            <el-input v-model="coKeyword" size="small" clearable style="width:240px"
              placeholder="搜索姓名 / 号码 / 班级 / 项目" :prefix-icon="Search" />
            <el-tag size="small" type="info">点击行可展开该运动员报名的全部项目</el-tag>
          </div>
          <el-table :data="coPaged" border stripe size="small" max-height="360" row-key="athleteId"
            @row-click="(r) => (r._open = !r._open)">
            <el-table-column label="#" width="46" align="center" type="index" />
            <el-table-column prop="name" label="运动员" width="96" />
            <el-table-column prop="number" label="号码布" width="92" />
            <el-table-column prop="className" label="班级" min-width="110" show-overflow-tooltip />
            <el-table-column prop="grade" label="年级" width="88" />
            <el-table-column label="兼项数" width="92" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.eventCount >= 5 ? 'danger' : (row.eventCount >= 3 ? 'warning' : 'primary')">
                  {{ row.eventCount }} 项
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="eventNamesText" label="报名项目" min-width="260" show-overflow-tooltip />
          </el-table>
          <el-pagination
            v-if="coFiltered.length > coPageSize"
            v-model:current-page="coPage"
            :page-size="coPageSize"
            :total="coFiltered.length"
            layout="total, prev, pager, next"
            style="margin-top:10px;justify-content:flex-end" />
        </el-tab-pane>

        <!-- 高频共现项目对：哪些项目常被同一批人同时报 -->
        <el-tab-pane name="pairs">
          <template #label>高频共现项目对（{{ coPairs.length }}）</template>
          <el-table v-if="coPairs.length" :data="coPairs" border stripe size="small" max-height="360">
            <el-table-column label="#" width="44" align="center" type="index" />
            <el-table-column label="项目A" min-width="130">
              <template #default="{ row }">{{ row.eventA?.name || '' }}</template>
            </el-table-column>
            <el-table-column label="项目B" min-width="130">
              <template #default="{ row }">{{ row.eventB?.name || '' }}</template>
            </el-table-column>
            <el-table-column label="共同报名人数" width="120" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.commonAthletes >= 5 ? 'danger' : 'warning'">{{ row.commonAthletes }} 人</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无共现项目对" :image-size="60" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- B06/U05：兼项冲突检测 —— 同一运动员在相近时间被排到不同项目 -->
    <el-card shadow="never" class="conflict-card">
      <template #header>
        <div class="conflict-header">
          <span>⚔️ 兼项冲突检测</span>
          <span class="hint">同一运动员的两个项目时间重叠（或间隔小于 15 分钟）时告警，附根因与调整建议</span>
          <div class="conflict-actions">
            <el-tag v-if="conflictSummary" size="small"
              :type="conflictSummary.blocker ? 'danger' : (conflictSummary.total ? 'warning' : 'success')">
              共 {{ conflictSummary.total }} 处（严重 {{ conflictSummary.blocker }} / 一般 {{ conflictSummary.warn }}），涉及 {{ conflictSummary.athleteCount }} 人
            </el-tag>
            <el-button size="small" type="primary" plain :icon="Search" :loading="conflictLoading" @click="loadConflicts">
              检测冲突
            </el-button>
            <el-button size="small" type="success" plain :icon="Download"
              :disabled="!conflictList.length" @click="exportConflicts">
              导出清单
            </el-button>
            <el-button size="small" type="warning" plain :icon="Remove"
              :disabled="!conflictList.length" @click="openCancelDialog">
              取消冲突项目
            </el-button>
            <el-tooltip placement="top" effect="light">
              <template #content>
                <div style="max-width: 300px; line-height: 1.7">
                  检测到兼项冲突后，点击此处触发「无限轮重排」：以真实冲突口径反复扰动重排，
                  直到冲突归零或收敛到当前配置下的最低值（上限 60 趟精修）。完成后自动刷新冲突清单。
                </div>
              </template>
              <el-button size="small" type="danger" plain :icon="MagicStick"
                :loading="resolveLoading"
                :disabled="!items.length || !(conflictSummary && conflictSummary.total)"
                @click="resolveConflicts">
                一键消解
              </el-button>
            </el-tooltip>
          </div>
        </div>
      </template>

      <template v-if="conflictList.length">
        <el-table :data="conflictPaged" border stripe size="small" max-height="420">
          <el-table-column type="index" label="#" width="46" align="center" />
          <el-table-column prop="severity" label="严重度" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.severity === '严重' ? 'danger' : 'warning'">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="112" />
          <el-table-column prop="athleteName" label="运动员" width="88" />
          <el-table-column prop="athleteNumber" label="号码布" width="84" />
          <el-table-column prop="eventAName" label="项目A" min-width="110" show-overflow-tooltip />
          <el-table-column prop="windowA" label="A 时间/场地" min-width="160" show-overflow-tooltip />
          <el-table-column prop="eventBName" label="项目B" min-width="110" show-overflow-tooltip />
          <el-table-column prop="windowB" label="B 时间/场地" min-width="160" show-overflow-tooltip />
          <el-table-column prop="gapMinutes" label="间隔(分)" width="84" align="center" />
          <el-table-column prop="suggestion" label="调整建议" min-width="260" show-overflow-tooltip />
        </el-table>
        <el-pagination
          v-if="conflictList.length > conflictPageSize"
          v-model:current-page="conflictPage"
          :page-size="conflictPageSize"
          :total="conflictList.length"
          layout="total, prev, pager, next"
          style="margin-top:10px;justify-content:flex-end" />
      </template>

      <el-empty v-else
        :description="conflictSummary ? '未检测到兼项冲突' : '点击「检测冲突」检查是否存在兼项冲突'"
        :image-size="70" />
    </el-card>

    <!-- 运动会日程配置对话框（日期/时段/年级顺序/串行并行 全部可配置，不硬编码） -->
    <el-dialog v-model="showConfigDialog" title="运动会日程配置" width="860px" :close-on-click-modal="false"
      top="4vh">
      <el-form label-width="130px" label-position="left">
        <el-form-item label="运动会名称">
          <el-input v-model="meetForm.meetName" maxlength="40" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="meetForm.startDate" type="date" value-format="YYYY-MM-DD"
            placeholder="选择第一天日期" style="width: 220px" @change="syncDates" />
          <span class="hint" style="margin-left:12px">共 {{ meetForm.days }} 天，每天具体日期自动顺延</span>
        </el-form-item>
        <el-form-item label="时间目标">
          <div style="display:flex;align-items:center;gap:12px;width:100%">
            <!-- 与球类 daysLimit 同一套契约：限定 x 天 / 0 不限 / -1 尽可能减少 -->
            <el-select v-model="meetForm.dayMode" style="width:140px">
              <el-option label="限定天数" value="fixed" />
              <el-option label="不限" value="unlimited" />
              <el-option label="尽可能减少" value="minimize" />
            </el-select>
            <el-input-number v-if="meetForm.dayMode === 'fixed'" v-model="meetForm.days"
                             :min="1" :max="10" @change="syncDays" />
            <span v-else class="hint">
              {{ meetForm.dayMode === 'unlimited'
                ? '不限制天数：按每天时段容量推算需要几天就排几天（输入 0）'
                : '不限天数但尽可能压缩工期：求解器会尽量把项目挤进同一时段、避免多占一天（输入 -1）' }}
            </span>
          </div>
        </el-form-item>
        <el-form-item label="兼项缓冲(分钟)">
          <div style="display:flex;align-items:center;gap:12px;width:100%">
            <el-input-number v-model="meetForm.conflictBufferMinutes" :min="0" :max="120" :step="5" />
            <span class="hint">同一运动员两个项目之间至少间隔多久；调大更保守、调小更紧凑</span>
          </div>
        </el-form-item>
        <el-form-item v-if="arrangeMode === 'ai'" label="AI 对抗轮数">
          <div style="display:flex;align-items:center;gap:12px;width:100%">
            <el-input-number v-model="meetForm.aiAdversarialRounds" :min="1" :max="10" />
            <span class="hint">仅 AI 模式生效：生成→精修→评判→择优的轮数，越多越优但越慢</span>
          </div>
        </el-form-item>
        <el-form-item label="年级出场顺序">
          <div style="width:100%">
            <el-switch v-model="useCustomOrder" inline-prompt active-text="自定义顺序" inactive-text="跟随年级设置"
              style="margin-bottom:8px" @change="onCustomOrderChange" />
            <template v-if="!useCustomOrder">
              <div>
                <el-tag v-for="g in meetForm.gradeOrder" :key="g" style="margin-right:6px" size="small">
                  {{ g }}
                </el-tag>
              </div>
              <span class="hint" style="display:block;margin-top:6px">
                出场顺序实时跟随「系统设置 → 年级管理」的 sortOrder；调整后重新点击「一键编排赛程」即生效。
              </span>
            </template>
            <template v-else>
              <div class="grade-order-edit">
                <div v-for="(g, gi) in meetForm.gradeOrder" :key="g" class="grade-order-row">
                  <span class="go-idx">{{ gi + 1 }}</span>
                  <span class="go-name">{{ g }}</span>
                  <el-button-group>
                    <el-button :icon="Top" size="small" :disabled="gi === 0" title="上移" @click="moveGrade(gi, -1)" />
                    <el-button :icon="Bottom" size="small" :disabled="gi === meetForm.gradeOrder.length - 1"
                      title="下移" @click="moveGrade(gi, 1)" />
                  </el-button-group>
                </div>
                <el-button link type="primary" @click="useCustomOrder = false">恢复跟随年级设置</el-button>
              </div>
              <span class="hint" style="display:block;margin-top:4px">
                已启用自定义出场顺序（保存后以本处顺序为准）。
              </span>
            </template>
          </div>
        </el-form-item>

        <!-- 每天时段（各自独立，可不同） -->
        <el-form-item label="每天时段">
          <div style="width:100%">
            <div v-for="dc in meetForm.dayConfigs" :key="dc.day" class="day-config-block">
              <div class="day-config-title">
                第 {{ dc.day }} 天
                <span v-if="dc.date" class="hint">{{ dc.date }}</span>
              </div>
              <div v-for="(sl, si) in dc.slots" :key="sl.key" class="slot-row">
                <el-select v-model="sl.name" style="width:100px">
                  <el-option label="上午" value="上午" />
                  <el-option label="下午" value="下午" />
                  <el-option label="晚上" value="晚上" />
                </el-select>
                <el-time-select v-model="sl.start" start="06:00" step="00:10" end="22:00" style="width:130px"
                  placeholder="开始" />
                <span style="color:#909399">至</span>
                <el-time-select v-model="sl.end" start="06:00" step="00:10" end="22:00" style="width:130px"
                  placeholder="结束" />
                <el-button link type="danger" :icon="Delete" @click="removeSlot(dc, si)" />
              </div>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addSlot(dc)">添加时段</el-button>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="并数">
          <div style="display:flex;gap:32px;align-items:flex-start;width:100%;flex-wrap:wrap">
            <div>
              <div class="hint" style="margin-bottom:4px">径赛（同时进行的项目数）</div>
              <el-input-number v-model="meetForm.trackSlots" :min="1" :max="venueCount" size="small" />
            </div>
            <div>
              <div class="hint" style="margin-bottom:4px">田赛（同时进行的项目数）</div>
              <el-input-number v-model="meetForm.fieldSlots" :min="1" :max="venueCount" size="small" />
            </div>
            <div class="hint" style="margin-top:20px;flex:1;min-width:280px">
              <b>并数</b>：1 = 该位次同一时刻只进行 1 个项目（串行）；n = 最多 n 个项目同时进行（并行）。<br />
              <b>上限取决于场地数量</b>（当前 {{ venueCount }} 个场地），场地不足时编排会自动复用并提示。
            </div>
          </div>
        </el-form-item>

        <el-form-item label="项目编排顺序">
          <div style="width:100%">
            <div class="hint" style="margin-bottom:6px">
              自定义项目的编排先后顺序（田赛 + 径赛混排，各自在所属并发池内生效）；未列入的项目按项目排序号排在后面。
            </div>
            <div class="order-list">
              <div class="hint" style="margin-bottom:4px">提示：可直接拖拽行（⠿ 手柄）调整项目顺序，松手后自动按新顺序重新编排并重新检测兼项冲突。</div>
              <div v-for="(item, idx) in eventOrderList" :key="item.id" class="order-row"
                   :class="{ 'dragging': dragIndex === idx }"
                   draggable="true"
                   @dragstart="onDragStart(idx, $event)"
                   @dragover.prevent="onDragOver(idx, $event)"
                   @drop="onDrop(idx)"
                   @dragend="onDragEnd">
                <span class="drag-handle" title="拖拽排序">⠿</span>
                <el-tag size="small" :type="item.isTrack ? 'primary' : 'warning'" effect="plain">
                  {{ item.isTrack ? '径' : '田' }}
                </el-tag>
                <span class="order-name">{{ item.name }}</span>
                <span class="hint">{{ item.gradeGroup || '不分年级' }}</span>
                <span style="flex:1"></span>
                <el-button link size="small" :disabled="idx === 0 || reordering" @click="moveEvent(idx, 0)">置顶</el-button>
                <el-button link size="small" :disabled="idx === 0 || reordering" @click="moveEvent(idx, -1)">上移</el-button>
                <el-button link size="small" :disabled="idx === eventOrderList.length - 1 || reordering"
                  @click="moveEvent(idx, 1)">下移</el-button>
                <el-button link size="small" :disabled="idx === eventOrderList.length - 1 || reordering"
                  @click="moveEvent(idx, 999)">置底</el-button>
              </div>
              <el-empty v-if="!eventOrderList.length" description="暂无启用项目" :image-size="48" />
            </div>
            <el-button size="small" plain @click="resetEventOrder">按项目排序号重置</el-button>
          </div>
        </el-form-item>

        <el-form-item label="田赛分组">
          <div style="width:100%">
            <div class="hint" style="margin-bottom:6px">
              同一组的田赛项目会安排在同一时段并行进行（组内项目数受「田赛并发位数」约束，超出时自动分波）。
            </div>
            <div v-for="(g, gi) in meetForm.fieldGroups" :key="gi" class="group-row">
              <el-input v-model="g.name" size="small" placeholder="组名（如 田赛A组）" style="width:150px" />
              <el-select v-model="g.eventIds" multiple collapse-tags size="small" placeholder="选择田赛项目"
                style="flex:1;min-width:220px">
                <el-option v-for="e in fieldEvents" :key="e.id"
                  :label="e.name + '（' + (e.gradeGroup || '不分年级') + '）'" :value="e.id" />
              </el-select>
              <el-button link type="danger" size="small" @click="meetForm.fieldGroups.splice(gi, 1)">删除</el-button>
            </div>
            <el-button size="small" type="primary" plain :icon="Plus" @click="addFieldGroup">添加分组</el-button>
          </div>
        </el-form-item>

        <el-form-item label="时长/间隔(分)">
          <div style="display:flex;gap:8px;align-items:center;width:100%">
            <span>项目上限</span>
            <el-input-number v-model="meetForm.defaultDurationMinutes" :min="5" :max="300" :step="5" />
            <span>间隔</span>
            <el-input-number v-model="meetForm.defaultIntervalMinutes" :min="0" :max="60" />
            <span>单组(径赛)</span>
            <el-input-number v-model="meetForm.heatMinutes" :min="1" :max="60" />
            <span>每人次(田赛)</span>
            <el-input-number v-model="meetForm.fieldPerAthleteMinutes" :min="1" :max="60" />
          </div>
          <div style="display:flex;gap:8px;align-items:center;width:100%;margin-top:8px">
            <span>间隔下限</span>
            <el-input-number v-model="meetForm.minIntervalMinutes" :min="1" :max="60" />
            <span>压缩告警阈值</span>
            <el-input-number v-model="meetForm.compressionWarnRatio" :min="1" :max="5" :step="0.1" :precision="1" />
            <span class="hint">（被压到预计用时 1/阈值 以下时告警，建议 1.5）</span>
          </div>
          <div style="display:flex;gap:8px;align-items:center;width:100%;margin-top:8px">
            <span>预赛-决赛最小间隔</span>
            <el-input-number v-model="meetForm.finalMinGapMinutes" :min="10" :max="180" :step="5" />
            <span class="hint">（B07/U06：预赛结束后至少间隔此时长再决赛，默认 45 分钟，建议 45~60）</span>
          </div>
        </el-form-item>

        <el-form-item label="场地">
          <div style="width:100%">
            <div class="hint" style="margin-bottom:6px">
              第 1 个场地为径赛主场地，其余供田赛并行使用；<b>并数上限取决于场地数量</b>，请先录全场地（名称 + 编码）。
            </div>
            <div v-for="(v, vi) in meetForm.venues" :key="vi" class="venue-row">
              <el-input v-model="v.name" size="small" placeholder="场地名称（如 田赛A区）" style="width:190px" />
              <el-input v-model="v.code" size="small" placeholder="编码（如 FIELD_A）" style="width:150px" />
              <el-tag v-if="vi === 0" size="small" type="primary" effect="plain">径赛主场地</el-tag>
              <span style="flex:1"></span>
              <el-button link type="danger" size="small" :disabled="meetForm.venues.length <= 1"
                @click="removeVenue(vi)">删除</el-button>
            </div>
            <el-button size="small" type="primary" plain :icon="Plus" @click="addVenue">添加场地</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showConfigDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveMeetConfig">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- 手动调整对话框 -->
    <el-dialog v-model="showEditDialog" title="调整项目安排" width="460px" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="项目">
          <span class="edit-event-name">{{ editForm.eventName }}</span>
        </el-form-item>
        <el-form-item label="天数">
          <el-input-number v-model="editForm.day" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="日期" v-if="editForm.scheduleDate">
          <span>{{ editForm.scheduleDate }}</span>
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="editForm.timeSlot" style="width:100%">
            <el-option v-for="s in ['上午', '下午', '晚上']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-time-select v-model="editForm.startTime" start="06:00" step="00:10" end="22:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-time-select v-model="editForm.endTime" start="06:00" step="00:10" end="22:00" style="width:100%" />
        </el-form-item>
        <el-form-item label="场地">
          <el-input v-model="editForm.venue" placeholder="如 田径场" />
        </el-form-item>
        <el-form-item label="年级">
          <el-select v-model="editForm.grade" style="width:100%" clearable placeholder="不分年级">
            <el-option v-for="g in meetForm.gradeOrder" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看成绩：赛程行上对已录入成绩的项目弹窗展示（预赛/决赛轮次一并列出） -->
    <el-dialog v-model="resultsDialog.visible" :title="resultsDialogTitle" width="860px" top="6vh">
      <el-table :data="resultsDialog.rows" size="small" border stripe max-height="480" v-loading="resultsDialog.loading">
        <el-table-column label="轮次" width="76" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.round === 'preliminary' ? 'warning' : 'primary'" effect="plain">
              {{ row.round === 'preliminary' ? '预赛' : '决赛' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="组次/道次" width="100" align="center">
          <template #default="{ row }">
            {{ row.heat ? `第${row.heat}组` : '—' }}<template v-if="row.lane"> / {{ row.lane }}道</template>
          </template>
        </el-table-column>
        <el-table-column prop="number" label="号码布" width="90" align="center" />
        <el-table-column prop="athleteName" label="姓名" width="96" />
        <el-table-column prop="className" label="班级" min-width="110" show-overflow-tooltip />
        <el-table-column prop="grade" label="年级" width="96" show-overflow-tooltip />
        <el-table-column label="成绩" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ 'result-dnf': isNonFinish(row.rawTime) }">{{ row.rawTime || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="组内名次" width="88" align="center">
          <template #default="{ row }">{{ row.heatRank || '—' }}</template>
        </el-table-column>
        <el-table-column label="总名次" width="80" align="center">
          <template #default="{ row }">{{ row.rank || '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
      </el-table>
      <div v-if="!resultsDialog.loading && !resultsDialog.rows.length" class="results-empty">
        该项目暂无已录入的成绩
      </div>
      <template #footer>
        <el-button @click="resultsDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 取消冲突项目：统计互撞项目 → 批量取消某项目（退报名+移出编排+通知班主任） -->
    <el-dialog v-model="showCancelDialog" title="取消冲突项目（消解兼项冲突）" width="720px" top="6vh">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px">
        取消会<b>退报名 + 移出编排</b>并通知该运动员的班主任。先看下方「互撞项目」统计，
        选择要取消的项目（项目A 或 项目B），系统会批量取消相关运动员在该项目的报名。
      </el-alert>
      <div style="margin-bottom:10px;display:flex;gap:8px;align-items:center">
        <el-button size="small" type="primary" plain :icon="Search" :loading="cancelStatLoading" @click="loadCancelStats">
          统计互撞项目
        </el-button>
        <el-button size="small" type="success" plain :icon="Download" :disabled="!cancelStats.length" @click="exportClassConflicts">
          导出统计表（转班主任）
        </el-button>
      </div>
      <el-table :data="cancelStats" size="small" border stripe max-height="360" v-loading="cancelStatLoading">
        <el-table-column label="项目A" min-width="120">
          <template #default="{ row }">{{ row.eventA?.name || '' }}</template>
        </el-table-column>
        <el-table-column label="项目B" min-width="120">
          <template #default="{ row }">{{ row.eventB?.name || '' }}</template>
        </el-table-column>
        <el-table-column label="冲突人数" width="90" align="center">
          <template #default="{ row }">{{ row.count }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain :loading="cancelLoading" @click="cancelEvent(row, 'A')">
              取消项目A
            </el-button>
            <el-button size="small" type="warning" plain :loading="cancelLoading" @click="cancelEvent(row, 'B')">
              取消项目B
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!cancelStats.length && !cancelStatLoading" description="点击「统计互撞项目」查看哪些项目互撞" :image-size="60" />
    </el-dialog>

    <!-- 行政时间保护（规避时间） -->
    <ProtectionManage v-model:visible="showProtection" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Download, Refresh, RefreshLeft, EditPen, Setting, Plus, Delete, Top, Bottom, Search, Remove, Calendar } from '@element-plus/icons-vue'
import axios from 'axios'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'
import ProtectionManage from '@/components/ProtectionManage.vue'

const loading = ref(false)
const arranging = ref(false)
const savingConfig = ref(false)
const items = ref([])

// ==================== 编排进度（异步提交 + 轮询） ====================
// 为什么需要：编排要走「构造启发式 → 算法组合波次 → GA/LNS/MNSA/ALNS/Fix-opt 精修链 →
// 对抗式自检」，真实学校规模下是秒级到十秒级的阻塞操作。同步等待既会撞 axios 的 30s 超时，
// 也让操作者只能盯着一个转圈图标、无从判断「在算」还是「卡死」。
const arrangeProgress = ref({ active: false, percent: 0, stage: '', message: '' })
const lastFeasibility = ref(null)     // 可解性诊断（编排响应里的 algorithmPortfolio.feasibility）
let progressTimer = null
let progressAbort = false

function stopProgressPolling () {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}

/**
 * 进度查询刻意走原生 axios：绕开全局请求拦截器的 ElMessage 弹窗。
 * 轮询是高频后台请求，偶发失败（网络抖动/后端重启）不该连弹多条错误提示打扰操作者。
 */
async function fetchArrangeProgress (taskId) {
  const token = localStorage.getItem('token')
  const { data } = await axios.get(apiBase() + '/schedule/progress/' + taskId, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  return data && data.data !== undefined ? data.data : data
}

function pollArrangeProgress (taskId) {
  return new Promise((resolve, reject) => {
    let misses = 0
    progressTimer = setInterval(async () => {
      if (progressAbort) {
        stopProgressPolling()
        reject(new Error('已取消编排'))
        return
      }
      try {
        const p = await fetchArrangeProgress(taskId)
        misses = 0
        arrangeProgress.value = {
          active: !p.done && !p.failed,
          percent: p.percent || 0,
          stage: p.stage || '',
          message: p.message || ''
        }
        if (p.done) {
          stopProgressPolling()
          resolve(p.result || {})
        } else if (p.failed) {
          stopProgressPolling()
          reject(new Error(p.error || '编排失败'))
        }
      } catch (e) {
        // 单次轮询失败不致命；连续多次才判定任务失联（避免网络抖动误报）
        if (++misses >= 5) {
          stopProgressPolling()
          reject(e)
        }
      }
    }, 500)
  })
}

/** 可解性冲突类型 → 中文标签（与后端 report.py / ScheduleFeasibilityService 的 type 对齐）。 */
function conflictKindLabel (type) {
  return ({
    capacity_shortfall: '容量缺口',
    oversized_unit: '超大单元',
    clique_exceeds_periods: '结构性不可解（团超时段）',
    athlete_clash: '兼项无法错开',
    athlete_clash_summary: '兼项无法错开（汇总）'
  })[type] || type
}

/** 把建议动作汇总成一句话（同类合并、去重）。 */
function actionSummary (actions) {
  const kinds = new Set()
  let needDays = null
  let lanes = new Set()
  let cancels = 0
  ;(actions || []).forEach(a => {
    if (a.action === 'extend_days') {
      kinds.add('延长天数')
      if (a.needDays && (!needDays || a.needDays > needDays)) needDays = a.needDays
    } else if (a.action === 'add_lanes') {
      kinds.add('增加场地/并发位')
      if (a.scope) lanes.add(a.scope)
    } else if (a.action === 'cancel_entry') {
      cancels++
    }
  })
  const parts = []
  if (kinds.has('延长天数')) parts.push(`延长至 ${needDays || '?'} 天`)
  if (kinds.has('增加场地/并发位')) parts.push(`为${[...lanes].join('/') || '紧张池'}增加场地或并发位`)
  if (cancels) parts.push(`取消 ${cancels} 名运动员的最优冲突项报名（交班主任确认）`)
  return parts.join('；') || '人工调整'
}

// 场地名称 -> 编码 映射：赛程结果表同时展示「场地名称(编码)」
const venueCodeMap = ref({})
async function loadVenueCodes() {
  const map = {}
  try {
    const vs = await request.get('/venues')
    ;(Array.isArray(vs) ? vs : []).forEach(v => { if (v && v.name) map[v.name] = v.code })
  } catch (e) { /* 场地接口不可用时仅显示名称 */ }
  // 合并运动会配置里的场地（兜底旧流程：DB 场地表为空时沿用配置场地）
  ;(meetForm.venues || []).forEach(v => { if (v && v.name && v.code) map[v.name] = v.code })
  venueCodeMap.value = map
}
function venueCode(name) {
  return name ? (venueCodeMap.value[name] || '') : ''
}
const showEditDialog = ref(false)
const showConfigDialog = ref(false)
const editingId = ref(null)
// 年级出场顺序是否自定义（false=跟随系统设置·年级管理的 sortOrder，保存时回传空数组避免冻结）
const useCustomOrder = ref(false)
// U39/B36：编排模式（rule=规则模式，确定性毫秒级；optimize=优化模式，Timefold+GA+LNS）。
// 记忆到 localStorage：用户上次的选择在下次登录后保持，避免误用不期望的模式
const arrangeMode = ref(localStorage.getItem('spt.arrangeMode') || 'optimize')

/**
 * 编排请求体：模式 + 约束参数。
 *
 * ⚠️ 这些约束必须真的传出去。后端 `RuleScheduleConfig` 收得到，
 * 但前端一直只传 `{ mode }`，于是「兼项缓冲」「对抗轮数」在界面上根本调不动 ——
 * 用户诉求里的「所有约束条件均可作为输入」正是卡在这一层。
 */
function arrangePayload() {
  return {
    mode: arrangeMode.value,
    ruleConflictBufferMinutes: meetForm.conflictBufferMinutes,
    aiAdversarialRounds: meetForm.aiAdversarialRounds,
  }
}
function onArrangeModeChange() {
  localStorage.setItem('spt.arrangeMode', arrangeMode.value)
}
// 最近一次编排的模式回显（来自后端结果，防止前后端认知漂移）
const lastArrangeMode = ref('')
// 最近一次 AI 模式的自对抗自检报告（来自后端 aiReport，若模型不可用则为空）
const lastAiReport = ref(null)
// 本次编排实际生效的模式（后端回显），用于 AI 报告的条件展示
const lastMode = computed(() => lastArrangeMode.value || '')
/** 模式 → 中文名（三态：rule / optimize / ai） */
function modeLabel(m) {
  return m === 'rule' ? '规则模式' : (m === 'ai' ? 'AI 模式' : '优化模式')
}
// 最近一次规则编排的观测信息（algorithmPortfolio.rule）
const lastRuleInfo = ref(null)

// 场地：名称 + 编码（并数上限取决于场地数量）
const defaultVenueList = () => ([
  { name: '田径场', code: 'TRACK' },
  { name: '田赛A区', code: 'FIELD_A' },
  { name: '田赛B区', code: 'FIELD_B' }
])

const emptySlot = { key: 'AM', name: '上午', start: '08:00', end: '11:30' }

const meetForm = reactive({
  meetName: '',
  startDate: '',
  days: 2,
  autoDays: false,
  // 时间三态（与球类 daysLimit 同一契约）：fixed=限定天数 / unlimited=0 不限 / minimize=-1 尽可能减少
  dayMode: 'fixed',
  // 兼项缓冲分钟（后端 ruleConflictBufferMinutes）：原来前端硬编码 15、后端无入口，
  // 调大 = 兼项避让更保守（留更多赶场时间），调小 = 赛程更紧凑。
  conflictBufferMinutes: 15,
  // AI 自对抗轮数（后端 aiAdversarialRounds，仅 AI 模式生效）：轮数越多方案越优、耗时越长。
  aiAdversarialRounds: 3,
  gradeOrder: [],
  dayConfigs: [
    { day: 1, date: '', slots: [{ ...emptySlot }, { key: 'PM', name: '下午', start: '14:00', end: '17:30' }] },
    { day: 2, date: '', slots: [{ ...emptySlot }, { key: 'PM', name: '下午', start: '14:00', end: '17:30' }] }
  ],
  trackSlots: 1,
  fieldSlots: 2,
  eventOrder: [],
  fieldGroups: [],
  defaultDurationMinutes: 30,
  defaultIntervalMinutes: 5,
  heatMinutes: 6,
  fieldPerAthleteMinutes: 3,
  // B05/U07：项目间隔下限 + 压缩告警阈值（被压到预计用时的 1/ratio 以下即告警）
  minIntervalMinutes: 5,
  compressionWarnRatio: 1.5,
  // B07/U06：预赛→决赛最小间隔（默认 45 分钟）
  finalMinGapMinutes: 45,
  venues: defaultVenueList()
})

/** 有效场地数（名称为空的不计），至少 1 —— 并数上限取决于它 */
const venueCount = computed(() =>
  Math.max(1, meetForm.venues.filter(v => v && String(v.name || '').trim()).length))

function addVenue() {
  meetForm.venues.push({ name: '', code: '' })
}

function removeVenue(index) {
  if (meetForm.venues.length <= 1) return
  meetForm.venues.splice(index, 1)
}

// ==================== 项目编排顺序 / 田赛分组 ====================
const allEvents = ref([])
const eventOrderList = ref([])
const fieldEvents = computed(() => allEvents.value.filter(e => !e.isTrack))

/** 按 meetForm.eventOrder 排出可编辑列表；未列入的项目按 sortOrder 追加在后 */
function buildEventOrder() {
  const pos = new Map((meetForm.eventOrder || []).map((id, i) => [id, i]))
  eventOrderList.value = [...allEvents.value].sort((a, b) => {
    const pa = pos.has(a.id) ? pos.get(a.id) : Number.MAX_SAFE_INTEGER
    const pb = pos.has(b.id) ? pos.get(b.id) : Number.MAX_SAFE_INTEGER
    if (pa !== pb) return pa - pb
    return (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
  })
}

/** dir: -1 上移 / 1 下移 / 0 置顶 / 999 置底 */
function moveEvent(index, dir) {
  const arr = [...eventOrderList.value]
  if (dir === 0) {
    const [it] = arr.splice(index, 1)
    arr.unshift(it)
  } else if (dir === 999) {
    const [it] = arr.splice(index, 1)
    arr.push(it)
  } else {
    const target = index + dir
    if (target < 0 || target >= arr.length) return
    ;[arr[index], arr[target]] = [arr[target], arr[index]]
  }
  eventOrderList.value = arr
}

function resetEventOrder() {
  eventOrderList.value = [...allEvents.value].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
}

// ==================== 拖拽排序（HTML5 原生，无额外依赖）====================
const dragIndex = ref(-1)
const reordering = ref(false)

/** 构造运动会日程配置提交体（与「保存」共用，确保拖拽调序提交的字段与手动保存完全一致） */
function buildMeetSchedulePayload() {
  const cleanVenues = (meetForm.venues || [])
    .filter(v => v && String(v.name || '').trim())
    .map(v => ({ name: String(v.name).trim(), code: String(v.code || '').trim() }))
  return {
    meetName: meetForm.meetName,
    startDate: meetForm.startDate,
    // 时间三态 → 后端 days：限定=具体天数；不限=0；尽可能减少=-1
    days: meetForm.dayMode === 'fixed'
      ? meetForm.dayConfigs.length
      : (meetForm.dayMode === 'minimize' ? -1 : 0),
    autoDays: meetForm.dayMode !== 'fixed',
    dayMode: meetForm.dayMode,
    dayConfigs: meetForm.dayConfigs,
    // 未自定义时回传空数组 → 服务端归一化，使“年级管理”调整 sortOrder 仍可传导，防止冻结
    gradeOrder: useCustomOrder.value ? meetForm.gradeOrder : [],
    venues: cleanVenues,
    trackSlots: meetForm.trackSlots,
    fieldSlots: meetForm.fieldSlots,
    // 自定义项目顺序（仅提交当前列表顺序，未列入的项目由后端按排序号追加）
    eventOrder: eventOrderList.value.map(e => e.id),
    // 只提交非空分组（组内项目必须同期的田赛）
    fieldGroups: (meetForm.fieldGroups || [])
      .filter(g => g.eventIds && g.eventIds.length)
      .map(g => ({ name: g.name, eventIds: [...g.eventIds] })),
    defaultDurationMinutes: meetForm.defaultDurationMinutes,
    defaultIntervalMinutes: meetForm.defaultIntervalMinutes,
    heatMinutes: meetForm.heatMinutes,
    fieldPerAthleteMinutes: meetForm.fieldPerAthleteMinutes,
    // B05/U07：间隔下限 + 压缩告警阈值随配置提交
    minIntervalMinutes: meetForm.minIntervalMinutes,
    compressionWarnRatio: meetForm.compressionWarnRatio,
    // B07/U06：预赛→决赛最小间隔随配置提交
    finalMinGapMinutes: meetForm.finalMinGapMinutes
  }
}

function onDragStart(idx, ev) {
  dragIndex.value = idx
  if (ev && ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move'
    ev.dataTransfer.setData('text/plain', String(idx))
  }
}

function onDragOver(idx, ev) {
  if (ev) ev.preventDefault()
  if (ev && ev.dataTransfer) ev.dataTransfer.dropEffect = 'move'
}

function onDrop(idx) {
  const from = dragIndex.value
  dragIndex.value = -1
  if (from < 0 || from === idx) return
  const arr = [...eventOrderList.value]
  const [it] = arr.splice(from, 1)
  arr.splice(idx, 0, it)
  eventOrderList.value = arr
  commitEventOrder()
}

function onDragEnd() {
  dragIndex.value = -1
}

/**
 * 拖拽落定后：保存新顺序 → 按新顺序重新编排（规则模式，确定性可复现）→ 重新计算兼项冲突。
 * 与「一键编排」「检测冲突」共用同一套判定，确保人工调序后冲突结果与自动编排一致。
 */
async function commitEventOrder() {
  if (!eventOrderList.value.length) return
  reordering.value = true
  try {
    await request.put('/system/meet-schedule', buildMeetSchedulePayload())
    // 拖拽改序后重排：沿用当前模式（AI 模式下跑 AI 派遣款型，与用户所选一致），
    // 不再写死 rule —— 否则「AI 模式改完顺序一点重排就退回规则模式」，模式选择形同虚设
    await request.post('/schedule/auto', arrangePayload())
    await loadConflicts()
    ElMessage.success('顺序已调整，已按新顺序重新编排并检测兼项冲突')
  } catch (e) {
    // 拦截器已提示
  } finally {
    reordering.value = false
  }
}

function addFieldGroup() {
  meetForm.fieldGroups.push({ name: '田赛组' + (meetForm.fieldGroups.length + 1), eventIds: [] })
}

async function fetchEvents() {
  try {
    const res = await request.get('/events')
    const list = Array.isArray(res) ? res : (res?.records || [])
    allEvents.value = list.filter(e => e.isEnabled !== false && e.enabled !== false)
  } catch (e) {
    allEvents.value = []
  }
}

const editForm = reactive({
  id: null, eventId: null, eventName: '', day: 1, scheduleDate: '', grade: '',
  timeSlot: '上午', startTime: '', endTime: '', venue: '田径场'
})

// 按天分组
const groupedByDay = computed(() => {
  const groups = {}
  items.value.forEach(i => {
    const d = i.day || 1
    if (!groups[d]) groups[d] = []
    groups[d].push(i)
  })
  return Object.keys(groups).sort((a, b) => a - b).reduce((acc, k) => {
    acc[k] = groups[k].sort((a, b) => (a.startTime || '').localeCompare(b.startTime || ''))
    return acc
  }, {})
})

function groupBySlot(dayItems) {
  const groups = {}
  dayItems.forEach(i => {
    const s = i.timeSlot || '其他'
    if (!groups[s]) groups[s] = []
    groups[s].push(i)
  })
  return groups
}

function slotTag(slot) {
  if (slot === '上午') return 'primary'
  if (slot === '下午') return 'warning'
  if (slot === '晚上') return 'info'
  return ''
}

async function fetchList() {
  loading.value = true
  try {
    const res = await request.get('/schedule')
    items.value = res.items || []
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

// ==================== 运动会日程配置 ====================
function blankSlots() {
  return [{ key: 'AM', name: '上午', start: '08:00', end: '11:30' },
          { key: 'PM', name: '下午', start: '14:00', end: '17:30' }]
}

async function openMeetConfig() {
  showConfigDialog.value = true
  try {
    const res = await request.get('/system/meet-schedule')
    Object.assign(meetForm, res)
    meetForm.autoDays = !!res.autoDays
    // 回填三态：优先用显式 dayMode；否则按 dayConfigs/autoDays 推断（兼容历史配置）
    meetForm.dayMode = res.dayMode
      || (Number(res.days) < 0 ? 'minimize' : (res.autoDays ? 'unlimited' : 'fixed'))
    // 规范化 dayConfigs / slots
    meetForm.dayConfigs = (res.dayConfigs || []).map((dc, i) => ({
      day: dc.day || i + 1,
      date: dc.date || '',
      slots: (dc.slots && dc.slots.length ? dc.slots : blankSlots()).map(s => ({
        key: s.key || s.name, name: s.name || '上午', start: s.start || '08:00', end: s.end || '11:30'
      }))
    }))
    // 场地：兼容旧的字符串数组 ["田径场", …] 与新的对象数组 [{name, code}, …]
    const rawVenues = Array.isArray(res.venues) ? res.venues : []
    meetForm.venues = rawVenues.length
      ? rawVenues.map(v => typeof v === 'string'
        ? { name: v, code: '' }
        : { name: v?.name || '', code: v?.code || '' })
      : defaultVenueList()
    // 并发位数（旧串行/并行配置由后端平滑换算为 1~n）
    meetForm.trackSlots = Number(res.trackSlots) > 0 ? Number(res.trackSlots) : 1
    meetForm.fieldSlots = Number(res.fieldSlots) > 0 ? Number(res.fieldSlots) : 2
    // 自定义项目顺序与田赛分组
    meetForm.eventOrder = Array.isArray(res.eventOrder) ? [...res.eventOrder] : []
    meetForm.fieldGroups = (Array.isArray(res.fieldGroups) ? res.fieldGroups : [])
      .map(g => ({ name: g?.name || '', eventIds: Array.isArray(g?.eventIds) ? [...g.eventIds] : [] }))
    buildEventOrder()
    // 服务端已自动填充 gradeOrder（跟随年级设置或已显式定制）
    useCustomOrder.value = !!res.gradeOrderCustom
    meetForm.gradeOrder = (res.gradeOrder && res.gradeOrder.length) ? [...res.gradeOrder] : []
    defaultOrderSnapshot.value = [...meetForm.gradeOrder]
  } catch (e) {
    // 读取失败仍可编辑默认值
  }
}

function onCustomOrderChange(val) {
  // 从“跟随”切到“自定义”时，以当前（推导）顺序为底稿
  if (val && !meetForm.gradeOrder.length) {
    try {
      meetForm.gradeOrder = [...defaultOrderSnapshot.value]
    } catch (e) { /* ignore */ }
  }
}

function moveGrade(index, dir) {
  const target = index + dir
  if (target < 0 || target >= meetForm.gradeOrder.length) return
  const arr = [...meetForm.gradeOrder]
  ;[arr[index], arr[target]] = [arr[target], arr[index]]
  meetForm.gradeOrder = arr
}

// 打开配置时抓一份“跟随”底稿，供切到自定义时回填
const defaultOrderSnapshot = ref([])

function syncDates() {
  if (!meetForm.startDate) return
  meetForm.dayConfigs.forEach((dc, i) => {
    const d = new Date(meetForm.startDate)
    d.setDate(d.getDate() + i)
    dc.date = d.toISOString().slice(0, 10)
  })
}

function syncDays() {
  const n = Number(meetForm.days) || 2
  while (meetForm.dayConfigs.length < n) {
    meetForm.dayConfigs.push({
      day: meetForm.dayConfigs.length + 1, date: '', slots: blankSlots()
    })
  }
  meetForm.dayConfigs = meetForm.dayConfigs.slice(0, n)
  meetForm.dayConfigs.forEach((dc, i) => { dc.day = i + 1 })
  syncDates()
}

function onAutoDaysChange() {
  // 切换自动推算时无需额外动作：关闭天数输入，编排阶段由后端按报名规模推算
}

function addSlot(dc) {
  const keys = 'ABCDEFG'.split('')
  const key = keys[dc.slots.length] || 'X' + dc.slots.length
  const start = dc.slots.length ? dc.slots[dc.slots.length - 1].end : '14:00'
  dc.slots.push({ key, name: '下午', start, end: '17:30' })
}

function removeSlot(dc, si) {
  if (dc.slots.length <= 1) return
  dc.slots.splice(si, 1)
}

async function saveMeetConfig() {
  if (!meetForm.startDate) { ElMessage.warning('请选择运动会开始日期'); return }
  const cleanVenues = meetForm.venues
    .filter(v => v && String(v.name || '').trim())
    .map(v => ({ name: String(v.name).trim(), code: String(v.code || '').trim() }))
  if (!cleanVenues.length) { ElMessage.warning('请至少配置一个场地（需填写场地名称）'); return }
  const maxSlots = Math.max(1, cleanVenues.length)
  if (meetForm.trackSlots > maxSlots || meetForm.fieldSlots > maxSlots) {
    ElMessage.warning(`并数不能超过场地数量（当前 ${maxSlots} 个场地）`)
    return
  }
  for (const dc of meetForm.dayConfigs) {
    if (!dc.slots.length) { ElMessage.warning(`第 ${dc.day} 天至少需要一个时段`); return }
  }
  savingConfig.value = true
  try {
    const payload = buildMeetSchedulePayload()
    await request.put('/system/meet-schedule', payload)
    ElMessage.success('运动会日程配置已保存')
    showConfigDialog.value = false
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingConfig.value = false
  }
}

// ==================== B06/U05：兼项冲突检测 ====================
const conflictList = ref([])
const conflictSummary = ref(null)
const conflictLoading = ref(false)
const resolveLoading = ref(false)
const conflictPage = ref(1)
const conflictPageSize = ref(20)
const conflictPaged = computed(() => {
  const from = (conflictPage.value - 1) * conflictPageSize.value
  return conflictList.value.slice(from, from + conflictPageSize.value)
})

/** 把后端冲突条目摊平（eventA/eventB 是对象，表格需要可直接渲染的字段名） */
function normalizeConflicts(list) {
  return (list || []).map(c => ({
    ...c,
    eventAName: (c.eventA && c.eventA.name) || '',
    eventBName: (c.eventB && c.eventB.name) || ''
  }))
}

function applyConflicts(data) {
  conflictSummary.value = (data && data.summary) || null
  conflictList.value = normalizeConflicts(data && data.list)
  conflictPage.value = 1
}

async function loadConflicts() {
  conflictLoading.value = true
  try {
    const res = await request.get('/arrange/conflicts')
    applyConflicts(res || {})
    if (!conflictList.value.length) ElMessage.success('未检测到兼项冲突')
    else ElMessage.warning(`检测到 ${conflictList.value.length} 处兼项冲突，请按「调整建议」列处理`)
  } catch (e) {
    console.error(e)
  } finally {
    conflictLoading.value = false
  }
}

async function exportConflicts() {
  try {
    await downloadApi('/arrange/conflicts/export', '兼项冲突清单.xlsx')
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e?.message || '导出失败，请重新登录后再试')
  }
}

// ==================== 一键消解兼项冲突 ====================
// 调用后端「无限轮重排」接口：以真实冲突口径反复扰动重排，直到归零或收敛到最低，完成后自动刷新冲突清单
async function resolveConflicts() {
  if (!items.value.length) return
  resolveLoading.value = true
  try {
    const res = await request.post('/schedule/resolve-conflicts', arrangePayload())
    items.value = res.items || []
    lastArrangeMode.value = res.mode || arrangeMode.value
    lastRuleInfo.value = res.algorithmPortfolio?.rule || null
    lastAiReport.value = res.aiReport || res.algorithmPortfolio?.aiReport || null
    // 消解后自动重新检测，刷新表格与计数（权威来源为后端 detectConflicts 同口径）
    await loadConflicts()
    const auto = res.autoArrange || null
    let autoTip = ''
    if (auto) {
      autoTip = `；已自动生成道次编排 ${auto.ok} 个（性别组）${auto.failed ? '，' + auto.failed + ' 个失败' : ''}`
    }
    const modeTag = lastArrangeMode.value === 'rule' ? '【规则模式】' : '【优化模式】'
    const left = (conflictSummary.value && conflictSummary.value.total) || 0
    const severe = (conflictSummary.value && conflictSummary.value.blocker) || 0
    if (left === 0) {
      ElMessage.success(modeTag + '兼项冲突已消解至 0 处！' + autoTip)
    } else {
      ElMessage.warning(
        modeTag + `已尽可能优化，残余兼项冲突 ${left} 处（严重 ${severe}），已达当前配置下最低` + autoTip
      )
    }
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  } finally {
    resolveLoading.value = false
  }
}

// ==================== 兼项自动统计（兼项运动员名单 + 高频共现项目对） ====================
// 为什么是「自动」：兼项运动员是编排里的高风险人群，进编排页就该看见，
// 而不是等使用者点一次按钮；编排/审核变化后由各入口显式调用刷新。
const coPairs = ref([])
const coSummary = ref(null)
const coAthletes = ref([])
const coTab = ref('athletes')
const coKeyword = ref('')
const coPage = ref(1)
const coPageSize = ref(10)
const coLoading = ref(false)
let coReqSeq = 0

const coRatioText = computed(() => {
  const s = coSummary.value
  if (!s) return '0%'
  const pct = (s.multiRatio || 0) * 100
  return (pct >= 10 ? pct.toFixed(0) : pct.toFixed(1)) + '%'
})

const coFiltered = computed(() => {
  const kw = (coKeyword.value || '').trim().toLowerCase()
  if (!kw) return coAthletes.value
  return coAthletes.value.filter((a) =>
    [a.name, a.number, a.className, a.grade, a.eventNamesText]
      .some((v) => String(v || '').toLowerCase().includes(kw)))
})

const coPaged = computed(() => {
  const start = (coPage.value - 1) * coPageSize.value
  return coFiltered.value.slice(start, start + coPageSize.value)
})

/** 分布条百分比相对「全部参赛运动员」，最多兼项的一档总是接近 100% 之外的视觉比例 */
function distPercent (d) {
  const base = (coSummary.value && coSummary.value.athleteCount) || 0
  if (!base) return 0
  return Math.round((((d && d.count) || 0) * 1000) / base) / 10
}

/** auto=true 为自动加载（静默）；auto=false 为用户点「刷新」（可提示） */
async function loadCooccurrence (auto = true) {
  if (coLoading.value) return
  const seq = ++coReqSeq
  coLoading.value = true
  try {
    const res = await request.get('/arrange/multi-event', { params: { limit: 500 } })
    if (seq !== coReqSeq) return           // 并发请求只认最后一次结果
    coSummary.value = res || null
    coPairs.value = (res && res.pairs) || []
    coAthletes.value = (res && res.athletes) || []
    coPage.value = 1
    if (!auto && !coAthletes.value.length) ElMessage.info('当前没有兼项运动员（暂无兼项报名）')
  } catch (e) {
    console.error(e)
    if (seq === coReqSeq) coSummary.value = null
  } finally {
    if (seq === coReqSeq) coLoading.value = false
  }
}

function exportMultiEvent () {
  downloadApi('/arrange/multi-event/export', '兼项运动员统计.xlsx')
    .then(() => ElMessage.success('兼项运动员名单已导出'))
    .catch((e) => { if (e && e.message) ElMessage.error(e.message) })
}

// ==================== 取消冲突项目（消解兼项冲突） ====================
const showProtection = ref(false)
const showCancelDialog = ref(false)
const cancelStats = ref([])
const cancelLoading = ref(false)
const cancelStatLoading = ref(false)

function openCancelDialog() {
  showCancelDialog.value = true
  if (!cancelStats.value.length) loadCancelStats()
}

async function loadCancelStats() {
  cancelStatLoading.value = true
  try {
    const res = await request.get('/arrange/conflicts/statistics')
    cancelStats.value = (res && res.pairs) || []
  } catch (e) {
    console.error(e)
  } finally {
    cancelStatLoading.value = false
  }
}

/** 取消某项目（A/B）：把该项目在此事件对中的所有冲突运动员批量退报名+移出编排+通知班主任 */
async function cancelEvent(row, which) {
  const event = which === 'A' ? row.eventA : row.eventB
  const athletes = row.athletes || []
  if (!event || !event.id || !athletes.length) return
  try {
    await ElMessageBox.confirm(
      `将取消「${event.name}」项目，共 ${athletes.length} 名冲突运动员的报名，并同步移出编排、通知班主任。继续？`,
      '取消冲突项目', { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' })
  } catch { return }
  cancelLoading.value = true
  try {
    const items = athletes.map(a => ({ athleteId: a.athleteId, eventId: event.id }))
    const r = await request.post('/arrange/conflicts/cancel', { items, notifyTeacher: true })
    ElMessage.success(`已取消 ${r.cancelled} 条报名，通知 ${r.notifiedTeachers} 名班主任`)
    await loadCancelStats()
    await loadConflicts()
    await fetchList()
    loadCooccurrence(true)          // 报名被取消，兼项统计要跟着变
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  } finally {
    cancelLoading.value = false
  }
}

async function exportClassConflicts() {
  try {
    await downloadApi('/arrange/conflicts/class-export', '兼项冲突统计表.xlsx')
    ElMessage.success('统计表已导出，可转交班主任')
  } catch (e) {
    ElMessage.error(e?.message || '导出失败')
  }
}

// ==================== 一键编排（赛程 + 自动道次） ====================
// U39/B36：带上编排模式——rule=规则模式（确定性、毫秒级、可复现）；optimize=优化模式；ai=AI 模式
async function doAutoSchedule() {
  arranging.value = true
  progressAbort = false
  lastFeasibility.value = null
  arrangeProgress.value = { active: true, percent: 0, stage: '提交', message: '正在提交编排任务…' }
  try {
    // 异步提交 + 轮询进度：编排链路长（求解 → 精修 → 自检），同步等待会撞前端 30s 超时
    const submitted = await request.post('/schedule/auto/async', arrangePayload())
    const taskId = submitted && submitted.taskId
    if (!taskId) throw new Error('未能获取编排任务号')
    const res = await pollArrangeProgress(taskId)
    items.value = res.items || []
    // 模式回显（以服务端为准）
    lastArrangeMode.value = res.mode || arrangeMode.value
    lastRuleInfo.value = res.algorithmPortfolio?.rule || null
    lastAiReport.value = res.aiReport || res.algorithmPortfolio?.aiReport || null
    // B06/U05：编排响应本身已带 conflicts，直接用，省一次往返
    applyConflicts({ summary: null, list: res.conflicts })
    if (res.conflicts) {
      const severe = res.conflicts.filter(c => c.severity === '严重').length
      conflictSummary.value = {
        total: res.conflicts.length, blocker: severe, warn: res.conflicts.length - severe,
        athleteCount: new Set(res.conflicts.map(c => c.athleteId)).size, bufferMinutes: 15
      }
    }
    const auto = res.autoArrange || null
    let autoTip = ''
    if (auto) {
      autoTip = `；已自动生成道次编排 ${auto.ok} 个（性别组）${auto.failed ? '，' + auto.failed + ' 个失败' : ''}`
    }
    const modeTag = lastArrangeMode.value === 'rule' ? '【规则模式】' : '【优化模式】'
    let ruleTip = ''
    if (lastArrangeMode.value === 'rule' && lastRuleInfo.value) {
      ruleTip = `（耗时 ${lastRuleInfo.value.elapsedMillis}ms` +
        (lastRuleInfo.value.unplaced > 0 ? `，${lastRuleInfo.value.unplaced} 个单元排不下已告警` : '') + '）'
    }
    const dayTip = res.estimatedDays ? `（自动推算需 ${res.estimatedDays} 天）` : ''
    if (res.warnings && res.warnings.length) {
      ElMessage.warning(modeTag + '编排完成，但有 ' + res.warnings.length + ' 条提示：' + res.warnings[0] + autoTip + ruleTip + dayTip)
    } else {
      ElMessage.success(modeTag + '赛程编排完成！共 ' + (res.total || 0) + ' 个单元' + autoTip + ruleTip + dayTip)
    }
    if (auto && auto.fails && auto.fails.length) console.warn('自动道次失败明细', auto.fails)

    // 编排/道次落库后，兼项运动员名单可能随之变化（自动排道会补齐此前未编排的项目），刷新一次
    loadCooccurrence(true)

    // 可解性诊断：编排完成 ≠ 排得下。响应里带 feasibility 时把结论落在页面上，
    // 让操作者当场看到「哪些排不下、为什么、怎么办」，而不是只看到一句「编排完成」。
    const feas = res.algorithmPortfolio?.feasibility || null
    lastFeasibility.value = feas
    if (feas && !feas.feasible) {
      const s = feas.summary || {}
      const first = (feas.conflicts || [])[0]
      ElMessage.warning('可解性诊断：' + (s.placed || 0) + '/' + (s.tasks || 0) + ' 个组次可排，'
        + (s.unplaced || 0) + ' 个排不下' + (first ? '（' + conflictKindLabel(first.type) + '）' : ''))
    }
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  } finally {
    stopProgressPolling()
    arranging.value = false
    arrangeProgress.value = { active: false, percent: 0, stage: '', message: '' }
  }
}

// ==================== 手动调整 ====================
function openEdit(row) {
  editingId.value = row.id
  Object.assign(editForm, {
    id: row.id, eventId: row.eventId, eventName: row.eventName, day: row.day,
    scheduleDate: row.scheduleDate || '', grade: row.grade || '',
    timeSlot: row.timeSlot, startTime: row.startTime, endTime: row.endTime, venue: row.venue
  })
  showEditDialog.value = true
}

async function saveEdit() {
  try {
    const updated = items.value.map(i => i.id === editingId.value ? { ...i, ...editForm } : i)
    const res = await request.post('/schedule/save', updated)
    items.value = res.items || []
    showEditDialog.value = false
    ElMessage.success('调整已保存')
  } catch (e) {
    console.error(e)
  }
}

async function exportSheet() {
  try { await downloadApi('/schedule/export', '赛程总表.xlsx'); ElMessage.success('导出成功') }
  catch (e) { ElMessage.error(e?.message || '导出失败，请重新登录后再试') }
}

async function clearAll() {
  try {
    await ElMessageBox.confirm(
      '确定清空全部项目赛程吗？道次编排（含裁判分配与预留空位）将一并清空，需重新编排。',
      '确认清空', { type: 'warning' })
    await request.delete('/schedule')
    items.value = []
    ElMessage.success('赛程与道次编排已清空')
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

// ==================== 已录入成绩的项目：行上「查看成绩 / 再次排道」 ====================
const resultEventIds = ref(new Set())
const resultsDialog = reactive({ visible: false, loading: false, eventName: '', round: '', rows: [] })
const resultsDialogTitle = computed(() => {
  const r = resultsDialog.round === 'preliminary' ? '预赛' : '决赛'
  return `「${resultsDialog.eventName}」${r}成绩`
})

function hasResult(row) { return !!row?.eventId && resultEventIds.value.has(row.eventId) }

/** 进页面拉一次全量成绩，聚合出「已录入成绩的项目 id」集合（失败不阻塞编排页） */
async function loadResultEventIds() {
  try {
    const rows = await request.get('/results')
    resultEventIds.value = new Set((Array.isArray(rows) ? rows : []).map(r => r.eventId).filter(Boolean))
  } catch (e) { /* 静默：拉不到成绩清单时按钮不显示，不影响编排主流程 */ }
}

async function openResults(row) {
  resultsDialog.eventName = row.eventName
  resultsDialog.round = row.round
  resultsDialog.rows = []
  resultsDialog.visible = true
  resultsDialog.loading = true
  try {
    const rows = await request.get('/results', { params: { eventId: row.eventId } })
    const list = Array.isArray(rows) ? rows : []
    // 行是项目×年级×轮次：同轮次成绩排前，其余轮次附后；组次、道次升序
    list.sort((a, b) =>
      ((a.round === row.round ? 0 : 1) - (b.round === row.round ? 0 : 1))
      || (a.heat || 0) - (b.heat || 0) || (a.lane || 0) - (b.lane || 0))
    resultsDialog.rows = list
  } catch (e) {
    ElMessage.error(e?.message || '成绩查询失败，请重新登录后再试')
  } finally {
    resultsDialog.loading = false
  }
}

/** 再次排道：按该行（项目×年级×轮次）重排道次。该年级各性别组按当前报名重排，人工锁定项保留；已录入的成绩记录挂运动员，不受影响 */
async function rearrangeRow(row) {
  try {
    await ElMessageBox.confirm(
      `将重新生成「${row.eventName}」（${row.grade || '不分年级'}）`
      + `${row.round === 'preliminary' ? '预赛' : '决赛'}的组次与道次：`
      + `该年级各性别组按当前报名重排，人工锁定的道次保留；已录入的成绩记录不受影响。继续？`,
      '再次排道', { type: 'warning', confirmButtonText: '重排', cancelButtonText: '取消' })
  } catch { return }
  try {
    // AI 模式下「再次排道」沿用 AI 派遣款型，避免同一份报名「一键 AI 编排」与「再次排道」排出两套道次
    const r = await request.post(`/arrange/events/${row.eventId}/rearrange`,
      { grade: row.grade, round: row.round, styleRule: arrangeMode.value === 'ai' ? 'ai' : undefined }) || {}
    if (r.failed > 0) {
      ElMessage.warning(`道次已重排 ${r.arranged} 组，失败 ${r.failed} 组：${(r.fails || []).join('；')}`)
    } else {
      ElMessage.success(`道次已重排完成（${(r.genders || []).length} 个性别组）`)
    }
  } catch (e) {
    ElMessage.error(e?.message || '再次排道失败')
  }
}

/** DNF/DNS/DSQ 等未完赛标记的展示样式 */
function isNonFinish(t) {
  return typeof t === 'string' && /^(DNF|DNS|DSQ|DQ)$/i.test(t.trim())
}

onMounted(() => {
  fetchList()
  fetchEvents()
  loadVenueCodes()
  loadResultEventIds()
  // 兼项统计自动跑一次：进入编排页就能看到「有多少人兼项、兼了几项」
  loadCooccurrence(true)
})

// 搜索关键词变化回到第一页，否则会停在一个越界页码上（翻页后筛掉，表格直接空白）
watch(coKeyword, () => { coPage.value = 1 })
</script>

<style scoped>
.schedule-page { display: flex; flex-direction: column; gap: 12px; }
.toolbar-card { border-radius: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; flex-wrap: wrap; }
.mode-switch { margin-right: 4px; }
.mode-switch :deep(.el-radio-button__inner) { font-weight: 600; }
/* AI 模式：紫红色，与「规则/优化」的蓝绿区分开，一眼能看出走的是 ONNX 本地推理链路 */
.mode-switch :deep(.el-radio-button.is-ai-mode .el-radio-button__inner) {
  background: linear-gradient(135deg, #7c3aed, #c026d3);
  border-color: #7c3aed;
  color: #fff;
}
.mode-switch :deep(.el-radio-button.is-ai-mode.is-active .el-radio-button__inner) {
  background: #6d28d9;
  border-color: #6d28d9;
  box-shadow: -1px 0 0 0 #6d28d9;
}
.empty-card { border-radius: 12px; }
.day-card { border-radius: 12px; }
.day-header { display: flex; align-items: center; gap: 10px; }
.day-title { font-size: 16px; font-weight: 700; color: #303133; }
.slot-block { margin-bottom: 16px; }
.slot-title { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.slot-time { font-size: 12px; color: #909399; }
.edit-event-name { font-weight: 600; color: #303133; }
.team-mark {
  display: inline-block;
  width: 18px; height: 18px; line-height: 18px;
  text-align: center; border-radius: 4px;
  background: #f56c6c; color: #fff; font-size: 11px;
  margin-right: 4px;
}
.row-remark { font-size: 12px; color: #909399; }
.result-dnf { color: #f56c6c; font-weight: 600; }
.results-empty { text-align: center; color: #909399; padding: 24px 0; font-size: 13px; }
.day-config-block {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.grade-order-edit { display: flex; flex-direction: column; gap: 6px; }
.grade-order-row {
  display: flex; align-items: center; gap: 10px;
  background: #f8fafc; border: 1px solid #e4e7ed; border-radius: 8px; padding: 4px 10px;
}
.grade-order-row .go-idx {
  width: 22px; height: 22px; border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6, #6366f1); color: #fff;
  font-size: 12px; display: inline-flex; align-items: center; justify-content: center; font-weight: 600;
}
.grade-order-row .go-name { flex: 1; font-size: 14px; color: #303133; }
.day-config-title { font-weight: 600; margin-bottom: 8px; color: #303133; }
.slot-row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.order-list {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 260px; overflow-y: auto; padding: 6px; margin-bottom: 8px;
  border: 1px solid #e4e7ed; border-radius: 8px; background: #fafbfc;
}
.order-row {
  display: flex; align-items: center; gap: 8px;
  background: #fff; border: 1px solid #e4e7ed; border-radius: 8px; padding: 4px 10px;
}
.order-row .order-name { font-size: 14px; color: #303133; font-weight: 500; }
.order-row { cursor: default; }
.order-row.dragging { opacity: 0.4; }
.drag-handle { cursor: grab; color: #909399; user-select: none; font-size: 16px; line-height: 1; }
.order-row.dragging .drag-handle { cursor: grabbing; }
.group-row, .venue-row {
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap;
}
/* B06/U05 兼项冲突卡片 */
.conflict-card { margin-top: 14px; border-radius: 10px; }
.conflict-header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.conflict-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

/* ===== 兼项自动统计：概览指标 + 项数分布 ===== */
.co-overview {
  display: flex; align-items: stretch; gap: 12px; flex-wrap: wrap;
  padding: 12px 14px; margin-bottom: 12px;
  background: linear-gradient(135deg, rgba(124, 58, 237, .06), rgba(192, 38, 211, .04));
  border: 1px solid rgba(124, 58, 237, .16); border-radius: 10px;
}
.co-metric { min-width: 96px; text-align: center; }
.co-metric-num { font-size: 24px; font-weight: 700; line-height: 1.15; color: #303133; }
.co-metric.is-hot .co-metric-num { color: #7c3aed; }
.co-metric-label { font-size: 12px; color: #909399; margin-top: 2px; }
html.dark .co-metric-num { color: #e5e7eb; }
html.dark .co-metric-label { color: #9ca3af; }
.co-dist { margin-left: auto; min-width: 260px; flex: 1 1 260px; }
.co-dist-title { font-size: 12px; color: #909399; margin-bottom: 4px; }
.co-dist-row { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
.co-dist-label { font-size: 12px; color: #606266; width: 74px; flex-shrink: 0; }
.co-dist-row :deep(.el-progress) { flex: 1; margin: 0; }
.co-dist-num { font-size: 12px; color: #606266; width: 46px; text-align: right; }
.co-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.co-tabs { margin-top: 2px; }

@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: flex-start; }
  .toolbar-right { width: 100%; }
  .conflict-header { flex-direction: column; align-items: flex-start; }
  .conflict-actions { margin-left: 0; }
  .co-dist { margin-left: 0; }
}
</style>
