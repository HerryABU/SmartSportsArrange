<template>
  <div class="ct-dashboard" v-loading="loading">
    <!-- 角色横幅（班主任：绿色系，由 role-class-teacher 提供） -->
    <div class="role-hero">
      <h2 class="role-hero-title">🎓 {{ stats.className || '尚未绑定班级' }}</h2>
      <p class="role-hero-desc">
        {{ stats.className
          ? `全班 ${stats.athleteCount} 名运动员 · 已报名 ${stats.registrationCount} 人次 · 通过 ${stats.approvedCount} 人次`
          : '请联系管理员在「班级管理」中为本账号绑定班级' }}
      </p>
      <div class="role-hero-tags">
        <span class="role-hero-tag">班主任视角</span>
        <span class="role-hero-tag">本班运动员 {{ stats.athleteCount || 0 }}</span>
        <span class="role-hero-tag">获奖(前三) {{ stats.awardCount || 0 }}</span>
      </div>
    </div>

    <!-- 统计 -->
    <div class="role-stats">
      <div v-for="s in statCards" :key="s.label" class="role-stat">
        <div class="role-stat-num">{{ s.value }}</div>
        <div class="role-stat-label">{{ s.label }}</div>
      </div>
    </div>

    <!-- 分步流程（顺序 + 录入） -->
    <div class="role-card">
      <div class="role-card-head">
        <span class="role-card-title">班级工作流 · 一步一步来</span>
        <span class="role-card-extra">
          <el-tag v-if="currentStepNo" type="primary" size="small" effect="dark" round>
            建议当前：第 {{ currentStepNo }} 步
          </el-tag>
          <span v-else>共 {{ steps.length }} 步</span>
        </span>
      </div>
      <div class="role-steps">
        <div
          v-for="s in steps"
          :key="s.no"
          class="role-step"
          :class="{ 'is-current': currentStepNo === s.no, 'is-done': s.done }"
        >
          <div class="role-step-no">{{ s.no }}</div>
          <div class="role-step-body">
            <div class="role-step-head">
              <span class="role-step-title">{{ s.title }}</span>
              <span class="role-step-tag" :class="'tag-' + s.kind">{{ kindLabel(s.kind) }}</span>
              <div class="role-step-action">
                <el-button size="small" :type="currentStepNo === s.no ? 'primary' : 'default'" @click="$router.push(s.to)">
                  {{ actionLabel(s.kind) }}
                </el-button>
              </div>
            </div>
            <div class="role-step-desc">{{ s.desc }}</div>
            <div v-if="s.tip" class="role-step-tip">💡 {{ s.tip }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 今日安排 + 近期报名 -->
    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">🗓️ 本班已编排安排</span>
            <span class="role-card-extra">{{ schedules.length }} 条</span>
          </div>
          <ul v-if="schedules.length" class="role-list">
            <li v-for="(s, i) in schedules.slice(0, 10)" :key="i" class="role-list-item">
              <div class="role-list-main">
                <div class="role-list-title">{{ s.eventName }}</div>
                <div class="role-list-desc">第{{ s.heat }}组 · {{ s.laneNumber }}道 · {{ s.athleteName }}</div>
              </div>
            </li>
          </ul>
          <el-empty v-else description="暂无已编排赛程（报名通过并由体育老师编排后展示）" :image-size="70" />
        </div>
      </el-col>

      <el-col :xs="24" :md="12">
        <div class="role-card">
          <div class="role-card-head">
            <span class="role-card-title">🕐 近期报名</span>
            <el-button link type="primary" size="small" @click="$router.push('/class-teacher/registration')">去报名 →</el-button>
          </div>
          <el-table v-if="registrationList.length" :data="registrationList" size="small" border max-height="280">
            <el-table-column prop="athleteName" label="运动员" min-width="90" />
            <el-table-column prop="eventName" label="项目" min-width="120" />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 'approved' ? 'success' : row.status === 'withdrawn' ? 'info' : 'warning'"
                  size="small">{{ row.status === 'approved' ? '已通过' : row.status === 'withdrawn' ? '已取消' : '待审核' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无报名记录" :image-size="70" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { UserFilled, DocumentAdd, Medal, Trophy } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const stats = ref({ athleteCount: 0, registrationCount: 0, approvedCount: 0, awardCount: 0, className: '' })
const registrationList = ref([])
const schedules = ref([])

const statCards = ref([
  { label: '运动员数', value: 0, icon: UserFilled, color: '#2563eb', bg: '#eff6ff' },
  { label: '已报名(人次)', value: 0, icon: DocumentAdd, color: '#16a34a', bg: '#f0fdf4' },
  { label: '已通过', value: 0, icon: Medal, color: '#d97706', bg: '#fffbeb' },
  { label: '获奖(前三)', value: 0, icon: Trophy, color: '#dc2626', bg: '#fef2f2' }
])

// ============ 分步工作流（顺序 + 录入） ============
// kind: entry=录入 / arrange=编排 / report=统计；done 用于定位「建议当前步骤」
const steps = computed(() => {
  const list = [
    {
      title: '录入班级名单', kind: 'entry', to: '/class-teacher/athletes',
      desc: 'Excel 导入全班花名册（自动创建学生账号 + 运动员），或手动添加。',
      tip: '名单不全报名会漏人，导入后先核对本班人数。',
      done: (stats.value.athleteCount || 0) > 0
    },
    {
      title: '报名运动会项目', kind: 'entry', to: '/class-teacher/registration',
      desc: '按学号定位逐个报名，或整队按项目批量勾选；已报好的整表可批量导入。',
      tip: '报名提交后需体育老师审核通过，才算正式参赛。',
      done: (stats.value.registrationCount || 0) > 0
    },
    {
      title: '查看本班赛程', kind: 'arrange', to: '/class-teacher/schedule',
      desc: '查看本班运动员的组次、道次与时间安排（体育老师编排后生成）。',
      done: schedules.value.length > 0
    },
    {
      title: '查看成绩与获奖', kind: 'report', to: '/class-teacher/results',
      desc: '本班成绩明细、总分与金银铜（前三名）汇总。',
      done: (stats.value.awardCount || 0) > 0
    }
  ]
  return list.map((s, i) => ({ ...s, no: i + 1 }))
})

/** 建议当前步骤 = 第一个尚未完成的步骤 */
const currentStepNo = computed(() => {
  const hit = steps.value.find(s => !s.done)
  return hit ? hit.no : 0
})

const kindLabel = (k) => ({ entry: '录入', config: '配置', arrange: '编排', report: '统计' }[k] || k)
const actionLabel = (k) => ({ entry: '去录入 →', config: '去配置 →', arrange: '去查看 →', report: '去查看 →' }[k] || '去处理 →')

onMounted(async () => {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/dashboard')
    if (res?.stats) {
      stats.value = { ...stats.value, ...res.stats }
      statCards.value[0].value = res.stats.athleteCount || 0
      statCards.value[1].value = res.stats.registrationCount || 0
      statCards.value[2].value = res.stats.approvedCount || 0
      statCards.value[3].value = res.stats.awardCount || 0
    }
    registrationList.value = res?.registrations || []
    schedules.value = res?.schedules || []
  } catch (e) { console.error(e) }
  finally { loading.value = false }
})
</script>

<style scoped>
.ct-dashboard { display: flex; flex-direction: column; }
</style>
