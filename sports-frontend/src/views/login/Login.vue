<template>
  <div class="login-root">
    <!-- ===== Left Panel（品牌展示，无动画角色） ===== -->
    <div class="leftPanel">
      <div class="leftTop">
        <div class="brandMark">
          <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
            <rect width="28" height="28" rx="7" fill="white" fill-opacity="0.15"/>
            <path d="M7 14L12 9L17 14L12 19L7 14Z" fill="white" fill-opacity="0.9"/>
            <path d="M13 14L18 9L21 12V16L18 19L13 14Z" fill="white" fill-opacity="0.5"/>
          </svg>
        </div>
        <span class="brandName">运动会编排</span>
      </div>

      <div class="leftBody">
        <h2 class="heroTitle">运动会智能编排系统</h2>
        <p class="heroSub">从班级名单到成绩排名，一站式赛事管理</p>
        <ul class="featureList">
          <li><span class="dot"></span>多角色协作：管理员 / 体育老师 / 班主任 / 学生</li>
          <li><span class="dot"></span>Excel 智能导入导出</li>
          <li><span class="dot"></span>道次编排 · 项目编排 · 排名积分</li>
          <li><span class="dot"></span>规则完全自定义</li>
        </ul>
      </div>

      <div class="decorBlur1"></div>
      <div class="decorBlur2"></div>
      <div class="decorGrid"></div>
    </div>

    <!-- ===== Right Panel（登录表单） ===== -->
    <div class="rightPanel">
      <div class="formWrapper" :class="{ shaking: shaking }">
        <p class="panelTag">WELCOME</p>
        <div class="mobileLogo">
          <div class="mobileLogoIcon">
            <svg width="20" height="20" viewBox="0 0 28 28" fill="none">
              <rect width="28" height="28" rx="7" fill="#1e40af" fill-opacity="0.15"/>
            </svg>
          </div>
          <span>运动会编排</span>
        </div>

        <div class="formHeader">
          <h1 class="formTitle">登录系统</h1>
          <p class="formSubtitle">选择角色，输入账号密码</p>
        </div>

        <!-- Role selector -->
        <div class="roleRow">
          <div v-for="r in roles" :key="r.key"
               class="roleItem" :class="{on:activeRole===r.key}"
               @click="activeRole=r.key">
            <span class="ri">{{r.icon}}</span>
            <span class="rl">{{r.label}}</span>
          </div>
        </div>

        <form id="loginForm" autocomplete="off" @submit.prevent="handleLogin">
          <div class="inputGroup">
            <div class="fieldLabel">用户名</div>
            <div class="inputWrapper">
              <span class="prefixIcon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
              </span>
              <input type="text" id="username" v-model="loginForm.username" placeholder="请输入用户名" autocomplete="off">
            </div>
            <div class="fieldError" v-if="usernameError">{{ usernameError }}</div>
          </div>

          <div class="inputGroup">
            <div class="fieldLabel">密码</div>
            <div class="inputWrapper">
              <span class="prefixIcon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </span>
              <input :type="showPassword ? 'text' : 'password'" v-model="loginForm.password" placeholder="请输入密码" autocomplete="off">
              <button type="button" class="eyeToggle" tabindex="-1" @click="showPassword = !showPassword">
                <svg v-if="!showPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              </button>
            </div>
            <div class="fieldError" v-if="passwordError">{{ passwordError }}</div>
          </div>

          <div class="errorBox" v-if="errorMsg">{{ errorMsg }}</div>

          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:18px;">
            <label style="display:flex;align-items:center;gap:6px;font-size:13px;color:#64748b;cursor:pointer;user-select:none;">
              <input type="checkbox" v-model="rememberMe" style="accent-color:#0f766e;width:14px;height:14px;">
              记住我
            </label>
          </div>

          <button type="submit" class="submitBtn" :disabled="loading">
            <span v-if="loading" class="spin"></span>{{loading?'安全验证中...':btnText}}
          </button>
        </form>

        <p class="footerHint">
          <span @click="showForgot=true" style="cursor:pointer">忘记密码？</span> |
          <span @click="showHelp=true" style="cursor:pointer">使用帮助</span> |
          admin / admin123
        </p>
      </div>
    </div>

    <!-- Dialogs -->
    <el-dialog v-model="showForgot" title="忘记密码" width="380px">
      <el-alert type="info" :closable="false" show-icon>
        <template #title><p>👨‍🎓 学生→班主任 | 👨‍🏫 教师→管理员 | 🛡️ 管理员→其他管理员</p></template>
      </el-alert>
    </el-dialog>
    <el-dialog v-model="showHelp" title="使用帮助" width="380px">
      <el-alert type="success" :closable="false" show-icon>
        <template #title><p>🛡️管理员 🏅体育老师 📚班主任 👨‍🎓学生</p></template>
      </el-alert>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const activeRole = ref('TEACHER')
const loading = ref(false)
const showForgot = ref(false)
const showHelp = ref(false)
const showPassword = ref(false)
const loginForm = reactive({ username: '', password: '' })
const rememberMe = ref(true)
const usernameError = ref('')
const passwordError = ref('')
const errorMsg = ref('')
const shaking = ref(false)

