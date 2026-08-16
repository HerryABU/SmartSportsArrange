<template>
  <div class="loading-root" @click="go">
    <div class="loading-content">
      <div class="loading-title">
        <span class="l-letter">S</span>
        <span class="l-letter">P</span>
        <span class="soccer-wrap">
          <span class="l-letter">O</span>
          <span class="soccer-ball">⚽</span>
        </span>
        <span class="l-letter">R</span>
        <span class="l-letter">T</span>
        <span class="l-letter">S</span>
      </div>
      <p class="loading-sub">运动会智能编排系统</p>
      <p class="loading-hint">点击进入系统</p>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

function go() {
  router.push('/login')
}
</script>

<style scoped>
.loading-root {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #ececec;
  background-image:
    repeating-linear-gradient(45deg, #fffa 0 1px, transparent 1px 30px),
    repeating-linear-gradient(-45deg, #fffa 0 1px, transparent 1px 30px);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.loading-content {
  text-align: center;
}

.loading-title {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  font-size: 100px;
  font-weight: 900;
  line-height: 1cap;
  user-select: none;
  font-family: "Libre Franklin", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.l-letter {
  display: inline-block;
  will-change: transform;
  animation: letter-bounce 0.35s ease both infinite alternate;
  transform-origin: bottom center;
}

.l-letter:nth-child(1) { animation-delay: 0s; }
.l-letter:nth-child(2) { animation-delay: 0.05s; }
.soccer-wrap + .l-letter { animation-delay: 0.15s; }
.l-letter:last-of-type { animation-delay: 0.2s; }

.soccer-wrap {
  position: relative;
}

.soccer-ball {
  position: absolute;
  bottom: 100%;
  left: 50%;
  translate: -50% 0;
  --size: 50px;
  width: var(--size);
  height: var(--size);
  font-size: calc(var(--size) * 0.8);
  margin-bottom: 25px;
  will-change: transform;
  animation:
    rotate 4s linear infinite,
    bounce 0.35s ease-out infinite alternate;
  display: flex;
  justify-content: center;
  align-items: center;
  font-family: "Noto Emoji", sans-serif;
}

@keyframes letter-bounce {
  0%   { transform: scaleX(1.2) scaleY(0.75); }
  45%  { transform: scaleX(1) scaleY(1); }
}

@keyframes bounce {
  from { translate: -50% 100%; }
  to   { translate: -50% 0; }
}

@keyframes rotate {
  to { rotate: 360deg; }
}

.loading-sub {
  font-size: 18px;
  color: #666;
  margin-top: 24px;
  letter-spacing: 4px;
}

.loading-hint {
  font-size: 13px;
  color: #999;
  margin-top: 30px;
  animation: blink 1.5s ease infinite;
}

@keyframes blink {
  0%, 100% { opacity: 0.3; }
  50%      { opacity: 1; }
}
</style>
