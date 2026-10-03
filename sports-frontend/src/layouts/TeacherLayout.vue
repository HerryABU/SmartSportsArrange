<template>
  <div class="teacher-layout role-root" :class="isAdmin ? 'role-admin' : 'role-teacher'">
    <!-- Desktop sidebar -->
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="logo"><span class="logo-icon">🏟️</span><span class="logo-text">运动会编排</span></div>
        <div class="role-badge">
          <el-tag size="small" :type="isAdmin?'danger':'primary'" effect="dark" round>{{ isAdmin?'管理员':'体育老师' }}</el-tag>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="sidebar-menu">
        <el-menu-item-group title="① 运动会设置">
          <el-menu-item index="/teacher/meets"><el-icon><Collection /></el-icon><span>届 / 运动会（届次·季节）</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item-group title="② 导入报名">
          <el-menu-item index="/teacher/classes"><el-icon><School /></el-icon><span>班级管理</span></el-menu-item>
          <el-menu-item index="/teacher/athletes"><el-icon><UserFilled /></el-icon><span>运动员名单</span></el-menu-item>
          <el-menu-item index="/teacher/events"><el-icon><Trophy /></el-icon><span>比赛项目（表格2）</span></el-menu-item>
          <el-menu-item index="/teacher/venues"><el-icon><Location /></el-icon><span>场地管理</span></el-menu-item>
          <el-menu-item index="/teacher/registrations"><el-icon><Document /></el-icon><span>报名表导入·审核</span></el-menu-item>
          <el-menu-item index="/teacher/bulk-import"><el-icon><Files /></el-icon><span>多表导入</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="③ 编排比赛">
          <el-menu-item index="/teacher/schedule"><el-icon><Calendar /></el-icon><span>赛程编排</span></el-menu-item>
          <el-menu-item index="/teacher/arrange"><el-icon><Grid /></el-icon><span>道次编排</span></el-menu-item>
          <el-menu-item index="/teacher/rules"><el-icon><MagicStick /></el-icon><span>规则注入</span></el-menu-item>
          <el-menu-item index="/teacher/scores"><el-icon><EditPen /></el-icon><span>成绩录入</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="④ 统计排名">
          <el-menu-item index="/teacher/ranking"><el-icon><TrendCharts /></el-icon><span>合分排行</span></el-menu-item>
          <el-menu-item index="/teacher/reports"><el-icon><DataAnalysis /></el-icon><span>报表中心</span></el-menu-item>
          <el-menu-item index="/screen?mode=overview"><el-icon><Monitor /></el-icon><span>数据大屏</span></el-menu-item>
          <el-menu-item index="/screen?mode=ranking"><el-icon><DataLine /></el-icon><span>排行榜大屏</span></el-menu-item>
          <el-menu-item index="/teacher/progress"><el-icon><Histogram /></el-icon><span>跨届进步榜</span></el-menu-item>
        </el-menu-item-group>
          <el-menu-item-group title="⑤ 自定义项目区">
          <el-menu-item index="/teacher/custom-projects"><el-icon><Star /></el-icon><span>自定义项目区</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="⑥ 秩序册">
          <el-menu-item index="/teacher/order-book"><el-icon><Document /></el-icon><span>秩序册设计</span></el-menu-item>
          <el-menu-item index="/teacher/ball-tournament"><el-icon><Trophy /></el-icon><span>球类赛程编排</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/settings"><el-icon><Setting /></el-icon><span>系统设置</span></el-menu-item>
        <el-menu-item index="/teacher/help"><el-icon><Reading /></el-icon><span>说明书</span></el-menu-item>
        <el-menu-item index="/teacher/referee-board"><el-icon><Medal /></el-icon><span>裁判工作安排</span></el-menu-item>
        <el-menu-item index="/referee/dashboard"><el-icon><Medal /></el-icon><span>裁判工作台</span></el-menu-item>
        <template v-if="isAdmin">
          <el-divider style="margin:8px 0;border-color:rgba(255,255,255,.1)" />
          <div style="padding:4px 16px;font-size:11px;color:rgba(255,255,255,.35)">管理员专用</div>
          <el-menu-item index="/teacher/referees"><el-icon><Medal /></el-icon><span>裁判管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=users"><el-icon><Avatar /></el-icon><span>用户管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=batch"><el-icon><MagicStick /></el-icon><span>批量创建</span></el-menu-item>
        </template>
      </el-menu>
      <div class="sidebar-foot">
        <el-button class="foot-btn" :icon="Guide" @click="guideVisible = true">🧭 新手引导</el-button>
      </div>
    </div>

    <!-- Mobile drawer -->
    <el-drawer v-model="drawerVisible" direction="ltr" :show-close="false" size="240px" class="mobile-sidebar-drawer">
      <template #header>
        <div class="logo"><span class="logo-icon">🏟️</span><span class="logo-text">运动会编排</span></div>
      </template>
      <el-menu :default-active="activeMenu" router class="sidebar-menu" @select="drawerVisible = false">
        <el-menu-item-group title="① 运动会设置">
          <el-menu-item index="/teacher/meets"><el-icon><Collection /></el-icon><span>届 / 运动会（届次·季节）</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item-group title="② 导入报名">
          <el-menu-item index="/teacher/classes"><el-icon><School /></el-icon><span>班级管理</span></el-menu-item>
          <el-menu-item index="/teacher/athletes"><el-icon><UserFilled /></el-icon><span>运动员名单</span></el-menu-item>
          <el-menu-item index="/teacher/events"><el-icon><Trophy /></el-icon><span>比赛项目（表格2）</span></el-menu-item>
          <el-menu-item index="/teacher/venues"><el-icon><Location /></el-icon><span>场地管理</span></el-menu-item>
          <el-menu-item index="/teacher/registrations"><el-icon><Document /></el-icon><span>报名表导入·审核</span></el-menu-item>
          <el-menu-item index="/teacher/bulk-import"><el-icon><Files /></el-icon><span>多表导入</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="③ 编排比赛">
          <el-menu-item index="/teacher/schedule"><el-icon><Calendar /></el-icon><span>赛程编排</span></el-menu-item>
          <el-menu-item index="/teacher/arrange"><el-icon><Grid /></el-icon><span>道次编排</span></el-menu-item>
          <el-menu-item index="/teacher/rules"><el-icon><MagicStick /></el-icon><span>规则注入</span></el-menu-item>
          <el-menu-item index="/teacher/scores"><el-icon><EditPen /></el-icon><span>成绩录入</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="④ 统计排名">
          <el-menu-item index="/teacher/ranking"><el-icon><TrendCharts /></el-icon><span>合分排行</span></el-menu-item>
          <el-menu-item index="/teacher/reports"><el-icon><DataAnalysis /></el-icon><span>报表中心</span></el-menu-item>
          <el-menu-item index="/screen?mode=overview"><el-icon><Monitor /></el-icon><span>数据大屏</span></el-menu-item>
          <el-menu-item index="/screen?mode=ranking"><el-icon><DataLine /></el-icon><span>排行榜大屏</span></el-menu-item>
          <el-menu-item index="/teacher/progress"><el-icon><Histogram /></el-icon><span>跨届进步榜</span></el-menu-item>
        </el-menu-item-group>
          <el-menu-item-group title="⑤ 自定义项目区">
          <el-menu-item index="/teacher/custom-projects"><el-icon><Star /></el-icon><span>自定义项目区</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="⑥ 秩序册">
          <el-menu-item index="/teacher/order-book"><el-icon><Document /></el-icon><span>秩序册设计</span></el-menu-item>
          <el-menu-item index="/teacher/ball-tournament"><el-icon><Trophy /></el-icon><span>球类赛程编排</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item index="/teacher/settings"><el-icon><Setting /></el-icon><span>系统设置</span></el-menu-item>
        <el-menu-item index="/teacher/help"><el-icon><Reading /></el-icon><span>说明书</span></el-menu-item>
        <el-menu-item index="/teacher/referee-board"><el-icon><Medal /></el-icon><span>裁判工作安排</span></el-menu-item>
        <el-menu-item index="/referee/dashboard"><el-icon><Medal /></el-icon><span>裁判工作台</span></el-menu-item>
        <template v-if="isAdmin">
          <el-divider style="margin:8px 0;border-color:rgba(255,255,255,.1)" />
          <el-menu-item index="/teacher/referees"><el-icon><Medal /></el-icon><span>裁判管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=users"><el-icon><Avatar /></el-icon><span>用户管理</span></el-menu-item>
          <el-menu-item index="/teacher/settings?tab=batch"><el-icon><MagicStick /></el-icon><span>批量创建</span></el-menu-item>
        </template>
      </el-menu>
      <div class="sidebar-foot">
        <el-button class="foot-btn" :icon="Guide" @click="guideVisible = true">🧭 新手引导</el-button>
      </div>
    </el-drawer>

    <div class="main-container">
      <div class="header">
        <div class="header-left">
          <el-button class="mobile-menu-btn" :icon="Expand" size="small" text @click="drawerVisible = true" />
          <el-breadcrumb><el-breadcrumb-item>{{ isAdmin?'管理员端':'体育老师端' }}</el-breadcrumb-item><el-breadcrumb-item v-if="title">{{ title }}</el-breadcrumb-item></el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tooltip :content="hasActiveMeet ? '点击设置 / 切换当前届（编排、赛程、成绩均归属此届）' : '尚未设置运动会，请先到「届 / 运动会」创建并设为当前届'" placement="bottom">
            <el-tag v-if="hasActiveMeet" class="meet-pill" type="warning" effect="light" @click="goMeets">
              <el-icon><Collection /></el-icon><span>当前届：{{ meetName }}</span>
            </el-tag>
            <el-tag v-else class="meet-pill meet-pill--warn" type="danger" effect="dark" @click="goMeets">
              <el-icon><WarningFilled /></el-icon><span>未设置运动会，点此创建</span>
            </el-tag>
          </el-tooltip>
          <el-button :icon="isDark ? 'Sunny' : 'Moon'" circle size="small" @click="toggleDark" class="theme-toggle" />
          <NotificationBell />
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

    <OnboardingGuide v-model="guideVisible" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Expand, Guide, Location, Medal, Collection, WarningFilled, Document, Trophy } from '@element-plus/icons-vue'