const roles = [
  { key: 'ADMIN', icon: '🛡️', label: '管理员' },
  { key: 'TEACHER', icon: '🏅', label: '体育老师' },
  { key: 'CLASS_TEACHER', icon: '📚', label: '班主任' },
  { key: 'STUDENT', icon: '👨‍🎓', label: '学生' }
]

const btnText = computed(() => ({
  ADMIN: '管理员登录',
  TEACHER: '体育老师登录',
  CLASS_TEACHER: '班主任登录',
  STUDENT: '学生登录'
}[activeRole.value] || '登录'))

function shake() {
  shaking.value = false
  requestAnimationFrame(() => { shaking.value = true })
  setTimeout(() => { shaking.value = false }, 500)
}

async function handleLogin() {
  usernameError.value = ''
  passwordError.value = ''
  errorMsg.value = ''

  let valid = true
  if (!loginForm.username || loginForm.username.length < 3) {
    usernameError.value = loginForm.username ? '用户名长度不能少于3个字符' : '请输入用户名'
    valid = false
  }
  if (!loginForm.password || loginForm.password.length < 6) {
    passwordError.value = loginForm.password ? '密码长度不能少于6个字符' : '请输入密码'
    valid = false
  }
  if (!valid) { shake(); return }

  loading.value = true
  try {
    const r = await authStore.login(loginForm.username, loginForm.password)
    if (r.success) {
      if (rememberMe.value) {
        localStorage.setItem('saved_username', loginForm.username)
        localStorage.setItem('saved_role', activeRole.value)
      } else {
        localStorage.removeItem('saved_username')
        localStorage.removeItem('saved_role')
      }
      ElMessage.success('登录成功')
      const role = authStore.userRole
      if (role === 'TEACHER' || role === 'SUPER_ADMIN') router.push('/teacher/dashboard')
      else if (role === 'CLASS_TEACHER') router.push('/class-teacher/dashboard')
      else router.push('/student/home')
    } else {
      errorMsg.value = r.message || '登录失败'
      shake()
    }
  } catch {
    errorMsg.value = '网络错误，请重试'
    shake()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const savedUsername = localStorage.getItem('saved_username')
  const savedRole = localStorage.getItem('saved_role')
  if (savedUsername) {
    loginForm.username = savedUsername
    rememberMe.value = true
  }
  if (savedRole && roles.find(r => r.key === savedRole)) {
    activeRole.value = savedRole
  }
})
</script>

<style scoped>
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

.login-root {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr 1fr;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
  -webkit-font-smoothing: antialiased;
}
@media (max-width: 1024px) {
  .login-root { grid-template-columns: 1fr; }
}

/* ========== Left Panel ========== */
.leftPanel {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px;
  background: linear-gradient(145deg, #0f172a 0%, #1e3a8a 50%, #1e40af 100%);
  overflow: hidden;
}
@media (max-width: 1024px) {
  .leftPanel { display: none; }
}

.leftTop {
  position: relative;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
  letter-spacing: 0.5px;
}

.brandMark {
  width: 40px; height: 40px;
  border-radius: 10px;
  background: rgba(255,255,255,0.12);
  border: 1px solid rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  backdrop-filter: blur(8px);
}

.brandName {
  color: #ffffff;
  font-size: 20px; font-weight: 700;
  letter-spacing: 1px;
}

.leftBody {
  position: relative;
  z-index: 20;
  max-width: 440px;
  margin: auto 0;
}

.heroTitle {
  font-size: 34px;
  font-weight: 800;
  color: #fff;
  line-height: 1.3;
  margin-bottom: 14px;
  letter-spacing: -0.02em;
}

.heroSub {
  font-size: 15px;
  color: rgba(255,255,255,0.7);
  margin-bottom: 32px;
}

.featureList {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.featureList li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: rgba(255,255,255,0.85);
}

.dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #22d3ee;
  flex-shrink: 0;
  box-shadow: 0 0 8px rgba(34,211,238,0.8);
}

.decorBlur1 {
  position: absolute;
  top: 15%; right: 10%;
  width: 300px; height: 300px;
  background: rgba(59,130,246,0.25);
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none; z-index: 0;
}

.decorBlur2 {
  position: absolute;
  bottom: 10%; left: 5%;
  width: 400px; height: 400px;
  background: rgba(30,64,175,0.3);
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none; z-index: 0;
}

.decorGrid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.03) 1px, transparent 1px);
  background-size: 40px 40px;
  pointer-events: none; z-index: 1;
}

