<template>
  <div class="profile-page">
    <!-- Profile Header -->
    <div class="profile-card">
      <div class="profile-avatar">
        <el-avatar :size="72" icon="User" style="background:linear-gradient(135deg,#6366f1,#a855f7)" />
      </div>
      <div class="profile-info">
        <h2 class="profile-name">{{ user?.realName || user?.username || '学生' }}</h2>
        <p class="profile-role">学生</p>
      </div>
    </div>

    <!-- Stats Cards -->
    <div class="stats-grid">
      <div class="stat-card stat-primary">
        <div class="stat-number">{{ stats.registrationCount || 0 }}</div>
        <div class="stat-label">我的报名</div>
      </div>
      <div class="stat-card stat-success">
        <div class="stat-number">{{ stats.approvedCount || 0 }}</div>
        <div class="stat-label">已通过</div>
      </div>
      <div class="stat-card stat-warning">
        <div class="stat-number">{{ stats.awardCount || 0 }}</div>
        <div class="stat-label">获奖次数</div>
      </div>
    </div>

    <!-- Quick Actions -->
    <div class="section-title">快捷操作</div>
    <div class="actions-grid">
      <div class="action-item" @click="$router.push('/student/schedule')">
        <div class="action-icon" style="background:linear-gradient(135deg,#3b82f6,#60a5fa)">
          <el-icon :size="22" color="#fff"><Calendar /></el-icon>
        </div>
        <span>我的赛程</span>
      </div>
      <div class="action-item" @click="$router.push('/student/results')">
        <div class="action-icon" style="background:linear-gradient(135deg,#10b981,#34d399)">
          <el-icon :size="22" color="#fff"><Trophy /></el-icon>
        </div>
        <span>我的成绩</span>
      </div>
      <div class="action-item" @click="$router.push('/student/events')">
        <div class="action-icon" style="background:linear-gradient(135deg,#f59e0b,#fbbf24)">
          <el-icon :size="22" color="#fff"><List /></el-icon>
        </div>
        <span>项目浏览</span>
      </div>
      <div class="action-item" @click="showAbout = true">
        <div class="action-icon" style="background:linear-gradient(135deg,#6366f1,#a78bfa)">
          <el-icon :size="22" color="#fff"><InfoFilled /></el-icon>
        </div>
        <span>关于系统</span>
      </div>
    </div>

    <!-- Account Info -->
    <div class="section-title">账号信息</div>
    <div class="info-card">
      <div class="info-row"><span class="info-label">用户名</span><span class="info-value">{{ user?.username || '-' }}</span></div>
      <div class="info-row"><span class="info-label">姓名</span><span class="info-value">{{ user?.realName || user?.name || '-' }}</span></div>
      <div class="info-row"><span class="info-label">角色</span><span class="info-value">学生</span></div>
      <div class="info-row" style="border:none"><span class="info-label">学号</span><span class="info-value">{{ user?.username || '-' }}</span></div>
    </div>

    <el-dialog v-model="showAbout" title="关于系统" width="360px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="系统名称">运动会智能编排系统</el-descriptions-item>
        <el-descriptions-item label="版本">v1.0.0</el-descriptions-item>
        <el-descriptions-item label="技术栈">Spring Boot + Vue 3</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import request from '@/utils/request'

const authStore = useAuthStore()
const user = ref(authStore.user)
const stats = ref({})
const showAbout = ref(false)

onMounted(async () => {
  try {
    const res = await request.get('/student/home')
    if (res?.stats) stats.value = res.stats
  } catch {}
})
</script>

<style scoped>
.profile-page { padding-bottom: 20px; }

.profile-card {
  display: flex; align-items: center; gap: 16px;
  padding: 24px; margin-bottom: 16px;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 50%, #a855f7 100%);
  border-radius: 16px; color: #fff;
  box-shadow: 0 8px 24px rgba(99,102,241,.3);
}
.profile-avatar { flex-shrink: 0; }
.profile-name { font-size: 20px; font-weight: 700; margin: 0 0 4px; }
.profile-role { font-size: 13px; opacity: .8; margin: 0; }

.stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 20px; }
.stat-card {
  text-align: center; padding: 16px 8px; border-radius: 14px;
  background: var(--bg-card); border: 1px solid var(--border-light);
  box-shadow: var(--shadow-sm);
}
.stat-number { font-size: 24px; font-weight: 700; margin-bottom: 2px; }
.stat-label { font-size: 12px; color: var(--text-muted); }
.stat-primary .stat-number { color: #3b82f6; }
.stat-success .stat-number { color: #10b981; }
.stat-warning .stat-number { color: #f59e0b; }

.section-title { font-size: 15px; font-weight: 600; color: var(--text-primary); margin-bottom: 12px; }

.actions-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
.action-item {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 16px 8px; border-radius: 14px;
  background: var(--bg-card); border: 1px solid var(--border-light);
  cursor: pointer; transition: all .2s;
}
.action-item:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.action-icon { width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; }
.action-item span { font-size: 12px; font-weight: 500; color: var(--text-secondary); }

.info-card {
  border-radius: 14px; overflow: hidden;
  background: var(--bg-card); border: 1px solid var(--border-light);
}
.info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 16px; border-bottom: 1px solid var(--border-light);
}
.info-label { font-size: 13px; color: var(--text-muted); }
.info-value { font-size: 13px; color: var(--text-primary); font-weight: 500; }
</style>