import request from '@/utils/request'
import OnboardingGuide from '@/components/OnboardingGuide.vue'
import NotificationBell from '@/components/NotificationBell.vue'

const route = useRoute(); const router = useRouter(); const authStore = useAuthStore()
const appStore = useAppStore()
const activeMenu = ref('/teacher/dashboard'); const title = computed(()=>route.meta?.title||'')
const isAdmin = computed(()=>authStore.isAdmin)
const showPwd = ref(false); const pwdLoading = ref(false)
const isDark = ref(document.documentElement.classList.contains('dark'))
const drawerVisible = ref(false)
const guideVisible = ref(false)
const pf = reactive({old:'',new1:'',new2:''})

// 当前届：编排/赛程/成绩都归属它；点击直接跳到「届/运动会」设置与切换
const meetName = computed(() => appStore.meetName)
const hasActiveMeet = computed(() => !!appStore.currentMeet)
function goMeets() { router.push('/teacher/meets') }

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
onMounted(()=>{
  activeMenu.value=route.fullPath
  // 每次加载/进入都从后端重新拉取当前届，避免「已设置当前届却仍显示未设置」的脏状态
  appStore.fetchCurrentMeet().catch(()=>{})
  // 首次部署后自动弹出新手引导（Setup 安装成功时写入 sp_just_installed）
  try {
    const justInstalled = localStorage.getItem('sp_just_installed')
    const guideDone = localStorage.getItem('sp_guide_done')
    if (justInstalled && !guideDone) {
      guideVisible.value = true
      localStorage.removeItem('sp_just_installed')
    }
  } catch (e) {}
})
watch(() => route.fullPath, (p) => { activeMenu.value = p }, { immediate: true })
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
.sidebar-menu :deep(.el-menu-item.is-active) { background:linear-gradient(135deg,var(--role-accent),var(--role-accent-2))!important; color:#fff!important; box-shadow:0 4px 12px rgba(59,130,246,.3); }
.sidebar-foot { padding: 12px 16px 16px; }
.foot-btn { width:100%; justify-content:flex-start; color:rgba(255,255,255,.82)!important; background:rgba(255,255,255,.08)!important; border:1px solid rgba(255,255,255,.14)!important; font-size:13px; }
.foot-btn:hover { background:rgba(255,255,255,.18)!important; color:#fff!important; }
.main-container { flex:1; display:flex; flex-direction:column; overflow:hidden; }
.header { height:52px; display:flex; align-items:center; justify-content:space-between; padding:0 20px; background:var(--bg-header); backdrop-filter:blur(12px); border-bottom:1px solid var(--border-light); flex-shrink:0; }
.header-right { display:flex; align-items:center; gap:8px; }
.meet-pill { display:inline-flex; align-items:center; gap:4px; cursor:pointer; font-weight:600; border-radius:10px; padding:0 10px; height:28px; transition:all .2s; }
.meet-pill:hover { filter:brightness(.96); transform:translateY(-1px); }
.meet-pill--warn { animation:pulseWarn 1.4s ease-in-out infinite; }
@keyframes pulseWarn { 0%,100%{ box-shadow:0 0 0 0 rgba(245,108,108,.5);} 50%{ box-shadow:0 0 0 6px rgba(245,108,108,0);} }
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
