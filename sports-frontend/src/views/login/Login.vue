<template>
  <div class="login-root">
    <!-- ===== Left Panel ===== -->
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

      <div class="charactersArea">
        <div id="charactersContainer" style="position:relative;width:550px;height:400px">

          <!-- Purple character -->
          <div id="purpleChar" style="position:absolute;bottom:0;left:70px;width:180px;height:400px;background:#6C3FF5;border-radius:10px 10px 0 0;z-index:1;transform-origin:bottom center;will-change:transform;">
            <div id="purpleFace" style="position:absolute;display:flex;gap:32px;left:45px;top:40px;">
              <div class="eyeball" data-max-distance="5" style="border-radius:50%;display:flex;align-items:center;justify-content:center;overflow:hidden;will-change:height;width:18px;height:18px;background:white;">
                <div class="eyeball-pupil" style="width:7px;height:7px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              </div>
              <div class="eyeball" data-max-distance="5" style="border-radius:50%;display:flex;align-items:center;justify-content:center;overflow:hidden;will-change:height;width:18px;height:18px;background:white;">
                <div class="eyeball-pupil" style="width:7px;height:7px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              </div>
            </div>
          </div>

          <!-- Black character -->
          <div id="blackChar" style="position:absolute;bottom:0;left:240px;width:120px;height:310px;background:#2D2D2D;border-radius:8px 8px 0 0;z-index:2;transform-origin:bottom center;will-change:transform;overflow:hidden;">
            <div id="blackFace" style="position:absolute;display:flex;gap:24px;left:26px;top:32px;">
              <div class="eyeball" data-max-distance="4" style="border-radius:50%;display:flex;align-items:center;justify-content:center;overflow:hidden;will-change:height;width:16px;height:16px;background:white;">
                <div class="eyeball-pupil" style="width:6px;height:6px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              </div>
              <div class="eyeball" data-max-distance="4" style="border-radius:50%;display:flex;align-items:center;justify-content:center;overflow:hidden;will-change:height;width:16px;height:16px;background:white;">
                <div class="eyeball-pupil" style="width:6px;height:6px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              </div>
            </div>
          </div>

          <!-- Orange character -->
          <div id="orangeChar" style="position:absolute;bottom:0;left:0;width:240px;height:200px;background:#FF9B6B;border-radius:120px 120px 0 0;z-index:3;transform-origin:bottom center;will-change:transform;overflow:hidden;">
            <div id="orangeFace" style="position:absolute;display:flex;gap:32px;left:82px;top:90px;">
              <div class="pupil" data-max-distance="5" style="width:12px;height:12px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              <div class="pupil" data-max-distance="5" style="width:12px;height:12px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
            </div>
          </div>

          <!-- Yellow character -->
          <div id="yellowChar" style="position:absolute;bottom:0;left:310px;width:140px;height:230px;background:#E8D754;border-radius:70px 70px 0 0;z-index:4;transform-origin:bottom center;will-change:transform;overflow:hidden;">
            <div id="yellowFace" style="position:absolute;display:flex;gap:24px;left:52px;top:40px;">
              <div class="pupil" data-max-distance="5" style="width:12px;height:12px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
              <div class="pupil" data-max-distance="5" style="width:12px;height:12px;border-radius:50%;background:#2D2D2D;will-change:transform;"></div>
            </div>
            <div id="yellowMouth" style="position:absolute;width:80px;height:4px;background:#2D2D2D;border-radius:9999px;left:40px;top:88px;will-change:transform;"></div>
          </div>

        </div>
      </div>

      <div class="decorBlur1"></div>
      <div class="decorBlur2"></div>
      <div class="decorGrid"></div>
    </div>

    <!-- ===== Right Panel ===== -->
    <div class="rightPanel">
      <div class="formWrapper">
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
              <input type="text" id="username" v-model="loginForm.username" placeholder="请输入用户名" autocomplete="off"
                     @focus="onUnameFocus" @blur="onUnameBlur">
            </div>
            <div id="usernameError" style="display:none;font-size:13px;color:#b91c1c;margin-top:4px;"></div>
          </div>

          <div class="inputGroup">
            <div class="fieldLabel">密码</div>
            <div class="inputWrapper">
              <span class="prefixIcon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </span>
              <input type="password" id="password" v-model="loginForm.password" placeholder="请输入密码" autocomplete="off"
                     @focus="onPwFocus" @blur="onPwBlur" @input="onPwInput">
              <button type="button" class="eyeToggle" id="eyeToggle" tabindex="-1" @click="onEyeToggle">
                <!-- Eye closed icon (default) -->
                <svg id="eyeClosed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                <!-- Eye open icon (hidden by default) -->
                <svg id="eyeOpen" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:none;"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              </button>
            </div>
            <div id="passwordError" style="display:none;font-size:13px;color:#b91c1c;margin-top:4px;"></div>
          </div>

          <div class="errorBox" id="errorBox" style="display:none;"></div>

          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:18px;">
            <label style="display:flex;align-items:center;gap:6px;font-size:13px;color:#64748b;cursor:pointer;user-select:none;">
              <input type="checkbox" v-model="rememberMe" style="accent-color:#0f766e;width:14px;height:14px;">
              记住我
            </label>
          </div>

          <button type="submit" class="submitBtn" id="submitBtn" :disabled="loading">
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
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import gsap from 'gsap'

