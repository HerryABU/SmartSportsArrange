<template>
  <!--
    可视化 Excel 网格预览：真正的「行 × 列」表格，不是摘要。

    表头直接标在列头上（原表头 → 会被导入成哪个字段），未对上的列标红；
    数据行带 Excel 行号，分页翻页，改过映射的父组件可把 type / columnMap 传进来让网格跟着变。
  -->
  <div class="grid-preview">
    <div class="gp-bar">
      <div class="gp-meta">
        <span class="gp-title">{{ view.sheetName || 'Sheet' }}</span>
        <el-tag size="small" :type="view.type ? 'success' : 'danger'" effect="plain">
          {{ view.type || '未识别类型' }}
        </el-tag>
        <span class="gp-muted">共 {{ view.totalRows }} 行数据</span>
        <span class="gp-muted">第 {{ view.page }} / {{ totalPages }} 页</span>
        <span v-if="unmapped.length" class="gp-warn">未对上 {{ unmapped.length }} 列：{{ unmapped.join('、') }}</span>
      </div>
      <div class="gp-ops">
        <el-button size="small" :disabled="view.page <= 1" @click="fetch(page - 1)">上一页</el-button>
        <el-button size="small" :disabled="!view.hasMore" @click="fetch(page + 1)">下一页</el-button>
        <el-select v-model="pageSize" size="small" style="width: 96px" @change="fetch(1)">
          <el-option v-for="n in [20, 50, 100]" :key="n" :label="`${n} 行/页`" :value="n" />
        </el-select>
      </div>
    </div>

    <div v-loading="loading" class="gp-body">
      <div v-if="!view.rows || !view.rows.length" class="gp-empty">这张表暂时没有可显示的数据行</div>
      <table v-else class="gp-table">
        <thead>
          <tr>
            <th class="gp-corner">行号</th>
            <th v-for="h in view.headers" :key="h.col" :class="['gp-th', { 'gp-th--off': !h.field }]"
                :title="`${h.header}${h.field ? ' → ' + h.field : '（未对上，不会导入）'}`">
              <div class="gp-th-head">{{ h.header }}</div>
              <div class="gp-th-field">
                <span v-if="h.field" class="gp-th-tag">{{ h.field }}</span>
                <span v-else class="gp-th-tag gp-th-tag--off">—</span>
              </div>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in view.rows" :key="row.rowNo">
            <td class="gp-no">{{ row.rowNo }}</td>
            <td v-for="(cell, i) in row.cells" :key="i" class="gp-td" :class="{ 'gp-td--off': !fieldOf(i) }"
                :title="fieldOf(i) ? `列「${view.headers[i] && view.headers[i].header}」→ ${fieldOf(i)}` : '未对上的列，不会导入'">
              {{ cell }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

/**
 * 可视化 Excel 网格预览。
 *
 * @prop {Array} files 浏览器里持有的原始 File 数组（取数时要重新随表单提交，MultipartFile 不能被缓存）
 * @prop {Number} fileIndex 取第几个文件
 * @prop {Number} sheetIndex 取该文件里的第几个 Sheet
 * @prop {Boolean} hasHeader 首行是否表头
 * @prop {String|null} type 人工指定的类型
 * @prop {Object} columnMap 人工指定的列映射（列号字符串 → 字段名）
 */
const props = defineProps({
  files: { type: Array, default: () => [] },
  fileIndex: { type: Number, default: 0 },
  sheetIndex: { type: Number, default: 0 },
  hasHeader: { type: Boolean, default: true },
  type: { type: String, default: null },
  columnMap: { type: Object, default: () => ({}) }
})

const view = ref({
  sheetName: '', type: null, headers: [], rows: [], totalRows: 0,
  page: 1, pageSize: 50, hasMore: false, columnMap: {}
})
const loading = ref(false)
const pageSize = ref(50)

const totalPages = computed(() => Math.max(1, Math.ceil(view.value.totalRows / pageSize.value)))

/** 把后端 columnMap（列号字符串 → 字段名）落成「列下标 → 字段名」，前端按数组下标取。 */
const fieldsByIndex = computed(() => {
  const out = {}
  Object.entries(view.value.columnMap || {}).forEach(([col, field]) => {
    const i = Number(col)
    if (!Number.isNaN(i)) out[i] = field
  })
  return out
})

function fieldOf (i) {
  return fieldsByIndex.value[i] || ''
}

const unmapped = computed(() =>
  (view.value.headers || []).filter(h => !h.field).map(h => h.header))

async function fetch (page) {
  if (!props.files.length) {
    ElMessage.warning('文件已不在，请重新选择文件后再预览')
    return
  }
  loading.value = true
  try {
    const fd = new FormData()
    props.files.forEach(f => fd.append('files', f))
    fd.append('fileIndex', String(props.fileIndex))
    fd.append('sheetIndex', String(props.sheetIndex))
    fd.append('hasHeader', String(props.hasHeader))
    fd.append('page', String(Math.max(1, page)))
    fd.append('pageSize', String(pageSize.value))
    if (props.type) fd.append('type', props.type)
    if (props.columnMap && Object.keys(props.columnMap).length) {
      fd.append('columnMap', JSON.stringify(props.columnMap))
    }
    const data = await request.post('/excel/multi/sheet-data', fd,
      { headers: { 'Content-Type': 'multipart/form-data' } })
    const v = data || {}
    view.value = {
      sheetName: v.sheetName,
      type: v.type,
      // 后端 headers 是纯字符串数组；这里补上 col / field 让表头直接能标「→ 字段」
      headers: (v.headers || []).map((h, i) => ({
        col: i,
        header: h,
        field: (v.columnMap || {})[String(i)] || ''
      })),
      rows: v.rows || [],
      totalRows: v.totalRows || 0,
      page: v.page || 1,
      pageSize: v.pageSize || pageSize.value,
      hasMore: !!v.hasMore,
      columnMap: v.columnMap || {}
    }
    if (v.rows && v.rows.length) {
      // 后端给的是「行数组」，表头列数优先用 headers；两者不等时以后端表头为准补空列
      const cols = view.value.headers.length || Math.max(...v.rows.map(r => r.cells.length))
      view.value.rows = v.rows.map(r => {
        const cells = r.cells || []
        while (cells.length < cols) cells.push('')
        return { rowNo: r.rowNo, cells }
      })
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '预览取数失败')
  } finally {
    loading.value = false
  }
}

defineExpose({ fetch, refresh: fetch })

watch(() => [props.sheetIndex, props.type, props.columnMap], () => fetch(1))

onMounted(() => {
  // 打开弹层时组件才挂载，所以首次取数放在 mount 上（watch 不会在初次触发）
  if (props.files.length && props.sheetIndex != null) {
    fetch(1)
  }
})
</script>

<style scoped>
.grid-preview { font-size: 12px; }
.gp-bar { display: flex; justify-content: space-between; align-items: center; gap: 12px;
  padding: 6px 8px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fafafa; }
.gp-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.gp-title { font-weight: 600; color: #374151; }
.gp-muted { color: #9ca3af; }
.gp-warn { color: #b45309; }
.gp-ops { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.gp-body { margin-top: 8px; max-height: 460px; overflow: auto; border: 1px solid #e5e7eb; border-radius: 8px; }
.gp-empty { padding: 24px; text-align: center; color: #9ca3af; }
.gp-table { border-collapse: separate; border-spacing: 0; width: max-content; min-width: 100%; font-size: 12px; }
.gp-table th, .gp-table td { border-right: 1px solid #f0f0f0; border-bottom: 1px solid #f0f0f0;
  padding: 4px 8px; text-align: left; white-space: nowrap; }
.gp-corner { position: sticky; left: 0; z-index: 2; background: #f3f4f6; color: #9ca3af; font-weight: 500; }
.gp-table thead th { position: sticky; top: 0; z-index: 1; background: #f9fafb; }
.gp-table thead .gp-corner { z-index: 3; }
.gp-th { min-width: 110px; max-width: 220px; }
.gp-th--off { background: #fff5f5; }
.gp-th-head { font-weight: 600; color: #374151; overflow: hidden; text-overflow: ellipsis; max-width: 210px; }
.gp-th-field { margin-top: 2px; }
.gp-th-tag { display: inline-block; font-size: 11px; color: #4338ca; background: #eef2ff;
  border-radius: 4px; padding: 0 4px; }
.gp-th-tag--off { color: #b91c1c; background: #fee2e2; }
.gp-no { position: sticky; left: 0; background: #fafafa; color: #9ca3af; width: 48px; text-align: right; }
.gp-td { color: #374151; max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
.gp-td--off { color: #d1d5db; background: #fffafa; }
</style>
