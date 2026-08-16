<template>
  <div class="student-layout" :class="{dark: isDark}">
    <div class="topbar">
      <span class="topbar-title">🏃 运动会</span>
      <div class="topbar-right">
        <el-button :icon="isDark ? 'Sunny' : 'Moon'" circle size="small" text style="color:#fff" @click="toggleDark" />
        <span class="user-name">{{ authStore.user?.realName || authStore.user?.username }}</span>
        <el-button text size="small" style="color:#fff" @click="logout">退出</el-button>
      </div>
    </div>
    <div class="content"><router-view v-slot="{ Component }">
      <transition name="page-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view></div>
    <div class="bottom-tabs">
      <div v-for="tab in tabs" :key="tab.path" class="tab-item" :class="{active:activeTab===tab.path}" @click="go(tab.path)">
        <div class="tab-icon-wrap"><el-icon :size="20"><component :is="tab.icon" /></el-icon></div>
        <span class="tab-label">{{ tab.label }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessageBox } from 'element-plus'
import { HomeFilled, Calendar, Trophy, List, User } from '@element-plus/icons-vue'

const route = useRoute(); const router = useRouter()
const authStore = useAuthStore()
const activeTab = ref('/student/home')
const isDark = ref(document.documentElement.classList.contains('dark'))

const tabs = [
  { path:'/student/home', label:'首页', icon:'HomeFilled' },
  { path:'/student/schedule', label:'赛程', icon:'Calendar' },
  { path:'/student/results', label:'成绩', icon:'Trophy' },
  { path:'/student/events', label:'项目', icon:'List' },
  { path:'/student/profile', label:'我的', icon:'User' },
]

function go(p){activeTab.value=p;router.push(p)}
function toggleDark(){
  isDark.value=!isDark.value
  document.documentElement.classList.toggle('dark',isDark.value)
  localStorage.setItem('theme',isDark.value?'dark':'light')
}
function logout(){ElMessageBox.confirm('确定退出？','提示',{type:'warning'}).then(()=>{authStore.logout();router.push('/login')}).catch(()=>{})}
onMounted(()=>{activeTab.value=route.path})
watch(() => route.path, (p) => { activeTab.value = p }, { immediate: true })
</script>

<style scoped>
.student-layout{display:flex;flex-direction:column;height:100vh;overflow:hidden;background:var(--bg-page)}
.topbar{height:50px;background:linear-gradient(135deg,#6366f1,#8b5cf6,#a855f7);display:flex;align-items:center;justify-content:space-between;padding:0 16px;flex-shrink:0;box-shadow:0 2px 12px rgba(99,102,241,.3)}
.topbar-title{color:#fff;font-size:17px;font-weight:700;letter-spacing:.5px}
.topbar-right{display:flex;align-items:center;gap:12px}
.user-name{color:rgba(255,255,255,.9);font-size:13px;font-weight:500}
.content{flex:1;overflow-y:auto;padding:16px 12px 8px}
.bottom-tabs{display:flex;background:var(--bg-card);border-top:1px solid var(--border-light);flex-shrink:0;padding:4px 0 max(4px,env(safe-area-inset-bottom))}
.tab-item{flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;padding:6px 0 4px;font-size:11px;color:var(--text-muted);cursor:pointer;transition:all .2s;border-radius:12px;margin:0 4px}
.tab-item:hover{color:var(--color-primary)}
.tab-item.active{color:#6366f1;font-weight:600}
.tab-item.active .tab-icon-wrap{background:linear-gradient(135deg,rgba(99,102,241,.15),rgba(168,85,247,.15));border-radius:10px;padding:2px 10px}
.tab-label{line-height:1.2}
.page-fade-enter-active{animation:fadeIn .25s ease}
.page-fade-leave-active{animation:fadeIn .15s ease reverse}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
</style>
