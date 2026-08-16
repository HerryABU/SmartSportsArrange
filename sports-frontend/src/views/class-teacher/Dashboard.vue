<template>
  <div class="ct-dashboard" v-loading="loading">
    <!-- 班级信息 -->
    <div class="class-info-bar">
      <el-tag type="primary" size="large" effect="dark">
        📚 {{ stats.className || '未绑定班级' }}
      </el-tag>
      <span class="info-note" v-if="!stats.className">请联系管理员在"班级管理"中设置班主任</span>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="12" style="margin-top:12px">
      <el-col :xs="12" :sm="12" :md="8" v-for="stat in statCards" :key="stat.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" :style="{background: stat.bg}">
            <el-icon :size="28"><component :is="stat.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <el-row :gutter="12" style="margin-top:12px">
      <el-col :xs="24" :sm="12" :md="8">
        <el-card shadow="hover" class="action-card" @click="$router.push('/class-teacher/athletes')">
          <el-icon :size="32" color="#409EFF"><UserFilled /></el-icon>
          <div class="action-title">运动员管理</div>
          <div class="action-desc">导入名单 / 查看运动员</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="8">
        <el-card shadow="hover" class="action-card" @click="$router.push('/class-teacher/registration')">
          <el-icon :size="32" color="#67C23A"><DocumentAdd /></el-icon>
          <div class="action-title">项目报名</div>
          <div class="action-desc">为运动员报名比赛</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="8">
        <el-card shadow="hover" class="action-card" @click="$router.push('/class-teacher/results')">
          <el-icon :size="32" color="#E6A23C"><Trophy /></el-icon>
          <div class="action-title">成绩查看</div>
          <div class="action-desc">查看本班成绩</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 近期报名 -->
    <el-card shadow="never" style="margin-top:12px" v-if="registrationList.length">
      <template #header><span>近期报名</span></template>
      <el-table :data="registrationList" border size="small">
        <el-table-column prop="athleteName" label="运动员" />
        <el-table-column prop="eventName" label="项目" />
        <el-table-column prop="status" label="状态">
          <template #default="{row}"><el-tag :type="row.status==='approved'?'success':'warning'" size="small">{{ row.status==='approved'?'已通过':'待审核' }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { UserFilled, DocumentAdd, Trophy, Medal } from '@element-plus/icons-vue'
import request from '@/utils/request'

const loading = ref(false)
const stats = ref({ athleteCount:0, registrationCount:0, approvedCount:0, awardCount:0, className:'' })
const registrationList = ref([])

const statCards = ref([
  { label: '运动员数', value: 0, icon: 'UserFilled', bg: '#ecf5ff' },
  { label: '已报名', value: 0, icon: 'DocumentAdd', bg: '#f0f9eb' },
  { label: '已通过', value: 0, icon: 'Medal', bg: '#fdf6ec' },
  { label: '获奖', value: 0, icon: 'Trophy', bg: '#fef0f0' }
])

onMounted(async () => {
  loading.value = true
  try {
    const res = await request.get('/class-teacher/dashboard')
    if (res?.stats) {
      stats.value = res.stats
      statCards.value[0].value = res.stats.athleteCount || 0
      statCards.value[1].value = res.stats.registrationCount || 0
      statCards.value[2].value = res.stats.approvedCount || 0
      statCards.value[3].value = res.stats.awardCount || 0
    }
    registrationList.value = res?.registrations || []
  } catch(e) { console.error(e) }
  finally { loading.value = false }
})
</script>

<style scoped>
.class-info-bar { display:flex; align-items:center; gap:12px; padding:12px 0; }
.info-note { color:#909399; font-size:13px; }
.stat-card { display:flex; align-items:center; gap:12px; }
.stat-icon { width:48px; height:48px; border-radius:12px; display:flex; align-items:center; justify-content:center; }
.stat-value { font-size:24px; font-weight:bold; color:#303133; }
.stat-label { font-size:13px; color:#909399; }
.action-card { text-align:center; cursor:pointer; transition:transform 0.2s; }
.action-card:hover { transform:translateY(-4px); }
.action-title { font-size:15px; font-weight:600; margin:8px 0 4px; color:#303133; }
.action-desc { font-size:12px; color:#909399; }
@media(max-width:768px) {
  .class-info-bar { flex-direction: column; align-items: flex-start; gap: 6px; }
  .stat-value { font-size:20px; }
}
</style>