const router = useRouter()
const authStore = useAuthStore()

// ─── Reactive state ────────────────────────────────────────
const activeRole = ref('TEACHER')
const loading = ref(false)
const showForgot = ref(false)
const showHelp = ref(false)
const loginForm = reactive({ username: '', password: '' })
const rememberMe = ref(true)

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

// ─── Animation state (mirrors original JS exactly) ──────────
let showPassword = false
let isTyping = false
let passwordFocused = false
let isLooking = false
let isShaking = false

const mouse = { x: 0, y: 0 }
let rafId = 0
let purpleBlinkTimer, blackBlinkTimer, purplePeekTimer, lookingTimer

// ─── Helpers ───────────────────────────────────────────────
const passwordLength = () => loginForm.password.length
const isHidingPassword = () => passwordLength() > 0 && !showPassword
const isShowingPassword = () => passwordLength() > 0 && showPassword
const isPasswordGuardMode = () => passwordFocused

function calcPos(el) {
  const rect = el.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 3
  const dx = mouse.x - cx
  const dy = mouse.y - cy
  return {
    faceX: Math.max(-15, Math.min(15, dx / 20)),
    faceY: Math.max(-10, Math.min(10, dy / 30)),
    bodySkew: Math.max(-6, Math.min(6, -dx / 120))
  }
}

function calcEyePos(el, maxDist) {
  const r = el.getBoundingClientRect()
  const cx = r.left + r.width / 2
  const cy = r.top + r.height / 2
  const dx = mouse.x - cx
  const dy = mouse.y - cy
  const dist = Math.min(Math.sqrt(dx * dx + dy * dy), maxDist)
  const angle = Math.atan2(dy, dx)
  return { x: Math.cos(angle) * dist, y: Math.sin(angle) * dist }
}

// ─── GSAP quickTo references (set after DOM ready) ─────────
let qt = {}

// ─── Animation presets ─────────────────────────────────────
function applyLookAtEachOther() {
  qt.purpleFaceLeft(55)
  qt.purpleFaceTop(65)
  qt.blackFaceLeft(32)
  qt.blackFaceTop(12)
  document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: 3, y: 4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#blackChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: 0, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
}

function applyHidingPassword() {
  qt.purpleFaceLeft(55)
  qt.purpleFaceTop(65)
}

