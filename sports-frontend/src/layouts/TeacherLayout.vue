<template>
  <div class="teacher-layout">
    <!-- Desktop sidebar -->
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="logo"><span class="logo-icon">🏟️</span><span class="logo-text">运动会编排</span></div>
        <div class="role-badge">
          <el-tag size="small" :type="isAdmin?'danger':'primary'" effect="dark" round>{{ isAdmin?'管理员':'体育老师' }}</el-tag>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="sidebar-menu">
        <el-menu-item index="/teacher/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item-group title="基础数据">
          <el-menu-item index="/teacher/classes"><el-icon><School /></el-icon><span>班级管理</span></el-menu-item>
          <el-menu-item index="/teacher/athletes"><el-icon><UserFilled /></el-icon><span>运动员管理</span></el-menu-item>
          <el-menu-item index="/teacher/events"><el-icon><Trophy /></el-icon><span>项目管理</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="赛事管理">
          <el-menu-item index="/teacher/registrations"><el-icon><Document /></el-icon><span>报名管理</span></el-menu-item>
          <el-menu-item index="/teacher/arrange"><el-icon><Grid /></el-icon><span>智能编排</span></el-menu-item>
          <el-menu-item index="/teacher/scores"><el-icon><EditPen /></el-icon><span>成绩管理</span></el-menu-item>
          <el-menu-item index="/teacher/ranking"><el-icon><TrendCharts /></el-icon><span>排名积分</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/reports"><el-icon><DataAnalysis /></el-icon><span>统计报表</span></el-menu-item>
        <el-menu-item index="/teacher/settings"><el-icon><Setting /></el-icon><span>系统设置</span></el-menu-item>
        <template v-if="isAdmin">
          <el-divider style="margin:8px 0;border-color:rgba(255,255,255,.1)" />
          <div style="padding:4px 16px;font-size:11px;color:rgba(255,255,255,.35)">管理员专用</div>
          <el-menu-item index="/teacher/settings?tab=users"><el-icon><Avatar /></el-icon><span>用户管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=batch"><el-icon><MagicStick /></el-icon><span>批量创建</span></el-menu-item>
        </template>
      </el-menu>
    </div>

    <!-- Mobile drawer -->
    <el-drawer v-model="drawerVisible" direction="ltr" :show-close="false" size="240px" class="mobile-sidebar-drawer">
      <template #header>
        <div class="logo"><span class="logo-icon">🏟️</span><span class="logo-text">运动会编排</span></div>
      </template>
      <el-menu :default-active="activeMenu" router class="sidebar-menu" @select="drawerVisible = false">
        <el-menu-item index="/teacher/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item-group title="基础数据">
          <el-menu-item index="/teacher/classes"><el-icon><School /></el-icon><span>班级管理</span></el-menu-item>
          <el-menu-item index="/teacher/athletes"><el-icon><UserFilled /></el-icon><span>运动员管理</span></el-menu-item>
          <el-menu-item index="/teacher/events"><el-icon><Trophy /></el-icon><span>项目管理</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="赛事管理">
          <el-menu-item index="/teacher/registrations"><el-icon><Document /></el-icon><span>报名管理</span></el-menu-item>
          <el-menu-item index="/teacher/arrange"><el-icon><Grid /></el-icon><span>智能编排</span></el-menu-item>
          <el-menu-item index="/teacher/scores"><el-icon><EditPen /></el-icon><span>成绩管理</span></el-menu-item>
          <el-menu-item index="/teacher/ranking"><el-icon><TrendCharts /></el-icon><span>排名积分</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/reports"><el-icon><DataAnalysis /></el-icon><span>统计报表</span></el-menu-item>
        <el-menu-item index="/teacher/settings"><el-icon><Setting /></el-icon><span>系统设置</span></el-menu-item>
        <template v-if="isAdmin">
          <el-divider style="margin:8px 0;border-color:rgba(255,255,255,.1)" />
          <el-menu-item index="/teacher/settings?tab=users"><el-icon><Avatar /></el-icon><span>用户管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=batch"><el-icon><MagicStick /></el-icon><span>批量创建</span></el-menu-item>
        </template>
      </el-menu>
    </el-drawer>

    <div class="main-container">
      <div class="header">
        <div class="header-left">
          <el-button class="mobile-menu-btn" :icon="Expand" size="small" text @click="drawerVisible = true" />
          <el-breadcrumb><el-breadcrumb-item>{{ isAdmin?'管理员端':'体育老师端' }}</el-breadcrumb-item><el-breadcrumb-item v-if="title">{{ title }}</el-breadcrumb-item></el-breadcrumb>
        </div>
        <div class="header-right">
          <el-button :icon="isDark ? 'Sunny' : 'Moon'" circle size="small" @click="toggleDark" class="theme-toggle" />
          <el-dropdown trigger="click" @command="handleCmd">
            <div class="user-trigger">
              <el-avatar :size="30" icon="UserFilled" />
              <span class="uname">{{ authStore.user?.realName || authStore.user?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="pwd"><el-icon><Lock /></el-icon>修改密码</el-dropdown-item>
                <el-dropdown-item v-if="isAdmin" command="reqs"><el-icon><Bell /></el-icon>密码重置请求</el-dropdown-item>
                <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <div class="content"><router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view></div>
    </div>

    <el-dialog v-model="showPwd" title="修改密码" width="380px" :close-on-click-modal="false">
      <el-form :model="pf" label-width="80px">
        <el-form-item label="旧密码"><el-input v-model="pf.old" type="password" show-password placeholder="请输入旧密码" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pf.new1" type="password" show-password placeholder="请输入新密码" /></el-form-item>
        <el-form-item label="确认密码"><el-input v-model="pf.new2" type="password" show-password placeholder="请再次输入新密码" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showPwd=false">取消</el-button><el-button type="primary" @click="doPwd" :loading="pwdLoading">确认</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Expand } from '@element-plus/icons-vue'
import request from '@/utils/request'

const route = useRoute(); const router = useRouter(); const authStore = useAuthStore()
const activeMenu = ref('/teacher/dashboard'); const title = computed(()=>route.meta?.title||'')
const isAdmin = computed(()=>authStore.isAdmin)
const showPwd = ref(false); const pwdLoading = ref(false)
const isDark = ref(document.documentElement.classList.contains('dark'))
const drawerVisible = ref(false)
const pf = reactive({old:'',new1:'',new2:''})

function toggleDark() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
  localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
}

function handleCmd(cmd) {
  if (cmd==='logout') { ElMessageBox.confirm('确定退出？','提示',{type:'warning'}).then(()=>{authStore.logout();router.push('/login')}).catch(()=>{}) }
  else if (cmd==='pwd') { pf.old='';pf.new1='';pf.new2='';showPwd.value=true }
  else if (cmd==='reqs') { router.push('/teacher/settings?tab=users') }
}
async function doPwd() {
  if (!pf.old||!pf.new1) return ElMessage.warning('请填写密码')
  if (pf.new1!==pf.new2) return ElMessage.warning('两次密码不一致')
  pwdLoading.value=true
  try { await request.post('/auth/change-password',{oldPassword:pf.old,newPassword:pf.new1}); ElMessage.success('密码修改成功'); showPwd.value=false }
  catch(e){} finally { pwdLoading.value=false }
}
onMounted(()=>{activeMenu.value=route.path})
watch(() => route.path, (p) => { activeMenu.value = p }, { immediate: true })
</script>

<style scoped>
.teacher-layout { display:flex; height:100vh; overflow:hidden; background:var(--bg-page); }
.sidebar { width:220px; flex-shrink:0; background:linear-gradient(180deg,#1a1a2e,#16213e,#0f3460); display:flex; flex-direction:column; box-shadow:2px 0 20px rgba(0,0,0,.2); z-index:10; }
.sidebar-header { padding:16px; border-bottom:1px solid rgba(255,255,255,.06); }
.logo { display:flex; align-items:center; gap:10px; margin-bottom:8px; }
.logo-icon { font-size:24px; } .logo-text { color:#fff; font-size:16px; font-weight:700; letter-spacing:.5px; }
.role-badge { margin-left:34px; }
.sidebar-menu { border-right:none; background:transparent!important; flex:1; padding-top:4px; overflow-y:auto; }
.sidebar-menu :deep(.el-menu-item-group__title) { color:rgba(255,255,255,.3); font-size:11px; padding:12px 16px 4px; letter-spacing:.5px; }
.sidebar-menu :deep(.el-menu-item) { color:rgba(255,255,255,.6)!important; margin:2px 8px; border-radius:10px; height:42px; line-height:42px; font-size:13px; transition:all .2s; }
.sidebar-menu :deep(.el-menu-item:hover) { background:rgba(255,255,255,.08)!important; color:#fff!important; }
.sidebar-menu :deep(.el-menu-item.is-active) { background:linear-gradient(135deg,rgba(59,130,246,.85),rgba(99,102,241,.85))!important; color:#fff!important; box-shadow:0 4px 12px rgba(59,130,246,.3); }
.main-container { flex:1; display:flex; flex-direction:column; overflow:hidden; }
.header { height:52px; display:flex; align-items:center; justify-content:space-between; padding:0 20px; background:var(--bg-header); backdrop-filter:blur(12px); border-bottom:1px solid var(--border-light); flex-shrink:0; }
.header-right { display:flex; align-items:center; gap:8px; }
.theme-toggle { border:1px solid var(--border-light); background:var(--bg-card); }
.user-trigger { display:flex; align-items:center; gap:8px; cursor:pointer; padding:4px 10px; border-radius:10px; transition:background var(--transition-fast); }
.user-trigger:hover { background:rgba(0,0,0,.04); }
.uname { font-size:13px; color:var(--text-primary); font-weight:500; }
.content { flex:1; overflow-y:auto; padding:20px; background:var(--bg-page); }

/* Page transition */
.page-fade-enter-active { animation: fadeIn 0.3s ease; }
.page-fade-leave-active { animation: fadeIn 0.2s ease reverse; }
@keyframes fadeIn { from { opacity:0; transform:translateY(6px); } to { opacity:1; transform:translateY(0); } }

/* Mobile responsive */
.mobile-menu-btn { display: none; }
.header-left { display:flex; align-items:center; gap:8px; }
@media (max-width: 768px) {
  .sidebar { display: none !important; }
  .mobile-menu-btn { display: inline-flex !important; }
  .header { padding: 0 12px !important; }
  .uname { display: none; }
  .content { padding: 12px !important; }
}
</style>
