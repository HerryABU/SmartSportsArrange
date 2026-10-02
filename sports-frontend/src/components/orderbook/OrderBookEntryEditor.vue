<template>
  <div class="entry-editor">
    <!-- 头部：当前目录 + 新增入口 -->
    <div class="editor-head">
      <div class="head-left">
        <span class="hint">细则 · 介绍内容</span>
        <span v-if="sectionTitle" class="sec-name">「{{ sectionTitle }}」</span>
      </div>
      <div class="head-actions">
        <el-button size="small" type="primary" plain :icon="Plus" :disabled="!sectionId" @click="openCustom">
          自定义细则
        </el-button>
        <el-dropdown trigger="click" :disabled="!sectionId" @command="addSystem">
          <el-button size="small" type="success" plain :icon="Grid">
            系统板块
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="opt in sourceOptions"
                :key="opt.key"
                :command="opt.key"
              >{{ opt.label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <div v-if="!entries.length" class="empty-tip">
      这个目录还没有细则。点上面「自定义细则」手写介绍文字，或挂一个系统板块（自动取编排数据）。
    </div>

    <div v-else class="entry-list">
      <div
        v-for="row in entries"
        :key="row.id"
        class="entry-row"
        :class="{ offy: !row.enabled }"
      >
        <div class="er-main">
          <span class="er-title">{{ row.title || '（未命名）' }}</span>
          <el-tag size="small" effect="plain" :type="row.kind === 'SYSTEM' ? 'warning' : 'primary'">
            {{ row.kind === 'SYSTEM' ? sourceLabel(row.sourceKey) : '自定义文字' }}
          </el-tag>
          <span v-if="!row.enabled" class="er-off">未启用</span>
        </div>

        <div class="er-summary">{{ summarize(row) }}</div>

        <div class="er-actions">
          <el-switch :model-value="row.enabled" size="small" @change="(v) => toggleEnabled(row, v)" />
          <el-button link type="info" size="small" :icon="Top" @click="doMove(row, -1)" />
          <el-button link type="info" size="small" :icon="Bottom" @click="doMove(row, 1)" />
          <el-button link type="primary" size="small" :icon="Edit" @click="openEdit(row)" />
          <el-button link type="danger" size="small" :icon="Delete" @click="doRemove(row)" />
        </div>
      </div>
    </div>

    <!-- 删除后的撤销条（软删可恢复） -->
    <el-alert
      v-if="undo"
      class="undo-bar"
      type="info"
      :closable="false"
      show-icon
    >
      <span>细则已删除，可撤销。</span>
      <el-button link type="primary" @click="doUndo">撤销</el-button>
      <el-button link type="info" @click="undo = null">知道了</el-button>
    </el-alert>

    <!-- 自定义细则：标题 + 正文 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑细则' : '新增细则'"
      width="560px"
      append-to-body
    >
      <el-form label-width="76px" size="default">
        <el-form-item label="标题"><el-input v-model="form.title" placeholder="例如：竞赛须知 / 防疫要求" /></el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.content" type="textarea" :rows="8" placeholder="多段之间空一行即可，导出时按段落断开" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 系统板块：选数据源 -->
    <el-dialog v-model="srcDialog" title="挂载系统板块" width="460px" append-to-body>
      <el-form label-width="76px">
        <el-form-item label="板块">
          <el-select v-model="form.sourceKey" placeholder="选一个系统数据源" style="width:100%">
            <el-option v-for="opt in sourceOptions" :key="opt.key" :label="opt.label" :value="opt.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" placeholder="留空则用板块默认标题" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="srcDialog = false">取消</el-button>
        <el-button type="primary" @click="submitSystem">挂载</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 秩序册「细则」编辑区：一个目录下挂哪些内容条目。
 *
 * 细则分两类，编辑形态完全不同，所以拆成两个入口而不是塞进一个表单：
 *  - 自定义：管理员手写的介绍文字（TEXT，正文写在 textarea 里）；
 *  - 系统板块：挂 SCHEDULE / EVENTS / CLASSES / ARRANGE / NUMBERS，表格数据由渲染侧现取，
 *    管理员只决定「这块要不要出现、叫什么名字、排在第几位」。
 *
 * 排序口径：细则永远只在<b>当前目录内</b>上下移（和后端 OrderBookEntryService.move 一致），
 * 跨目录拖拽会让条目「跳到别的章节」，管理员很难查。
 */
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Grid, Top, Bottom, Edit, Delete, ArrowDown } from '@element-plus/icons-vue'
import {
  createEntry, updateEntry, moveEntry, deleteEntry, restoreEntry
} from '@/api/orderBook'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  sectionId: { type: [Number, String], default: null },
  sectionTitle: { type: String, default: '' }
})
const emit = defineEmits(['changed'])

/** 后端 normalizeSource 只认这五个 key，前端下拉必须同集，多一个都会被打回 NULL。 */
const sourceOptions = [
  { key: 'SCHEDULE', label: '运动会日程' },
  { key: 'EVENTS', label: '竞赛项目一览' },
  { key: 'CLASSES', label: '班级 / 运动员名单' },
  { key: 'ARANGE', label: '编排结果（道次场次）' },
  { key: 'NUMBERS', label: '号码对应表' }
]