function applyShowPassword() {
  qt.purpleSkew(0); qt.blackSkew(0); qt.orangeSkew(0); qt.yellowSkew(0)
  qt.purpleX(0); qt.blackX(0); qt.purpleHeight(400)
  qt.purpleFaceLeft(20); qt.purpleFaceTop(35)
  qt.blackFaceLeft(10); qt.blackFaceTop(28)
  qt.orangeFaceX(50 - 82); qt.orangeFaceY(85 - 90)
  qt.yellowFaceX(20 - 52); qt.yellowFaceY(35 - 40)
  qt.mouthX(10 - 40); qt.mouthY(0)

  document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: -4, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#blackChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: -4, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#orangeChar .pupil').forEach(p => {
    gsap.to(p, { x: -5, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#yellowChar .pupil').forEach(p => {
    gsap.to(p, { x: -5, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
  })
}

function applyPasswordGuardMode() {
  qt.purpleSkew(0); qt.blackSkew(0); qt.orangeSkew(0); qt.yellowSkew(0)
  qt.purpleX(0); qt.blackX(0); qt.purpleHeight(400)
  qt.purpleFaceLeft(24); qt.purpleFaceTop(22)
  qt.blackFaceLeft(14); qt.blackFaceTop(20)
  qt.orangeFaceX(22 - 82); qt.orangeFaceY(72 - 90)
  qt.yellowFaceX(20 - 52); qt.yellowFaceY(22 - 40)
  qt.mouthX(-8); qt.mouthY(-8)

  document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: -5, y: -5, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#blackChar .eyeball-pupil').forEach(p => {
    gsap.to(p, { x: -4, y: -4, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#orangeChar .pupil').forEach(p => {
    gsap.to(p, { x: -5, y: -5, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
  })
  document.querySelectorAll('#yellowChar .pupil').forEach(p => {
    gsap.to(p, { x: -5, y: -5, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
  })
}

// ─── Shake heads on error ──────────────────────────────────
function shakeHeads() {
  if (isShaking) return
  isShaking = true

  gsap.to('.eyeball-pupil', { x: 0, y: -2, duration: 0.08, ease: 'power2.out' })
  gsap.to('.pupil', { x: 0, y: -2, duration: 0.08, ease: 'power2.out' })

  const purpleChar = document.getElementById('purpleChar')
  const purpleFace = document.getElementById('purpleFace')
  const blackChar = document.getElementById('blackChar')
  const blackFace = document.getElementById('blackFace')
  const orangeChar = document.getElementById('orangeChar')
  const orangeFace = document.getElementById('orangeFace')
  const yellowChar = document.getElementById('yellowChar')
  const yellowFace = document.getElementById('yellowFace')
  const yellowMouth = document.getElementById('yellowMouth')

  const tl = gsap.timeline({
    onComplete: () => {
      gsap.set('.eyeball-pupil', { x: 0, y: 0 })
      gsap.set('.pupil', { x: 0, y: 0 })
      isShaking = false
    }
  })

  const O = 'sine.inOut'
  const SB = 'expo.out'
  const SF = 'expo.out'

  function add(target, kfs) { tl.add(gsap.to(target, { keyframes: kfs }), 0.04) }

  add(purpleFace, [
    { left: 25, duration: 0.14, ease: O }, { left: 55, duration: 0.13, ease: O },
    { left: 34, duration: 0.11, ease: O }, { left: 48, duration: 0.09, ease: O },
    { left: 43, duration: 0.06, ease: O }, { left: 45, duration: 0.48, ease: SF }
  ])
  add(purpleChar, [
    { skewX: -3, x: 6, duration: 0.14, ease: O }, { skewX: 3, x: -6, duration: 0.13, ease: O },
    { skewX: -2, x: 3, duration: 0.11, ease: O }, { skewX: 2, x: -3, duration: 0.09, ease: O },
    { skewX: -1, x: 1, duration: 0.06, ease: O }, { skewX: 0, x: 0, duration: 0.32, ease: SB }
  ])
  add(blackFace, [
    { left: 14, duration: 0.14, ease: O }, { left: 34, duration: 0.13, ease: O },
    { left: 20, duration: 0.11, ease: O }, { left: 30, duration: 0.09, ease: O },
    { left: 24, duration: 0.06, ease: O }, { left: 26, duration: 0.48, ease: SF }
  ])
  add(blackChar, [
    { skewX: -2, x: 4, duration: 0.14, ease: O }, { skewX: 2, x: -4, duration: 0.13, ease: O },
    { skewX: -1, x: 2, duration: 0.11, ease: O }, { skewX: 1, x: -2, duration: 0.09, ease: O },
    { skewX: 0, x: 1, duration: 0.06, ease: O }, { skewX: 0, x: 0, duration: 0.32, ease: SB }
  ])
  add(orangeFace, [
    { x: -20, duration: 0.14, ease: O }, { x: 13, duration: 0.13, ease: O },
    { x: -7, duration: 0.11, ease: O }, { x: 6, duration: 0.09, ease: O },
    { x: -2, duration: 0.06, ease: O }, { x: 0, duration: 0.48, ease: SF }
  ])
  add(orangeChar, [
    { skewX: -2, duration: 0.14, ease: O }, { skewX: 2, duration: 0.13, ease: O },
    { skewX: -1, duration: 0.11, ease: O }, { skewX: 1, duration: 0.09, ease: O },
    { skewX: 0, duration: 0.32, ease: SB }
  ])
  add(yellowFace, [
    { x: -18, duration: 0.14, ease: O }, { x: 11, duration: 0.13, ease: O },
    { x: -6, duration: 0.11, ease: O }, { x: 5, duration: 0.09, ease: O },
    { x: -2, duration: 0.06, ease: O }, { x: 0, duration: 0.48, ease: SF }
  ])
  add(yellowMouth, [
    { x: -18, duration: 0.14, ease: O }, { x: 11, duration: 0.13, ease: O },
    { x: -6, duration: 0.11, ease: O }, { x: 5, duration: 0.09, ease: O },
    { x: -2, duration: 0.06, ease: O }, { x: 0, duration: 0.48, ease: SF }
  ])
  add(yellowChar, [
    { skewX: -2, duration: 0.14, ease: O }, { skewX: 2, duration: 0.13, ease: O },
    { skewX: -1, duration: 0.11, ease: O }, { skewX: 1, duration: 0.09, ease: O },
    { skewX: 0, duration: 0.32, ease: SB }
  ])
}

// ─── Main animation loop ──────────────────────────────────
function tick() {
  const container = document.getElementById('charactersContainer')
  if (!container) { rafId = requestAnimationFrame(tick); return }
  if (isShaking) { rafId = requestAnimationFrame(tick); return }

  const guardMode = isPasswordGuardMode()
  const typing = isTyping
  const hidingPw = isHidingPassword()
  const showingPw = isShowingPassword()

  if (guardMode) {
    applyPasswordGuardMode()
    rafId = requestAnimationFrame(tick)
    return
  }

  const purpleChar = document.getElementById('purpleChar')
  const blackChar = document.getElementById('blackChar')
  const orangeChar = document.getElementById('orangeChar')
  const yellowChar = document.getElementById('yellowChar')

  if (purpleChar && !showingPw) {
    const pp = calcPos(purpleChar)
    if (typing || hidingPw) {
      qt.purpleSkew(pp.bodySkew - 12)
      qt.purpleX(40)
      qt.purpleHeight(440)
    } else {
      qt.purpleSkew(pp.bodySkew)
      qt.purpleX(0)
      qt.purpleHeight(400)
    }
  }

  if (blackChar && !showingPw) {
    const bp = calcPos(blackChar)
    if (isLooking) {
      qt.blackSkew(bp.bodySkew * 1.5 + 10)
      qt.blackX(20)
    } else if (typing || hidingPw) {
      qt.blackSkew(bp.bodySkew * 1.5)
      qt.blackX(0)
    } else {
      qt.blackSkew(bp.bodySkew)
      qt.blackX(0)
    }
  }

  if (orangeChar && !showingPw) {
    const op = calcPos(orangeChar)
    qt.orangeSkew(op.bodySkew)
    qt.orangeFaceX(op.faceX)
    qt.orangeFaceY(op.faceY)
  }

  if (yellowChar && !showingPw) {
    const yp = calcPos(yellowChar)
    qt.yellowSkew(yp.bodySkew)
    qt.yellowFaceX(yp.faceX)
    qt.yellowFaceY(yp.faceY)
    qt.mouthX(yp.faceX)
    qt.mouthY(yp.faceY)
  }

  if (purpleChar && !showingPw && !isLooking) {
    const pp2 = calcPos(purpleChar)
    const pfX = pp2.faceX >= 0 ? Math.min(25, pp2.faceX * 1.5) : pp2.faceX
    qt.purpleFaceLeft(45 + pfX)
    qt.purpleFaceTop(40 + pp2.faceY)
  }

  if (blackChar && !showingPw && !isLooking) {
    const bp2 = calcPos(blackChar)
    qt.blackFaceLeft(26 + bp2.faceX)
    qt.blackFaceTop(32 + bp2.faceY)
  }

  if (!showingPw) {
    container.querySelectorAll('.pupil').forEach(p => {
      const maxDist = Number(p.dataset.maxDistance) || 5
      const ePos = calcEyePos(p, maxDist)
      gsap.set(p, { x: ePos.x, y: ePos.y })
    })

    if (!isLooking) {
      container.querySelectorAll('.eyeball').forEach(eb => {
        const maxDist = Number(eb.dataset.maxDistance) || 10
        const pupil = eb.querySelector('.eyeball-pupil')
        if (!pupil) return
        const ePos = calcEyePos(eb, maxDist)
        gsap.set(pupil, { x: ePos.x, y: ePos.y })
      })
    }
  }

  rafId = requestAnimationFrame(tick)
}

// ─── Blink animations ─────────────────────────────────────
function schedulePurpleBlink() {
  purpleBlinkTimer = setTimeout(() => {
    const eyeballs = document.querySelectorAll('#purpleChar .eyeball')
    eyeballs.forEach(el => gsap.to(el, { height: 2, duration: 0.08, ease: 'power2.in' }))
    setTimeout(() => {
      eyeballs.forEach(el => {
        const size = Number(el.style.width.replace('px', '')) || 18
        gsap.to(el, { height: size, duration: 0.08, ease: 'power2.out' })
      })
      schedulePurpleBlink()
    }, 150)
  }, Math.random() * 4000 + 3000)
}

function scheduleBlackBlink() {
  blackBlinkTimer = setTimeout(() => {
    const eyeballs = document.querySelectorAll('#blackChar .eyeball')
    eyeballs.forEach(el => gsap.to(el, { height: 2, duration: 0.08, ease: 'power2.in' }))
    setTimeout(() => {
      eyeballs.forEach(el => {
        const size = Number(el.style.width.replace('px', '')) || 16
        gsap.to(el, { height: size, duration: 0.08, ease: 'power2.out' })
      })
      scheduleBlackBlink()
    }, 150)
  }, Math.random() * 4000 + 3000)
}

// ─── Peek behavior: purple leans in when password visible ──
function schedulePeek() {
  clearTimeout(purplePeekTimer)
  purplePeekTimer = setTimeout(() => {
    if (isPasswordGuardMode() || !isShowingPassword() || passwordLength() <= 0) return

    document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => {
      gsap.to(p, { x: 4, y: 5, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
    })
    qt.purpleFaceLeft(20)
    qt.purpleFaceTop(35)

    setTimeout(() => {
      document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => {
        gsap.to(p, { x: -4, y: -4, duration: 0.3, ease: 'power2.out', overwrite: 'auto' })
      })
      schedulePeek()
    }, 800)
  }, Math.random() * 3000 + 2000)
}

// ─── Event handlers ───────────────────────────────────────
function onUnameFocus() {
  isTyping = true
  document.getElementById('usernameError').style.display = 'none'
  handleLookAtEachOther()
}

function onUnameBlur() {
  isTyping = false
  clearTimeout(lookingTimer)
  isLooking = false
  document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => gsap.killTweensOf(p))

  const userEl = document.getElementById('username')
  if (userEl.value && userEl.value.length < 3) {
    document.getElementById('usernameError').textContent = '用户名长度不能少于3个字符'
    document.getElementById('usernameError').style.display = 'block'
  }
}

function onPwFocus() {
  passwordFocused = true
  isLooking = false
  clearTimeout(lookingTimer)
  applyPasswordGuardMode()
  clearTimeout(purplePeekTimer)
}

function onPwBlur() {
  passwordFocused = false
  handleStateChange()
  clearTimeout(purplePeekTimer)
  if (isShowingPassword()) { schedulePeek() }
}

function onPwInput() {
  handleStateChange()
  clearTimeout(purplePeekTimer)
  if (isShowingPassword()) { schedulePeek() }
  document.getElementById('passwordError').style.display = 'none'
}

function onEyeToggle(e) {
  e.preventDefault()
  showPassword = !showPassword

  const eyeClosed = document.getElementById('eyeClosed')
  const eyeOpen = document.getElementById('eyeOpen')
  const pwInput = document.getElementById('password')

  if (showPassword) {
    eyeClosed.style.display = 'none'
    eyeOpen.style.display = 'block'
    pwInput.type = 'text'
  } else {
    eyeClosed.style.display = 'block'
    eyeOpen.style.display = 'none'
    pwInput.type = 'password'
  }

  handleStateChange()
  clearTimeout(purplePeekTimer)
  if (isShowingPassword()) { schedulePeek() }
}

function handleStateChange() {
  if (isPasswordGuardMode()) {
    applyPasswordGuardMode()
  } else if (isShowingPassword()) {
    applyShowPassword()
  } else if (isHidingPassword()) {
    applyHidingPassword()
  }
}

function handleLookAtEachOther() {
  if (isPasswordGuardMode() || isShowingPassword()) return
  isLooking = true
  applyLookAtEachOther()
  clearTimeout(lookingTimer)
  lookingTimer = setTimeout(() => {
    isLooking = false
    document.querySelectorAll('#purpleChar .eyeball-pupil').forEach(p => gsap.killTweensOf(p))
  }, 800)
}

// ─── Login ────────────────────────────────────────────────
async function handleLogin() {
  const errorBox = document.getElementById('errorBox')
  const usernameError = document.getElementById('usernameError')
  const passwordError = document.getElementById('passwordError')
  const submitBtn = document.getElementById('submitBtn')

  let valid = true
  errorBox.style.display = 'none'
  usernameError.style.display = 'none'
  passwordError.style.display = 'none'

  if (!loginForm.username || loginForm.username.length < 3) {
    usernameError.textContent = loginForm.username ? '用户名长度不能少于3个字符' : '请输入用户名'
    usernameError.style.display = 'block'
    valid = false
  }

  if (!loginForm.password || loginForm.password.length < 6) {
    passwordError.textContent = loginForm.password ? '密码长度不能少于6个字符' : '请输入密码'
    passwordError.style.display = 'block'
    valid = false
  }

  if (!valid) {
    shakeHeads()
    return
  }

  loading.value = true

  try {
    const r = await authStore.login(loginForm.username, loginForm.password)
    if (r.success) {
      // Remember me persistence
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
      errorBox.textContent = r.message || '登录失败'
      errorBox.style.display = 'block'
      shakeHeads()
    }
  } catch {
    errorBox.textContent = '网络错误，请重试'
    errorBox.style.display = 'block'
    shakeHeads()
  } finally {
    loading.value = false
  }
}

// ─── Mouse tracking handler ──────────────────────────────
function onMouseMove(e) { mouse.x = e.clientX; mouse.y = e.clientY }

// ─── Mount / Unmount ──────────────────────────────────────
onMounted(() => {
  // Restore saved credentials if remember me was checked
  const savedUsername = localStorage.getItem('saved_username')
  const savedRole = localStorage.getItem('saved_role')
  if (savedUsername) {
    loginForm.username = savedUsername
    rememberMe.value = true
  }
  if (savedRole && roles.find(r => r.key === savedRole)) {
    activeRole.value = savedRole
  }

  // Initialize GSAP quickTo
  const purpleChar = document.getElementById('purpleChar')
  const purpleFace = document.getElementById('purpleFace')
  const blackChar = document.getElementById('blackChar')
  const blackFace = document.getElementById('blackFace')
  const orangeChar = document.getElementById('orangeChar')
  const orangeFace = document.getElementById('orangeFace')
  const yellowChar = document.getElementById('yellowChar')
  const yellowFace = document.getElementById('yellowFace')
  const yellowMouth = document.getElementById('yellowMouth')

  gsap.set('.pupil', { x: 0, y: 0 })
  gsap.set('.eyeball-pupil', { x: 0, y: 0 })

  qt = {
    purpleSkew: gsap.quickTo(purpleChar, 'skewX', { duration: 0.3, ease: 'power2.out' }),
    blackSkew: gsap.quickTo(blackChar, 'skewX', { duration: 0.3, ease: 'power2.out' }),
    orangeSkew: gsap.quickTo(orangeChar, 'skewX', { duration: 0.3, ease: 'power2.out' }),
    yellowSkew: gsap.quickTo(yellowChar, 'skewX', { duration: 0.3, ease: 'power2.out' }),
    purpleX: gsap.quickTo(purpleChar, 'x', { duration: 0.3, ease: 'power2.out' }),
    blackX: gsap.quickTo(blackChar, 'x', { duration: 0.3, ease: 'power2.out' }),
    purpleHeight: gsap.quickTo(purpleChar, 'height', { duration: 0.3, ease: 'power2.out' }),
    purpleFaceLeft: gsap.quickTo(purpleFace, 'left', { duration: 0.3, ease: 'power2.out' }),
    purpleFaceTop: gsap.quickTo(purpleFace, 'top', { duration: 0.3, ease: 'power2.out' }),
    blackFaceLeft: gsap.quickTo(blackFace, 'left', { duration: 0.3, ease: 'power2.out' }),
    blackFaceTop: gsap.quickTo(blackFace, 'top', { duration: 0.3, ease: 'power2.out' }),
    orangeFaceX: gsap.quickTo(orangeFace, 'x', { duration: 0.2, ease: 'power2.out' }),
    orangeFaceY: gsap.quickTo(orangeFace, 'y', { duration: 0.2, ease: 'power2.out' }),
    yellowFaceX: gsap.quickTo(yellowFace, 'x', { duration: 0.2, ease: 'power2.out' }),
    yellowFaceY: gsap.quickTo(yellowFace, 'y', { duration: 0.2, ease: 'power2.out' }),
    mouthX: gsap.quickTo(yellowMouth, 'x', { duration: 0.2, ease: 'power2.out' }),
    mouthY: gsap.quickTo(yellowMouth, 'y', { duration: 0.2, ease: 'power2.out' })
  }

  // Mouse tracking
  window.addEventListener('mousemove', onMouseMove, { passive: true })

  // Start animation loop
  rafId = requestAnimationFrame(tick)

  // Start blink timers
  schedulePurpleBlink()
  scheduleBlackBlink()
})

onUnmounted(() => {
  clearTimeout(purpleBlinkTimer)
  clearTimeout(blackBlinkTimer)
  clearTimeout(purplePeekTimer)
  clearTimeout(lookingTimer)
  cancelAnimationFrame(rafId)
  window.removeEventListener('mousemove', onMouseMove)
})
</script>

<style scoped>
/* ========== Reset & Base ========== */
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

.charactersArea {
  position: relative;
  z-index: 20;
  display: flex; align-items: flex-end; justify-content: center;
  flex: 1;
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

.errorBox {
  padding: 11px 14px; font-size: 13px;
  color: #b91c1c; background: #fff1f2;
  border: 1px solid #fecdd3; border-radius: 12px;
  margin-bottom: 16px; display: none;
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

/* spin */
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
