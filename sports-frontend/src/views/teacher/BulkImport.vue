<template>
  <div class="bulk-import-page">
    <!-- 页面头 -->
    <div class="pg-head">
      <div class="pg-titles">
        <span class="pg-ico"><el-icon :size="20"><Files /></el-icon></span>
        <div>
          <h3 class="pg-title">多表导入 · 一个 Excel 多张表</h3>
          <p class="pg-desc">
            支持<b>一个工作簿里的多个 Sheet</b>（如把年级 / 班级 / 全名单 / 报名表拆成独立表），
            也支持<b>一次选多个文件</b>。系统按「Sheet 名 + 表头」自动识别每张表的类型，
            并按依赖顺序导入（年级 → 班级 → 名单 → 项目 → 报名 → 成绩）。
          </p>
        </div>
      </div>
      <div class="pg-actions">
        <el-switch v-model="hasHeader" active-text="首行为表头" style="margin-right:10px" />
        <el-button plain @click="downloadMultiTemplate" :icon="Download">下载多表模板</el-button>
      </div>
    </div>

    <!-- 选文件 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>① 选择文件（可多选 / 可含多个 Sheet）</span>
          <div>
            <el-button size="small" type="primary" plain @click="pickFiles" :icon="Upload">选择文件</el-button>
            <el-button size="small" :disabled="!files.length" @click="clearFiles">清空</el-button>
          </div>
        </div>
      </template>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" multiple style="display:none" @change="onFilesPicked" />
      <div v-if="!files.length" class="empty-tip">
        还没有选择文件。可一次选中多个 .xlsx；单个文件里的所有 Sheet 都会被解析。
      </div>
      <div v-else class="file-list">
        <div v-for="(f, i) in files" :key="i" class="file-item">
          <el-icon><Document /></el-icon>
          <span class="fi-name">{{ f.name }}</span>
          <span class="fi-size">{{ humanSize(f.size) }}</span>
          <el-button link type="danger" size="small" @click="files.splice(i, 1)">移除</el-button>
        </div>
      </div>
      <div class="row-actions">
        <el-button type="primary" :loading="parsing" :disabled="!files.length" @click="doPreview">
          ② 解析表结构
        </el-button>
        <el-button :loading="reparsing" :disabled="!files.length || !hasPreview" @click="doReparse">
          按当前指定重新解析
        </el-button>
        <el-button type="success" :loading="importing" :disabled="!canImport" @click="doImport">
          ③ 开始导入
        </el-button>
        <span v-if="parseSummary" class="hint">{{ parseSummary }}</span>
      </div>
    </el-card>

    <!-- 解析结果 -->
    <el-card v-if="preview" shadow="never">
      <template #header><div class="card-head"><span>表结构（可逐表指定类型后再导入）</span></div></template>
      <div v-for="f in preview.files" :key="f.fileIndex" class="file-block">
        <div class="fb-head"><el-icon><Document /></el-icon> {{ f.fileName }}</div>
        <el-table :data="f.sheets" size="small" border>
          <el-table-column label="Sheet" prop="sheetName" width="150" />
          <el-table-column label="判定类型" width="210">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small" placeholder="未识别" clearable style="width:100%">
                <el-option v-for="t in preview.allTypes" :key="t"
                  :label="`${t} · ${preview.typeLabels[t] || t}`" :value="t" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="识别依据" width="92">
            <template #default="{ row }">
              <el-tag v-if="row.resolvedBy === 'override'" size="small" type="warning">人工指定</el-tag>
              <el-tag v-else-if="row.resolvedBy === 'header'" size="small" type="success">表头指纹</el-tag>
              <el-tag v-else-if="row.resolvedBy === 'name'" size="small" type="info">Sheet 名</el-tag>
              <el-tag v-else size="small" type="danger">未识别</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="表头 → 字段" min-width="300">
            <template #default="{ row }">
              <span v-for="m in row.mappedFields" :key="m.column" class="chip-map">
                {{ m.header }} → {{ m.label }}
              </span>
              <span v-if="!row.mappedFields || !row.mappedFields.length" class="muted">（无可用列）</span>
              <div v-if="row.unmappedHeaders && row.unmappedHeaders.length" class="muted">
                未识别列：{{ row.unmappedHeaders.join('、') }}
              </div>
              <div class="col-ops">
                <el-button link type="primary" size="small" @click="openMapping(row)">逐列编辑映射</el-button>
                <el-button link type="info" size="small" @click="openGrid(f.fileIndex, row)">可视化预览</el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="数据行" width="80">
            <template #default="{ row }">{{ row.totalRows }}</template>
          </el-table-column>
          <el-table-column label="导入该表" width="92">
            <template #default="{ row }">
              <el-switch v-model="row.include" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="可导入" width="80">
            <template #default="{ row }">
              <el-tag :type="row.importable ? 'success' : 'danger'" size="small">
                {{ row.importable ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              <span v-if="row.reason" class="danger-text">{{ row.reason }}</span>
              <span v-else class="muted">数据行 {{ row.totalRows }} 行将导入</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 逐列映射编辑 -->
    <el-dialog v-model="mappingVisible" :title="mappingTitle" width="880px" append-to-body>
      <SheetMappingEditor
        v-if="mappingRow && mappingRow.advice"
        :columns="mappingRow.advice"
        @apply="onMappingApplied"
      />
      <template #footer>
        <el-button @click="mappingVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 可视化 Excel 预览 -->
    <el-dialog v-model="gridVisible" :title="gridTitle" width="min(1200px, 94vw)" append-to-body>
      <ExcelGridPreview
        v-if="gridRow"
        ref="gridRef"
        :files="files"
        :file-index="gridRow.fileIndex"
        :sheet-index="gridRow.sheetIndex"
        :has-header="hasHeader"
        :type="gridRow.type || null"
        :column-map="gridRow.columnMap || {}"
      />
    </el-dialog>

    <!-- 导入报告 -->
    <el-card v-if="report" shadow="never">
      <template #header>
        <div class="card-head">
          <span>导入结果</span>
          <div class="summary-chips">
            <el-tag type="success" size="small">成功 {{ report.summary.imported }} 行</el-tag>
            <el-tag type="warning" size="small">跳过(已存在/重复) {{ report.summary.rowSkipped }} 行</el-tag>
            <el-tag v-if="report.summary.duplicateRows" type="info" size="small">
              重复 {{ report.summary.duplicateRows }} 行
            </el-tag>
            <el-tag v-if="report.summary.conflictRows" type="warning" size="small">
              同键冲突 {{ report.summary.conflictRows }} 行
            </el-tag>
            <el-tag v-if="report.summary.rosterMismatches" type="danger" size="small">
              名单不一致 {{ report.summary.rosterMismatches }}
            </el-tag>
            <el-tag :type="report.summary.failed ? 'danger' : 'info'" size="small">
              失败 {{ report.summary.failed }} 行
            </el-tag>
            <el-tag type="info" size="small">跳过表 {{ report.summary.skippedSheets }} 张</el-tag>
          </div>
        </div>
      </template>

      <el-alert v-for="(n, i) in report.notes" :key="'n' + i" :title="n" type="warning" show-icon
        :closable="false" style="margin-bottom:6px" />

      <div v-for="f in report.files" :key="f.fileIndex" class="file-block">
        <div class="fb-head"><el-icon><Document /></el-icon> {{ f.fileName }}</div>
        <el-table :data="f.sheets" size="small" border>
          <el-table-column label="Sheet" prop="sheetName" width="140" />
          <el-table-column label="类型" prop="type" width="110">
            <template #default="{ row }">{{ row.type || '—' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="200">
            <template #default="{ row }">
              <template v-if="row.skipped">
                <el-tag type="info" size="small">整表跳过</el-tag>
              </template>
              <template v-else>
                <el-tag type="success" size="small">成功 {{ row.success }}</el-tag>
                <el-tag v-if="row.rowSkipped" type="warning" size="small">跳过 {{ row.rowSkipped }}</el-tag>
                <el-tag v-if="row.failed" type="danger" size="small">失败 {{ row.failed }}</el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="明细" min-width="320">
            <template #default="{ row }">
              <div v-if="row.reason" class="danger-text">{{ row.reason }}</div>
              <div v-for="(e, ei) in (row.errors || [])" :key="'e' + ei" class="err-line">
                第 {{ e.row }} 行：{{ e.message }}
              </div>
              <div v-for="(c, ci) in (row.inconsistencies || [])" :key="'c' + ci" class="warn-line">
                第 {{ c.row }} 行 [{{ kindLabel(c.kind) }}]：{{ c.message }}
              </div>
              <div v-for="(s, si) in (row.skipNotes || [])" :key="'s' + si" class="muted">
                {{ s }}
              </div>
              <span v-if="!row.failed && !row.skipped && !(row.skipNotes || []).length && !row.reason && !(row.inconsistencies || []).length"
                class="muted">
                无异常
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="hint" style="margin-top:8px">
        提示：「跳过(已存在)」= 该行数据已在系统中（含<b>文件内重复行</b>与<b>同键冲突行</b>），不计为失败 —— 同一份工作簿可放心重跑。
        「[重复]」同一实体出现多次只导一次；「[冲突]」同一实体字段不一致（如同学号不同姓名），已按首次出现为准；
        「[名单不一致]」报名信息与名单不符，仅提示、不阻止导入。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Files, Upload, Download, Document, Setting, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { downloadApi } from '@/utils/download'
import SheetMappingEditor from '@/components/excel/SheetMappingEditor.vue'
import ExcelGridPreview from '@/components/excel/ExcelGridPreview.vue'

const fileInput = ref(null)
const files = ref([])
const hasHeader = ref(true)
const preview = ref(null)
const report = ref(null)
const parsing = ref(false)
const importing = ref(false)
const reparsing = ref(false)

// 去重 / 一致性问题的中文标签
const KIND_LABELS = { DUPLICATE: '重复', CONFLICT: '冲突', ROSTER_MISMATCH: '名单不一致' }
function kindLabel (k) { return KIND_LABELS[k] || k }

const hasPreview = computed(() => !!(preview.value && preview.value.files && preview.value.files.length))

const canImport = computed(() => {
  if (!files.value.length) return false
  if (!preview.value) return true // 未解析也允许直接导入（走后端自动识别）
  // 至少有一张表被勾上「导入该表」，且它认得出类型
  return preview.value.files.some(f => (f.sheets || []).some(s => s.include !== false && s.type))
})

const parseSummary = computed(() => {
  if (!preview.value) return ''
  let sheets = 0
  let importable = 0
  // 说明页（填写说明）不算「已识别的可导入表」：它本来就没有业务数据，计入会让顶部统计虚高
  preview.value.files.forEach(f => (f.sheets || []).forEach(s => { sheets++; if (s.type && s.type !== 'notice') importable++ }))
  return `共 ${preview.value.files.length} 个文件 / ${sheets} 张表，其中 ${importable} 张已识别类型`
})

function pickFiles () {
  fileInput.value && fileInput.value.click()
}

function onFilesPicked (e) {
  const picked = Array.from(e.target.files || [])
  picked.forEach(f => {
    if (!files.value.some(x => x.name === f.name && x.size === f.size)) files.value.push(f)
  })
  preview.value = null
  report.value = null
  e.target.value = '' // 允许再次选择同一文件
}

function clearFiles () {
  files.value = []
  preview.value = null
  report.value = null
}

function humanSize (n) {
  if (!n && n !== 0) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

/**
 * 组装表单：多文件 + 是否含表头 + 逐表「导入计划」。
 *
 * 计划里现在带三样东西：类型 {@code type}、列映射 {@code columnMap}、是否参与 {@code include}。
 * 这正是「按用户指定重新解析」的载体 —— 浏览器里仍持有同一个 File 对象，
 * 所以改完映射不用重新选文件，把计划打回后端即可重来一遍。
 */
function buildFormData (withPlan) {
  const fd = new FormData()
  files.value.forEach(f => fd.append('files', f))
  fd.append('hasHeader', String(hasHeader.value))
  if (withPlan && preview.value) {
    const plan = []
    preview.value.files.forEach(f => {
      (f.sheets || []).forEach(s => {
        const entry = {
          file: f.fileName,
          sheet: s.sheetName,
          type: s.type || null,
          include: s.include !== false
        }
        // 传生效后的列映射（后端 s.columnMap 已是「人工优先、否则自动」）
        const cm = s.columnMap
        if (cm && Object.keys(cm).length) entry.columnMap = cm
        plan.push(entry)
      })
    })
    if (plan.length) fd.append('sheets', JSON.stringify(plan))
  }
  return fd
}

/**
 * 按用户指定重新解析：把当前预览状态（类型 / 列映射 / 是否导入）回传，
 * 后端据此重新判定并再出一遍预览，同时带上逐列候选字段供继续改。
 */
async function doReparse () {
  if (!files.value.length) { ElMessage.warning('请先选择文件'); return }
  reparsing.value = true
  try {
    const res = await request.post('/excel/multi/reparse', buildFormData(true),
      { headers: { 'Content-Type': 'multipart/form-data' } })
    preview.value = res || null
    report.value = null
    ElMessage.success('已按你的指定重新解析，可继续逐列调整映射')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '重新解析失败')
  } finally {
    reparsing.value = false
  }
}

const mappingVisible = ref(false)
const mappingRow = ref(null)
const mappingTitle = computed(() => {
  const s = mappingRow.value
  if (!s) return '列映射'
  // 注意：<script setup> 里 ref 不会自动解包，必须写 preview.value。
  // 曾经漏写成 preview.typeLabels[...]，点开「逐列编辑映射」时该 computed 在渲染期抛
  // TypeError（Cannot read properties of undefined (reading '<type>')）→ 整页白框，
  // 表现为「逐列编辑映射不可用」。
  const labels = preview.value?.typeLabels || {}
  return `列映射 · ${s.sheetName}（${labels[s.type] || s.type || '未识别'}）`
})

function openMapping (row) {
  if (!row.advice || !row.advice.length) {
    ElMessage.info('这张表没有可识别的表头，先点「按当前指定重新解析」拿到字段清单')
    return
  }
  // 后端建议是「这一列最可能是什么」，先把用户当前（可能是自动）的落点回填成选中项
  const advice = row.advice.map(a => ({ ...a, field: a.field || '' }))
  ;(row.columnMap || {}) && Object.keys(row.columnMap).forEach(k => {
    const c = advice.find(a => String(a.column) === String(k))
    if (c) c.field = row.columnMap[k]
  })
  mappingRow.value = { ...row, advice, columnMap: row.columnMap || {} }
  mappingVisible.value = true
}

// ---- 可视化预览 ----
const gridVisible = ref(false)
const gridRow = ref(null)
const gridRef = ref(null)
const gridTitle = computed(() => {
  const r = gridRow.value
  return r ? `可视化预览 · ${r.sheetName}` : '可视化预览'
})

function openGrid (fileIndex, row) {
  gridRow.value = {
    fileIndex,
    sheetIndex: row.sheetIndex,
    sheetName: row.sheetName,
    type: row.type,
    columnMap: row.columnMap || {}
  }
  gridVisible.value = true
}

/** 映射编辑器点「应用映射」：写回本地计划，并立刻按指定重新解析一次（所见即所得）。 */
function onMappingApplied (columnMap) {
  if (mappingRow.value) {
    mappingRow.value.columnMap = columnMap
    // 把改动同步回列表里的那一行（dialog 里是副本）
    const all = (preview.value?.files || []).flatMap(f => f.sheets || [])
    const target = all.find(s => s.sheetIndex === mappingRow.value.sheetIndex && s.sheetName === mappingRow.value.sheetName)
    if (target) target.columnMap = columnMap
  }
  mappingVisible.value = false
  doReparse()
}

async function doPreview () {
  if (!files.value.length) { ElMessage.warning('请先选择文件'); return }
  parsing.value = true
  try {
    const res = await request.post('/excel/multi/preview', buildFormData(false),
      { headers: { 'Content-Type': 'multipart/form-data' } })
    preview.value = res || null
    report.value = null
    if (!preview.value || !preview.value.files || !preview.value.files.length) {
      ElMessage.warning('没有解析到任何 Sheet')
    } else {
      ElMessage.success('解析完成，请确认每张表的类型')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '解析失败')
  } finally {
    parsing.value = false
  }
}

async function doImport () {
  if (!files.value.length) { ElMessage.warning('请先选择文件'); return }
  importing.value = true
  try {
    const res = await request.post('/excel/import-multi', buildFormData(true),
      { headers: { 'Content-Type': 'multipart/form-data' } })
    report.value = res || null
    const s = report.value && report.value.summary
    if (s && s.failed) {
      ElMessage.warning(`导入完成：成功 ${s.imported} 行，失败 ${s.failed} 行，请查看明细`)
    } else if (s) {
      const extra = []
      if (s.duplicateRows) extra.push(`重复 ${s.duplicateRows}`)
      if (s.conflictRows) extra.push(`同键冲突 ${s.conflictRows}`)
      if (s.rosterMismatches) extra.push(`名单不一致 ${s.rosterMismatches}`)
      ElMessage.success(
        `导入完成：成功 ${s.imported} 行，跳过(已存在/重复) ${s.rowSkipped} 行` +
        (extra.length ? `，${extra.join('，')}` : '')
      )
    } else {
      ElMessage.success('导入完成')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '导入失败')
  } finally {
    importing.value = false
  }
}

async function downloadMultiTemplate () {
  try {
    await downloadApi('/excel/template/multiworkbook', '多表导入模板.xlsx')
    ElMessage.success('已下载多表模板（含年级表/班级表/全名单表/运动项目表/报名表/成绩表）')
  } catch (e) {
    ElMessage.error(e?.message || '模板下载失败')
  }
}
</script>

<style scoped>
.bulk-import-page { display: flex; flex-direction: column; gap: 14px; }
.pg-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.pg-titles { display: flex; gap: 10px; align-items: flex-start; }
.pg-ico { display: inline-flex; padding: 8px; border-radius: 10px; background: #ecfdf5; color: #059669; }
.pg-title { margin: 0; font-size: 16px; }
.pg-desc { margin: 4px 0 0; font-size: 12px; color: #6b7280; max-width: 900px; line-height: 1.7; }
.pg-actions { display: flex; align-items: center; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.empty-tip { color: #9ca3af; font-size: 13px; padding: 6px 2px; }
.file-list { display: flex; flex-direction: column; gap: 4px; }
.file-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 4px 6px;
  border: 1px solid #e5e7eb; border-radius: 8px; background: #fafafa; }
.fi-name { flex: 1; }
.fi-size { color: #9ca3af; font-size: 12px; }
.row-actions { margin-top: 12px; display: flex; align-items: center; gap: 8px; }
.file-block { margin-bottom: 14px; }
.fb-head { font-size: 13px; font-weight: 600; margin-bottom: 6px; display: flex; align-items: center; gap: 6px; }
  .chip-map { display: inline-block; font-size: 11.5px; background: #eef2ff; color: #4338ca;
  border-radius: 6px; padding: 1px 6px; margin: 1px 4px 1px 0; }
  .col-ops { margin-top: 4px; display: flex; gap: 2px; }
.muted { color: #9ca3af; font-size: 12px; }
.danger-text { color: #b91c1c; font-size: 12px; }
.err-line { color: #b91c1c; font-size: 12px; }
.warn-line { color: #b45309; font-size: 12px; }
.summary-chips { display: flex; gap: 6px; }
.hint { font-size: 12px; color: #9ca3af; }
</style>
