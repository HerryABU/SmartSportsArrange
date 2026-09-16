<template>
  <div class="referee-layout role-root role-referee">
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="logo"><span class="logo-icon">🧑‍⚖️</span><span class="logo-text">裁判工作台</span></div>
        <div class="role-badge">
          <span class="role-chip">🥇 裁判视角</span>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="sidebar-menu">
        <el-menu-item index="/referee/dashboard"><el-icon><HomeFilled /></el-icon><span>我的执裁安排</span></el-menu-item>
        <el-menu-item v-if="!isReferee" index="/referee/board"><el-icon><Medal /></el-icon><span>全体裁判安排</span></el-menu-item>
        <el-menu-item index="/teacher/help"><el-icon><Reading /></el-icon><span>说明书</span></el-menu-item>
      </el-menu>
      <div class="sidebar-foot">
        <el-button v-if="!isReferee" class="foot-btn" @click="backToConsole">← 返回主控端</el-button>
        <el-button v-else class="foot-btn" @click="logout">退出登录</el-button>
      </div>
    </div>

    <div class="main">
      <div class="header">
        <span class="page-title">{{ route.meta?.title || '裁判工作台' }}</span>
        <div class="header-right">
          <span class="who">{{ authStore.user?.realName || authStore.user?.username || '' }}</span>
        </div>
      </div>
      <div class="content">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { HomeFilled, Medal, Reading } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeMenu = computed(() => route.path)
const isReferee = computed(() => !!authStore.isReferee)

function backToConsole() {
  if (authStore.isClassTeacher) router.push('/class-teacher/dashboard')
  else if (authStore.isStudent) router.push('/student/home')
  else router.push('/teacher/dashboard')
}

function logout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.referee-layout { display: flex; height: 100vh; overflow: hidden; }

.sidebar {
  width: 220px; flex-shrink: 0;
  background: linear-gradient(180deg, #2b2113, #3b2a16, #4a3319);
  display: flex; flex-direction: column;
  box-shadow: 2px 0 20px rgba(0, 0, 0, .2); z-index: 10;
}
.sidebar-header { padding: 16px; border-bottom: 1px solid rgba(255, 255, 255, .06); }
.logo { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.logo-icon { font-size: 24px; }
.logo-text { color: #fff; font-size: 16px; font-weight: 700; letter-spacing: .5px; }
.role-badge { margin-left: 34px; }

.sidebar-menu { border-right: none; background: transparent !important; flex: 1; padding-top: 4px; overflow-y: auto; }
.sidebar-menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, .6) !important;
  margin: 2px 8px; border-radius: 10px; height: 42px; line-height: 42px;
  font-size: 13px; transition: all .2s;
}
.sidebar-menu :deep(.el-menu-item:hover) { background: rgba(255, 255, 255, .08) !important; color: #fff !important; }
.sidebar-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, var(--role-accent), var(--role-accent-2)) !important;
  color: #fff !important; box-shadow: 0 4px 12px rgba(245, 158, 11, .3);
}

.sidebar-foot { padding: 12px 16px 16px; }
.foot-btn {
  width: 100%; justify-content: flex-start;
  color: rgba(255, 255, 255, .82) !important;
  background: rgba(255, 255, 255, .08) !important;
  border: 1px solid rgba(255, 255, 255, .14) !important; font-size: 13px;
}
.foot-btn:hover { background: rgba(255, 255, 255, .18) !important; color: #fff !important; }

.main { flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #f5f7fa; }
.header {
  height: 56px; flex-shrink: 0; background: #fff; border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; justify-content: space-between; padding: 0 20px;
}
.page-title { font-size: 16px; font-weight: 700; color: #303133; }
.header-right { display: flex; align-items: center; gap: 10px; }
.who { font-size: 13px; color: #909399; }

.content { flex: 1; overflow-y: auto; padding: 20px; }

.page-fade-enter-active, .page-fade-leave-active { transition: opacity .18s ease, transform .18s ease; }
.page-fade-enter-from { opacity: 0; transform: translateY(6px); }
.page-fade-leave-to { opacity: 0; transform: translateY(-6px); }

@media (max-width: 768px) {
  .sidebar { display: none; }
  .content { padding: 12px; }
}
</style>
