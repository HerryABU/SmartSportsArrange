<template>
  <div class="heat-grid-container">
    <!-- 统计概览 -->
    <div v-if="statistics" class="stats-bar">
      <div class="stat-chip">
        <span class="chip-icon">👥</span>
        <span class="chip-label">运动员</span>
        <span class="chip-value">{{ statistics.totalAthletes }}</span>
      </div>
      <div class="stat-chip">
        <span class="chip-icon">🏁</span>
        <span class="chip-label">组数</span>
        <span class="chip-value">{{ statistics.totalHeats }}</span>
      </div>
      <div class="stat-chip" v-if="statistics.avgPerHeat">
        <span class="chip-icon">📊</span>
        <span class="chip-label">平均每组</span>
        <span class="chip-value">{{ statistics.avgPerHeat }}</span>
      </div>
      <div class="stat-chip" v-if="statistics.emptyLanes != null">
        <span class="chip-icon">⬜</span>
        <span class="chip-label">空道次</span>
        <span class="chip-value">{{ statistics.emptyLanes }}</span>
      </div>
      <div class="stat-chip" v-if="statistics.lanes">
        <span class="chip-icon">🏟️</span>
        <span class="chip-label">跑道数</span>
        <span class="chip-value">{{ statistics.lanes }}</span>
      </div>
    </div>

    <!-- 道次网格 -->
    <div class="heats-wrapper">
      <div v-for="heat in heats" :key="heat.heat || heat.heatNo" class="heat-block">
        <div class="heat-title">
          <span class="heat-badge">第 {{ heat.heat || heat.heatNo }} 组</span>
          <span v-if="lottery" class="draw-badge" title="本组道次为随机抽签分配">🎲 抽签</span>
        </div>
        <div class="lane-grid">
          <div
            v-for="lane in (heat.lanes || [])"
            :key="lane.lane"
            class="lane-cell"
            :class="{
              'lane-empty': !lane.athleteId && !lane.athlete,
              'lane-occupied': lane.athleteId || lane.athlete,
              'lane-locked': lane.locked
            }"
          >
            <div class="lane-number">{{ lane.lane }}</div>
            <div v-if="lane.athleteId || lane.athlete" class="lane-content">
              <div class="lane-athlete">
                <span class="athlete-name">{{ lane.athleteName || (lane.athlete && lane.athlete.name) }}</span>
                <span class="athlete-number" v-if="lane.number || (lane.athlete && lane.athlete.number)">
                  #{{ lane.number || (lane.athlete && lane.athlete.number) }}
                </span>
              </div>
              <div class="lane-class">
                {{ lane.className || (lane.athlete && lane.athlete.className) || '-' }}
              </div>
            </div>
            <div v-else class="lane-empty-text">空</div>
            <!-- U12/B18：锁定人工调整项 —— 锁定后自动重排会跳过它，不再覆盖 -->
            <el-tooltip
              v-if="lockable && lane.arrangementId"
              :content="lane.locked
                ? '已锁定：自动重排将跳过此项（点击解锁）'
                : '点击锁定：防止后续自动重排覆盖人工调整'"
              placement="top"
            >
              <button
                type="button"
                class="lane-lock"
                :class="{ 'is-locked': lane.locked }"
                @click.stop="emit('toggle-lock', lane)"
              >
                {{ lane.locked ? '🔒' : '🔓' }}
              </button>
            </el-tooltip>
          </div>
        </div>

        <!-- 裁判分配（智能编排产出，可在此查看本组次裁判） -->
        <div v-if="heat.referees && heat.referees.length" class="heat-referees">
          <span class="ref-label">
            <el-icon><Medal /></el-icon> 裁判 ({{ heat.refereeCount }})
          </span>
          <span v-for="r in heat.referees" :key="r.id" class="ref-tag">{{ r.name }}</span>
        </div>
        <div v-else-if="showRefereeSlot" class="heat-referees heat-referees-empty">
          <span class="ref-label"><el-icon><Medal /></el-icon> 裁判</span>
          <span class="ref-none">本组次未安排裁判</span>
        </div>
      </div>
    </div>

    <!-- 无数据 -->
    <el-empty v-if="!heats || heats.length === 0" description="暂无编排数据" />
  </div>