const loading = ref(false)
const undo = ref(null)

const dialogVisible = ref(false)
const srcDialog = ref(false)
const editing = ref(false)
const form = reactive({ id: null, title: '', content: '', sourceKey: 'SCHEDULE' })

watch(() => props.sectionId, () => {
  undo.value = null
})

function sourceLabel (key) {
  const hit = sourceOptions.find(o => o.key === key)
  return hit ? hit.label : (key || '系统板块')
}

function summarize (row) {
  if (row.kind === 'SYSTEM') {
    return '由系统数据自动填充，导出时按板块渲染'
  }
  const c = row.content || ''
  if (!c) {
    return '（还没有正文）'
  }
  const first = c.split('\n').map(s => s.trim()).filter(Boolean)[0] || ''
  return first.length > 42 ? first.slice(0, 42) + '…' : (first || '（空）')
}

// ==================== 自定义细则 ====================

function openCustom () {
  editing.value = false
  form.title = ''
  form.content = ''
  dialogVisible.value = true
}

function openEdit (row) {
  editing.value = true
  form.id = row.id
  form.title = row.title || ''
  form.content = row.content || ''
  dialogVisible.value = true
}

function submit () {
  const title = (form.title || '').trim()
  if (!title) {
    ElMessage.warning('先给细则起个标题')
    return
  }
  const payload = { sectionId: props.sectionId, title, contentType: 'TEXT', content: form.content || '' }
  const call = editing.value
    ? updateEntry(form.id, payload)
    : createEntry(payload)
  call.then(() => {
    dialogVisible.value = false
    emit('changed')
  }).catch(() => {})
}

// ==================== 系统板块 ====================

function addSystem (key) {
  const opt = sourceOptions.find(o => o.key === key)
  form.sourceKey = key
  form.title = opt ? opt.label : ''
  srcDialog.value = true
}

function submitSystem () {
  const key = form.sourceKey
  if (!key) {
    ElMessage.warning('选一个系统板块')
    return
  }
  createEntry({
    sectionId: props.sectionId,
    title: (form.title || '').trim() || sourceLabel(key),
    kind: 'SYSTEM',
    sourceKey: key,
    contentType: 'TABLE',
    content: ''
  }).then(() => {
    srcDialog.value = false
    emit('changed')
  }).catch(() => {})
}

// ==================== 动作 ====================

function toggleEnabled (row, v) {
  updateEntry(row.id, { enabled: v }).then(() => emit('changed')).catch(() => {})
}

function doMove (row, dir) {
  moveEntry(row.id, dir).then(() => emit('changed')).catch(() => {})
}

async function doRemove (row) {
  try {
    await ElMessageBox.confirm(`删除细则「${row.title || '未命名'}」？删除后可撤销。`, '删除细则', {
      type: 'warning',
      confirmButtonText: '删除'
    })
  } catch (e) {
    return
  }
  loading.value = true
  deleteEntry(row.id).then(() => {
    undo.value = row.id
    emit('changed')
  }).catch(() => {}).finally(() => {
    loading.value = false
  })
}

function doUndo () {
  const id = undo.value
  undo.value = null
  if (id == null) {
    return
  }
  restoreEntry(id).then(() => emit('changed')).catch(() => {})
}
</script>

<style scoped>
.entry-editor { display: flex; flex-direction: column; gap: 10px; }
.editor-head {
  display: flex; align-items: center; justify-content: space-between;
  padding-bottom: 8px; border-bottom: 1px solid var(--el-border-color-lighter);
}
.head-left { display: flex; align-items: baseline; gap: 8px; }
.sec-name { font-weight: 600; color: #2b3a55; }
.head-actions { display: flex; gap: 8px; }

.empty-tip {
  padding: 26px 12px; text-align: center; color: #9aa5b5; font-size: 13px;
  background: #fafbfd; border: 1px dashed #dfe4ec; border-radius: 8px;
}

.entry-list { display: flex; flex-direction: column; gap: 6px; max-height: 380px; overflow-y: auto; }
.entry-row {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) auto;
  align-items: center; gap: 10px;
  padding: 8px 10px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px; background: #fff;
}
.entry-row:hover { border-color: #c3d4f5; }
.entry-row.offy { background: #fafafa; }
.er-main { display: flex; align-items: center; gap: 8px; min-width: 0; }
.er-title { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry-row.offy .er-title { color: #a8a8a8; text-decoration: line-through; }
.er-off { font-size: 12px; color: #b0b6c0; }
.er-summary {
  font-size: 12px; color: #8b95a5; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.er-actions { display: flex; align-items: center; gap: 2px; opacity: .7; }
.entry-row:hover .er-actions { opacity: 1; }

.undo-bar { margin-top: 2px; }
.undo-bar :deep(.el-alert__content) { display: flex; align-items: center; gap: 4px; }
</style>
