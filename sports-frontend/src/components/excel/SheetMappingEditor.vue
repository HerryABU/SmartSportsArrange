<template>
  <!--
    列映射编辑器：一张表的「表头 → 字段」逐列可改。

    独立成组件而不是塞进 BulkImport.vue，是因为这个面板在两处要复用：
    ①多表导入的解析结果行里点开，②可视化网格预览的表头行上直接改。
  -->
  <div class="mapping-editor">
    <div class="me-bar">
      <span class="me-hint">
        表头在左、字段在右。<b>带底色的</b>是系统按您指定的类型自动对上的，改动点「应用映射」后按「重新解析」生效。
      </span>
      <div class="me-ops">
        <el-button size="small" @click="clearAll">清空全部</el-button>
        <el-button size="small" type="primary" @click="emit('apply', buildColumnMap())">应用映射</el-button>
      </div>
    </div>

    <div class="me-grid">
      <div v-for="col in columns" :key="col.column" class="me-row" :class="{ 'me-row--empty': !col.field }">
        <div class="me-head" :title="col.header">
          <span class="me-head-text">{{ col.header }}</span>
          <span class="me-head-no">第 {{ col.column + 1 }} 列</span>
        </div>
        <div class="me-field">
          <el-select
            :model-value="col.field || ''"
            size="small"
            clearable
            placeholder="（该列不导入）"
            style="width: 100%"
            @update:model-value="v => onPick(col, v)"
          >
            <el-option
              v-for="o in col.options"
              :key="o.field"
              :label="o.label"
              :value="o.field"
            />
          </el-select>
        </div>
        <div class="me-flag">
          <el-tag v-if="col.field" size="small" type="success" effect="plain">已对上</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">未对上</el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

/**
 * 列映射编辑器。
 *
 * @prop {Array} columns 后端给的逐列建议（{@code SheetPreviewBuilder#advice} 的输出）：
 *   { column, header, field, label, options: [{ field, label, rank }] }
 *   options 已按「类型专属精确 → 类型专属包含 → 全局精确 → 全局包含 → 无」排好序，
 *   且包含该类型下的<b>全部</b>字段，所以任何一列都能手动选到任何字段。
 * @event apply 点「应用映射」时把 { 列号字符串: 字段名 } 抛出去（父级随 plan 一起回传后端）
 */
const props = defineProps({
  columns: { type: Array, default: () => [] }
})
const emit = defineEmits(['apply'])

// 本地副本：建议是后端给的，但用户当前改到哪一步要能在内存中来回切
const columns = ref([])
watch(() => props.columns, v => {
  columns.value = (v || []).map(c => ({
    column: c.column,
    header: c.header,
    field: c.field || '',
    options: c.options || []
  }))
}, { immediate: true, deep: true })

function onPick (col, value) {
  col.field = value || ''
}

/** 收集成「列号 → 字段」，只带非空项（空 = 该列不参与导入）。 */
function buildColumnMap () {
  const map = {}
  columns.value.forEach(c => {
    if (c.field) map[String(c.column)] = c.field
  })
  return map
}

function clearAll () {
  columns.value.forEach(c => { c.field = '' })
}
</script>

<style scoped>
.mapping-editor { font-size: 12px; }
.me-bar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; }
.me-hint { color: #9ca3af; }
.me-hint b { color: #6b7280; }
.me-ops { display: flex; gap: 6px; flex-shrink: 0; }
.me-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 6px; }
.me-row { display: flex; align-items: center; gap: 6px; padding: 4px 6px;
  border: 1px solid #e5e7eb; border-radius: 6px; background: #fafafa; }
.me-row--empty { background: #fff5f5; border-color: #fecaca; }
.me-head { flex: 0 0 100px; min-width: 0; }
.me-head-text { display: block; font-size: 12px; font-weight: 600; color: #374151;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.me-head-no { display: block; font-size: 11px; color: #9ca3af; }
.me-field { flex: 1; min-width: 0; }
.me-flag { flex: 0 0 auto; }
</style>