</template>

<script setup>
import { Medal } from '@element-plus/icons-vue'

defineProps({
  heats: {
    type: Array,
    default: () => []
  },
  statistics: {
    type: Object,
    default: null
  },
  // U12/B18：是否显示「锁定」开关（仅编排结果页开启，预览/只读场景关闭）
  lockable: {
    type: Boolean,
    default: false
  },
  // 是否展示「未安排裁判」占位（仅当项目设置了组次裁判数量且确实需裁判时开启）
  showRefereeSlot: {
    type: Boolean,
    default: false
  },
  // 抽签（随机道次）：开启后组内道次为随机分配，组标题显示抽签徽标
  lottery: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['toggle-lock'])
</script>

<style scoped>
.heat-grid-container {
  width: 100%;
}

/* 统计栏 */
.stats-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.stat-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(135deg, #f0f5ff 0%, #e8f0fe 100%);
  border: 1px solid #d0e0f7;
  border-radius: 20px;
  padding: 6px 16px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.stat-chip:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.15);
}

.chip-icon {
  font-size: 16px;
}

.chip-label {
  font-size: 12px;
  color: #909399;
  font-weight: 500;
}

.chip-value {
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
}

/* 组块 */
.heats-wrapper {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}

.heat-block {
  background: #fff;
  border-radius: 16px;
  border: 2px solid #e8ecf1;
  overflow: hidden;
  transition: box-shadow 0.3s, border-color 0.3s;
}

.heat-block:hover {
  box-shadow: 0 8px 24px rgba(64, 158, 255, 0.12);
  border-color: #c0d8f7;
}

.heat-title {
  background: linear-gradient(135deg, #409eff 0%, #337ecc 100%);
  padding: 10px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.heat-badge {
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 2px;
}

.draw-badge {
  font-size: 11px;
  color: #7a4a00;
  background: #ffe9b3;
  border-radius: 10px;
  padding: 2px 8px;
  font-weight: 600;
  letter-spacing: 0;
}

/* 跑道网格 */
.lane-grid {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
}

.lane-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  border-radius: 10px;
  padding: 8px 12px;
  min-height: 50px;
  transition: background 0.2s;
}

.lane-empty {
  background: #fafbfc;
  border: 1px dashed #dcdfe6;
}

.lane-occupied {
  background: linear-gradient(135deg, #f0f7ff 0%, #e8f2fc 100%);
  border: 1px solid #d0e4f7;
}

.lane-cell:hover {
  background: #ecf5ff;
}

.lane-number {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}

.lane-empty .lane-number {
  background: #c0c4cc;
}

.lane-content {
  flex: 1;
  min-width: 0;
}

.lane-athlete {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}

.athlete-name {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.athlete-number {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 1px 6px;
  border-radius: 8px;
}

.lane-class {
  font-size: 12px;
  color: #909399;
}

.lane-empty-text {
  flex: 1;
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
}

/* U12/B18 锁定开关 */
.lane-locked {
  background: linear-gradient(135deg, #fff8e6 0%, #fdf1d6 100%);
  border-color: #f0d39a;
}

.lane-lock {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #909399;
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  padding: 0;
  transition: all 0.2s;
}

.lane-lock:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
}

.lane-lock.is-locked {
  border-color: #e6a23c;
  background: #fdf6ec;
}

/* 裁判分配 footer */
.heat-referees {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: linear-gradient(135deg, #f0f9ff 0%, #e6f4ff 100%);
  border-top: 1px dashed #b6dcff;
}

.heat-referees-empty {
  background: #fafafa;
  border-top: 1px dashed #e4e7ed;
}

.ref-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #1d6fc4;
  margin-right: 2px;
}

.heat-referees-empty .ref-label {
  color: #909399;
}

.ref-tag {
  font-size: 12px;
  color: #1d6fc4;
  background: #fff;
  border: 1px solid #b6dcff;
  border-radius: 10px;
  padding: 2px 10px;
  font-weight: 500;
}

.ref-none {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
