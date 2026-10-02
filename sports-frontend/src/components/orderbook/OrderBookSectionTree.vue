<template>
  <div class="section-tree">
    <div class="tree-head">
      <span class="hint">目录（章节）</span>
      <el-button link type="primary" :icon="Plus" @click="addRoot">一级目录</el-button>
    </div>

    <div v-loading="loading" class="tree-body">
      <div
        v-for="row in rows"
        :key="row.id"
        class="tree-row"
        :class="{ active: row.id === selectedId }"
        :style="{ paddingLeft: (10 + (row.level - 1) * 18) + 'px' }"
        @click="select(row)"
      >
        <div class="row-title">
          <el-input
            v-if="editingId === row.id"
            ref="editRef"
            v-model="draft"
            size="small"
            class="inline-edit"
            @keyup.enter="commitEdit(row)"
            @keyup.esc="cancelEdit"
            @blur="commitEdit(row)"
          />
          <span
            v-else
            class="title-text"
            :class="{ offy: !row.enabled }"
            :title="row.title"
          >{{ row.title }}</span>
        </div>

        <div class="row-actions" @click.stop>
          <el-switch
            :model-value="row.enabled"
            size="small"
            @change="(v) => toggleEnabled(row, v)"
          />
          <el-button link type="info" size="small" :icon="Top" @click="doMove(row, -1)" />
          <el-button link type="info" size="small" :icon="Bottom" @click="doMove(row, 1)" />
          <el-button link type="primary" size="small" :icon="Edit" @click="startEdit(row)" />
          <el-dropdown trigger="click" @command="(cmd) => onCommand(cmd, row)">
            <el-button link type="danger" size="small">{{ '···' }}</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-if="row.level === 1"
                  command="child"
                >在此目录下加二级</el-dropdown-item>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete">删除（可恢复）</el-dropdown-item>
                <el-dropdown-item command="restore" v-if="false">恢复</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <el-empty v-if="!rows.length && !loading" description="还没有目录，先加一个一级目录" :image-size="70" />
    </div>
  </div>
</template>

<script setup>
/**
 * 秩序册目录树。
 *
 * 用「缩进行」而不是 el-tree：目录只有两级，行内要同时挂开关、上下移、重命名、删除四个动作，
 * el-tree 的插槽在这些动作挤在一起时不好排版；扁平数据 + level 缩进反而是直白的做法。
 *
 * 删除走软删，删完立刻给「撤销」——管理员误点一下不该丢掉整章内容。
 */
import { ref, computed, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Top, Bottom, Edit } from '@element-plus/icons-vue'
import {
  createSection, updateSection, moveSection, deleteSection, restoreSection
} from '@/api/orderBook'

const props = defineProps({
  sections: { type: Array, default: () => [] },
  meetId: { type: [Number, String], default: null },
  selectedId: { type: [Number, String], default: null }
})
const emit = defineEmits(['select', 'changed'])

const loading = ref(false)
const editingId = ref(null)
const draft = ref('')

const rows = computed(() => props.sections || [])

function select (row) {
  if (editingId.value) return
  emit('select', row.id)
}

function startEdit (row) {
  editingId.value = row.id
  draft.value = row.title || ''
  nextTick(() => {
    const el = document.querySelector('.inline-edit input')
    if (el) {
      el.focus()
      el.select()
    }
  })
}

function cancelEdit () {
  editingId.value = null
}

function commitEdit (row) {
  if (editingId.value !== row.id) return
  const title = (draft.value || '').trim()
  editingId.value = null
  if (!title || title === (row.title || '')) return
  updateSection(row.id, { title }).then(() => {
    emit('changed')
  }).catch(() => {})
}

function toggleEnabled (row, v) {
  updateSection(row.id, { enabled: v }).then(() => emit('changed')).catch(() => {})
}

function doMove (row, dir) {
  moveSection(row.id, dir, props.meetId).then(() => emit('changed')).catch(() => {})
}

function addRoot () {
  newSection(null, 1)
}

function newSection (parentId, level) {
  ElMessageBox.prompt(level === 1 ? '一级目录名称' : '二级目录名称', '新增目录', {
    inputValue: '',
    inputPlaceholder: '例如：竞赛须知 / 安全应急预案',
    confirmButtonText: '新增',
    cancelButtonText: '取消'
  }).then(({ value }) => {
    loading.value = true
    createSection({
      meetId: props.meetId,
      parentId,
      title: (value || '').trim() || '新建目录',
      level
    }).then(() => {
      emit('changed')
    }).finally(() => {
      loading.value = false
    })
  }).catch(() => {})
}

function onCommand (cmd, row) {
  if (cmd === 'child') {
    return newSection(String(row.id), 2)
  }
  if (cmd === 'rename') {
    return startEdit(row)
  }
  if (cmd === 'restore') {
    return restoreSection(row.id).then(() => emit('changed')).catch(() => {})
  }
  if (cmd === 'delete') {
    return ElMessageBox.confirm(
      `删除「${row.title}」及其下的细则？删除后可撤销。`,
      '删除目录',
      { type: 'warning', confirmButtonText: '删除' }
    ).then(() => deleteSection(row.id).then(() => emit('changed'))).catch(() => {})
  }
}
</script>

<style scoped>
.section-tree { display: flex; flex-direction: column; height: 100%; }
.tree-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 6px 8px; border-bottom: 1px solid var(--el-border-color-lighter);
}
.tree-body { flex: 1; overflow-y: auto; max-height: 460px; padding-top: 6px; }
.tree-row {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 8px; border-radius: 6px; cursor: pointer; font-size: 13px;
}
.tree-row:hover { background: #f3f6fb; }
.tree-row.active { background: #e7efff; box-shadow: inset 0 0 0 1px #b9cdff; }
.row-title { flex: 1; min-width: 0; }
.title-text {
  display: inline-block; max-width: 100%; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; vertical-align: middle;
}
.title-text.offy { color: #a8a8a8; text-decoration: line-through; }
.row-actions { display: flex; align-items: center; gap: 2px; opacity: .55; }
.tree-row:hover .row-actions, .tree-row.active .row-actions { opacity: 1; }
</style>