/* ========== Right Panel ========== */
.rightPanel {
  display: flex; align-items: center; justify-content: center;
  padding: 48px;
  background:
    radial-gradient(circle at 20% 0%, rgba(241,245,255,0.9), transparent 35%),
    radial-gradient(circle at 90% 80%, rgba(221,255,246,0.9), transparent 40%),
    linear-gradient(160deg, #f8fafc 0%, #eef2ff 52%, #ecfeff 100%);
}

.formWrapper {
  width: 100%; max-width: 450px;
  border-radius: 24px;
  background: rgba(255,255,255,0.86);
  border: 1px solid rgba(148,163,184,0.24);
  box-shadow: 0 24px 50px rgba(30,41,59,0.12);
  backdrop-filter: blur(14px);
  padding: 36px 32px 30px;
}

.formWrapper.shaking {
  animation: form-shake 0.4s ease;
}

@keyframes form-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-8px); }
  40% { transform: translateX(8px); }
  60% { transform: translateX(-5px); }
  80% { transform: translateX(5px); }
}

.panelTag {
  margin: 0 0 16px; text-align: center;
  font-size: 11px; font-weight: 700;
  letter-spacing: 0.14em; color: #0f766e;
}

.mobileLogo {
  display: none;
  align-items: center; justify-content: center;
  gap: 8px;
  font-size: 18px; font-weight: 700;
  color: #0f172a; margin-bottom: 24px;
}
@media (max-width: 1024px) {
  .mobileLogo { display: flex; }
}

.mobileLogoIcon {
  width: 32px; height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #dbeafe 0%, #ccfbf1 100%);
  display: flex; align-items: center; justify-content: center;
}

.formHeader { text-align: center; margin-bottom: 24px; }

.formTitle {
  font-size: 28px; font-weight: 700;
  letter-spacing: -0.03em; color: #0b1220;
  margin: 0 0 8px 0; line-height: 1.3;
}

.formSubtitle {
  font-size: 14px; color: #64748b;
  margin: 0; line-height: 1.6;
}

/* ========== Role selector ========== */
.roleRow {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-bottom: 24px;
}

.roleItem {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 4px;
  border-radius: 14px;
  border: 2px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.2s;
  background: #f8fafc;
}
.roleItem:hover { border-color: #93c5fd; background: #eff6ff; }
.roleItem.on {
  border-color: #3b82f6;
  background: #eff6ff;
  box-shadow: 0 2px 12px rgba(59,130,246,0.18);
}

.ri { font-size: 22px; }
.rl { font-size: 12px; font-weight: 600; color: #334155; }

/* ========== Form ========== */
.inputGroup { margin-bottom: 20px; }

.fieldLabel {
  font-size: 13px; font-weight: 600;
  color: #334155; margin-bottom: 6px;
  letter-spacing: 0.3px; text-transform: uppercase;
}

.inputWrapper {
  position: relative;
  display: flex; align-items: center;
  height: 50px;
  background: rgba(248,250,252,0.95);
  border: 1px solid #d8dee8;
  border-radius: 14px;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}
.inputWrapper:hover { border-color: #14b8a6; background: #ffffff; }
.inputWrapper:focus-within {
  border-color: #0f766e;
  box-shadow: 0 0 0 4px rgba(20,184,166,0.15);
  background: #ffffff;
}

.inputWrapper .prefixIcon {
  display: flex; align-items: center;
  padding-left: 14px; color: #94a3b8;
}

.inputWrapper input {
  flex: 1; height: 100%;
  border: none; outline: none;
  background: transparent;
  font-size: 14px; color: #111827;
  padding: 0 12px;
  font-family: inherit;
}
.inputWrapper input::placeholder { color: #9aa4b2; }

.eyeToggle {
  display: flex; align-items: center;
  padding-right: 14px;
  color: #64748b; cursor: pointer;
  font-size: 16px; transition: color 0.2s;
  background: none; border: none;
}
.eyeToggle:hover { color: #0f766e; }

.fieldError {
  font-size: 13px; color: #b91c1c; margin-top: 4px;
}

.errorBox {
  padding: 11px 14px; font-size: 13px;
  color: #b91c1c; background: #fff1f2;
  border: 1px solid #fecdd3; border-radius: 12px;
  margin-bottom: 16px;
}

.submitBtn {
  width: 100%; height: 52px;
  font-size: 15px; font-weight: 600;
  border-radius: 14px;
  background: linear-gradient(135deg, #0f766e 0%, #14b8a6 55%, #22d3ee 100%);
  border: none; color: #fff;
  letter-spacing: 0.5px; cursor: pointer;
  box-shadow: 0 14px 26px rgba(15,118,110,0.24);
  transition: transform 0.2s, box-shadow 0.2s, opacity 0.2s;
  font-family: inherit;
  display: flex; align-items: center; justify-content: center; gap: 8px;
}
.submitBtn:hover { transform: translateY(-1px); box-shadow: 0 16px 28px rgba(15,118,110,0.32); }
.submitBtn:active { transform: translateY(1px); opacity: 0.85; }
.submitBtn:disabled { opacity: 0.6; cursor: not-allowed; transform: none; }

.footerHint {
  text-align: center; font-size: 12px;
  color: #64748b; margin: 20px 6px 0; line-height: 1.6;
}

@keyframes spin2 { to { rotate: 360deg; } }
.spin {
  width: 14px; height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin2 0.6s linear infinite;
  display: inline-block;
}
</style>
