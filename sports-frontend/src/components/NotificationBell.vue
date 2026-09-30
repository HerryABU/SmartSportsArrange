<template>
  <el-dropdown trigger="click" @command="onCommand" @visible-change="onVisible">
    <div class="notif-trigger" :class="{ active: unread > 0 }">
      <el-badge :value="unread" :hidden="!unread" :max="99">
        <el-icon :size="18"><Bell /></el-icon>
      </el-badge>
    </div>
    <template #dropdown>
      <el-dropdown-menu>
        <div class="notif-panel">
          <div class="notif-head">
            <span>通知</span>
            <el-button v-if="list.length" link type="primary" size="small" @click="markAll">全部已读</el-button>
          </div>
          <div class="notif-list">
            <div v-for="n in list" :key="n.id" class="notif-item" :class="{ unread: !n.read }" @click="read(n)">
              <div class="notif-title">
                <span v-if="!n.read" class="dot" />
                {{ n.title }}
              </div>
              <div class="notif-content">{{ n.content }}</div>
              <div class="notif-time">{{ fmtTime(n.createdAt) }}</div>
            </div>
            <el-empty v-if="!list.length" description="暂无通知" :image-size="56" />
          </div>
        </div>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import request from '@/utils/request'

const list = ref([])
const unread = ref(0)
let timer = null

async function fetchData() {
  try {
    const [l, u] = await Promise.all([
      request.get('/notifications'),
      request.get('/notifications/unread-count')
    ])
    list.value = Array.isArray(l) ? l : []
    unread.value = (u && u.count) || 0
  } catch (e) {
    /* 未登录/接口不可用静默 */
  }
}

function onVisible(visible) {
  if (visible) fetchData()
}

function onCommand(cmd) {
  if (cmd === 'all') markAll()
}

async function read(n) {
  if (n.read) return
  try {
    await request.put(`/notifications/${n.id}/read`)
    n.read = true
    if (unread.value > 0) unread.value--
  } catch (e) { /* ignore */ }
}

async function markAll() {
  try {
    await request.put('/notifications/read-all')
    list.value.forEach(n => { n.read = true })
    unread.value = 0
  } catch (e) { /* ignore */ }
}

function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const pad = x => String(x).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  fetchData()
  timer = setInterval(fetchData, 30000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.notif-trigger {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%;
  cursor: pointer; color: var(--text-primary);
  border: 1px solid var(--border-light); background: var(--bg-card);
  transition: all .2s;
}
.notif-trigger:hover { background: rgba(0,0,0,.05); }
.notif-panel { width: 340px; max-height: 420px; display: flex; flex-direction: column; }
.notif-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px; font-weight: 600; border-bottom: 1px solid var(--border-light);
}
.notif-list { overflow-y: auto; max-height: 360px; }
.notif-item {
  padding: 10px 14px; border-bottom: 1px solid var(--border-light); cursor: pointer;
}
.notif-item:hover { background: rgba(0,0,0,.03); }
.notif-item.unread { background: rgba(59,130,246,.05); }
.notif-title { font-size: 13px; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: #f56c6c; flex-shrink: 0; }
.notif-content { font-size: 12px; color: var(--text-secondary); margin-top: 3px; line-height: 1.5; }
.notif-time { font-size: 11px; color: #c0c4cc; margin-top: 3px; }
</style>
