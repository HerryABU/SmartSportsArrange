<template>
  <router-view />
</template>

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  // Load saved theme preference
  const savedTheme = localStorage.getItem('theme') || 'light'
  document.documentElement.classList.toggle('dark', savedTheme === 'dark')
})
</script>

<style>
:root {
  /* === Premium Color System === */
  --color-primary: #3b82f6;
  --color-primary-light: #60a5fa;
  --color-primary-dark: #2563eb;
  --color-primary-bg: #eff6ff;

  --color-success: #10b981;
  --color-success-bg: #ecfdf5;
  --color-warning: #f59e0b;
  --color-warning-bg: #fffbeb;
  --color-danger: #ef4444;
  --color-danger-bg: #fef2f2;
  --color-info: #6366f1;
  --color-info-bg: #eef2ff;

  /* === Surfaces === */
  --bg-page: #f0f2f5;
  --bg-card: #ffffff;
  --bg-sidebar: linear-gradient(180deg, #1a1a2e, #16213e, #0f3460);
  --bg-header: rgba(255,255,255,0.85);
  --bg-input: #f8fafc;

  /* === Text === */
  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-muted: #94a3b8;
  --text-inverse: #ffffff;

  /* === Borders === */
  --border-light: #e2e8f0;
  --border-focus: #3b82f6;

  /* === Shadows === */
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.06);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.08);
  --shadow-lg: 0 12px 32px rgba(0,0,0,0.12);
  --shadow-xl: 0 20px 50px rgba(0,0,0,0.16);

  /* === Radius === */
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 16px;
  --radius-xl: 20px;

  /* === Transitions === */
  --transition-fast: 0.15s ease;
  --transition-normal: 0.25s ease;
  --transition-slow: 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}

/* === Dark Mode === */
html.dark {
  --bg-page: #0f172a;
  --bg-card: #1e293b;
  --bg-header: rgba(30,41,59,0.9);
  --bg-input: #334155;

  --text-primary: #f1f5f9;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;

  --border-light: #334155;
  --border-focus: #60a5fa;

  --shadow-sm: 0 1px 3px rgba(0,0,0,0.2);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.3);
  --shadow-lg: 0 12px 32px rgba(0,0,0,0.4);
}

html.dark body {
  background: var(--bg-page);
  color: var(--text-primary);
}

/* === Global Reset & Typography === */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial,
    'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', '微软雅黑', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* === Premium Scrollbar === */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 999px;
}
::-webkit-scrollbar-thumb:hover {
  background: #94a3b8;
}
html.dark ::-webkit-scrollbar-thumb {
  background: #475569;
}
html.dark ::-webkit-scrollbar-thumb:hover {
  background: #64748b;
}

/* === Global Card Style Enhancement === */
.el-card {
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--border-light) !important;
  box-shadow: var(--shadow-sm) !important;
  transition: box-shadow var(--transition-normal), transform var(--transition-normal) !important;
}
.el-card:hover {
  box-shadow: var(--shadow-md) !important;
}

/* === Premium Button Transitions === */
.el-button {
  transition: all var(--transition-fast) !important;
}
.el-button:active {
  transform: scale(0.97);
}

/* === Element Plus Overrides for Premium Feel === */
.el-table {
  border-radius: var(--radius-md) !important;
  overflow: hidden;
}
.el-table th.el-table__cell {
  background: #f8fafc !important;
  font-weight: 600 !important;
  color: var(--text-primary) !important;
}
html.dark .el-table th.el-table__cell {
  background: #1e293b !important;
}

.el-input__wrapper {
  border-radius: var(--radius-sm) !important;
  transition: box-shadow var(--transition-fast) !important;
}
.el-input__wrapper:hover {
  box-shadow: 0 0 0 1px var(--color-primary-light) inset !important;
}
.el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px var(--color-primary) inset, 0 0 0 3px rgba(59,130,246,0.15) inset !important;
}

/* === Global Animations === */
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes slideInRight {
  from { opacity: 0; transform: translateX(12px); }
  to { opacity: 1; transform: translateX(0); }
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.fade-in {
  animation: fadeIn 0.4s ease forwards;
}

/* === Mobile Responsive === */
@media (max-width: 768px) {
  /* Hide desktop sidebars — layouts swap to drawer */
  .sidebar { display: none !important; }

  /* Force el-row columns full width on mobile */
  .el-row .el-col { flex: 0 0 100% !important; max-width: 100% !important; }

  /* Horizontal scroll for tables */
  .el-table { width: 100%; overflow-x: auto; }

  /* Stack inline forms */
  .el-form--inline .el-form-item { display: block; width: 100%; margin-right: 0; }
  .el-form--inline .el-form-item__content { width: 100%; }
  .el-form--inline .el-select,
  .el-form--inline .el-input { width: 100% !important; }

  /* Dialog full-width on mobile */
  .el-dialog { width: 92vw !important; margin: 8vh auto !important; }

  /* Card padding reduction */
  .el-card { border-radius: 10px !important; }
  .el-card__header { padding: 12px 14px !important; }
  .el-card__body { padding: 12px 14px !important; }

  /* Pagination compact */
  .el-pagination { flex-wrap: wrap; justify-content: center; }
  .el-pagination .el-pagination__jump { display: none; }

  /* Tabs scrollable */
  .el-tabs__nav-wrap::after { display: none !important; }
}

@media (max-width: 480px) {
  .el-dialog { width: 96vw !important; margin: 4vh auto !important; }
  .el-dialog__body { padding: 12px !important; }
}

/* === 现代化页面头（统一各业务页：渐变图标 + 标题 + 描述 + 操作区） === */
.pg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  background: linear-gradient(120deg, rgba(59,130,246,.08), rgba(99,102,241,.06) 55%, rgba(16,185,129,.05));
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  padding: 14px 18px;
  margin-bottom: 14px;
}
.pg-titles { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.pg-ico {
  width: 44px; height: 44px; border-radius: 14px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--color-primary), var(--color-info));
  color: #fff; display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 6px 16px rgba(59,130,246,.28);
}
.pg-title { margin: 0; font-size: 19px; font-weight: 700; color: var(--text-primary); }
.pg-desc { margin: 2px 0 0; font-size: 12.5px; color: var(--text-secondary); }
.pg-actions { display: flex; gap: 8px; flex-wrap: wrap; }

/* 灵动卡片点缀 */
.hover-lift { transition: transform .25s ease, box-shadow .25s ease !important; }
.hover-lift:hover { transform: translateY(-3px); box-shadow: var(--shadow-md) !important; }
.rise-in { animation: pageRise .4s ease both; }
@keyframes pageRise {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
.chip {
  display: inline-flex; align-items: center; gap: 5px;
  border-radius: 999px; padding: 3px 10px; font-size: 12px; font-weight: 600;
}
.grad-text { background: linear-gradient(90deg, var(--color-primary), var(--color-info)); -webkit-background-clip: text; background-clip: text; color: transparent; }

/* === Print Styles === */
@media print {
  .sidebar, .header, .tabs, .topbar {
    display: none !important;
  }
  .content {
    padding: 0 !important;
  }
}
</style>
