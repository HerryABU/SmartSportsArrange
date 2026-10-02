<template>
  <div class="orderbook-designer">
    <!-- 页面头 -->
    <div class="pg-head">
      <div class="pg-titles">
        <span class="pg-ico"><el-icon :size="20"><Document /></el-icon></span>
        <div>
          <h3 class="pg-title">秩序册设计</h3>
          <p class="pg-desc">
            左边排<b>目录</b>（章节，可加二级、调顺序、启停），右边写<b>细则</b>（手写的介绍文字，
            或挂载系统板块自动取编排数据）。改完下方预览即时跟着变，满意了直接下载 Word 或打印成 PDF。
          </p>
        </div>
      </div>
      <div class="pg-actions">
        <el-select v-model="gradeScope" size="default" style="width:140px" @change="refreshPreview">
          <el-option label="全部年级" value="" />
          <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
        </el-select>
        <el-switch v-model="autoPreview" active-text="改完自动刷新预览" />
        <el-button plain :icon="Refresh" @click="loadLayout(false)">重读目录</el-button>
        <el-button type="primary" :loading="downloading" :icon="Download" @click="doDownload">下载 Word</el-button>
      </div>
    </div>

    <!-- 状态条 -->
    <div class="meta-bar">
      <el-tag v-if="layout.meetName" type="primary" size="small" effect="plain">
        届：{{ layout.meetName }}
        <span v-if="layout.edition">· 第 {{ layout.edition }} 届</span>
      </el-tag>
      <el-tag size="small" effect="plain">目录 {{ sectionCount }} 个 · 启用 {{ layout.enabledSectionCount ?? 0 }} 个</el-tag>
      <el-tag size="small" effect="plain">细则 {{ entryTotal }} 条</el-tag>
      <span class="tip">提示：目录里的「系统板块」条目内容由编排数据自动生成，改动数据后不必重写，预览即 refreshed。</span>
    </div>

    <!-- 目录 + 细则 -->
    <el-row :gutter="12" class="design-row">
      <el-col :span="8">
        <el-card shadow="never" class="pane-card">
          <template #header><div class="card-head"><span>目录结构</span></div></template>
          <OrderBookSectionTree
            :sections="layout.sections || []"
            :meet-id="layout.meetId"
            :selected-id="selectedId"
            @select="onSelect"
            @changed="onStructureChanged"
          />
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never" class="pane-card">
          <template #header>
            <div class="card-head">
              <span>细则内容</span>
              <span v-if="currentSection" class="sub">当前：{{ currentSection.title }}</span>
            </div>
          </template>
          <OrderBookEntryEditor
            :entries="currentEntries"
            :section-id="selectedId"
            :section-title="currentSection ? currentSection.title : ''"
            @changed="onStructureChanged"
          />
        </el-card>
      </el-col>
    </el-row>

    <!-- 在线预览 -->
    <el-card shadow="never" class="pane-card preview-card">
      <template #header>
        <div class="card-head">
          <span>Word 在线预览（与下载的 docx 同一份渲染）</span>
          <div>
            <el-button size="small" :icon="Refresh" :loading="previewing" @click="refreshPreview">刷新预览</el-button>
            <el-button size="small" type="primary" plain :icon="Printer" @click="printPreview">打印 / 存 PDF</el-button>
          </div>
        </div>
      </template>

      <div v-loading="previewing" class="preview-frame">
        <iframe
          v-if="previewHtml"
          ref="previewFrame"
          class="preview-iframe"
          :srcdoc="previewHtml"
          title="秩序册预览"
        />
        <div v-else class="preview-empty">
          还没有预览内容。上面动一动目录或细则，或点「刷新预览」。
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 秩序册设计器页：把「目录树」「细则编辑」「Word 预览」拼在一起。
 *
 * 三者之间只靠一份 layout  communicated：
 *  - layout 由后端 /api/order-book/layout 一次性给出「目录 + 各自挂的细则」；
 *  - 组件内部不另外拉 /entries，避免同一份数据出现两个真相（左树点一下右栏却还是旧数据）；
 *  - 任何写操作成功后统一 emit changed，由本页重拉 layout —— 单一收敛点。
 *
 * 预览用 iframe srcdoc 而不是 src 指向后端：预览接口要鉴权，而 iframe 子请求带不上
 * localStorage 里的 token（实现见 api/orderBook.js 的注释）。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Download, Refresh, Printer } from '@element-plus/icons-vue'
import OrderBookSectionTree from '@/components/orderbook/OrderBookSectionTree.vue'
import OrderBookEntryEditor from '@/components/orderbook/OrderBookEntryEditor.vue'
import { fetchLayout, ensureDefaults, fetchPreviewHtml, downloadOrderBookDocx } from '@/api/orderBook'

/** 和后端 Grades 的 12 年制口径对齐（小学 1-6 / 初中 7-9 / 高中 10-12）。 */
const gradeOptions = ['初一年级', '初二年级', '初三年级', '高一年级', '高二年级', '高三年级']

const layout = ref({ sections: [], meetId: null, meetName: null, edition: null, enabledSectionCount: 0 })
const selectedId = ref(null)
const previewHtml = ref('')
const previewing = ref(false)
const downloading = ref(false)
const gradeScope = ref('')
const autoPreview = ref(true)

const sectionCount = computed(() => (layout.value.sections || []).length)
const entryTotal = computed(() =>
  (layout.value.sections || []).reduce((n, s) => n + ((s.entries || []).length), 0))

const currentSection = computed(() =>
  (layout.value.sections || []).find(s => String(s.id) === String(selectedId.value)) || null)
const currentEntries = computed(() => (currentSection.value && currentSection.value.entries) || [])

let previewTimer = null
const previewFrame = ref(null)

// ==================== 数据 ====================

async function loadLayout (ensure) {
  if (ensure) {
    try {
      await ensureDefaults(layout.value.meetId)
    } catch (e) {
      /* 已经铺过了会直接返回，不用管 */
    }
  }
  const data = await fetchLayout(layout.value.meetId)
  layout.value = data || { sections: [] }
  if (!selectedId.value && (layout.value.sections || []).length) {
    selectedId.value = layout.value.sections[0].id
  }
}

function onSelect (id) {
  selectedId.value = id
}

/** 所有写操作的统一出口：重拉结构 + 可选刷新预览。 */
function onStructureChanged () {
  loadLayout(false).catch(() => {})
  if (autoPreview.value) {
    schedulePreview()
  }
}

function schedulePreview () {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
  previewTimer = setTimeout(refreshPreview, 700)
}

async function refreshPreview () {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
  previewing.value = true
  try {
    previewHtml.value = await fetchPreviewHtml(gradeScope.value || undefined)
  } catch (e) {
    previewHtml.value = ''
    ElMessage.error('预览生成失败，先保存一下目录再试')
  } finally {
    previewing.value = false
  }
}

async function doDownload () {
  downloading.value = true
  try {
    await downloadOrderBookDocx()
    ElMessage.success('秩序册已下载')
  } catch (e) {
    ElMessage.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

function printPreview () {
  const frame = previewFrame.value
  if (!frame || !frame.contentWindow) {
    ElMessage.warning('先等预览加载出来再打印')
    return
  }
  frame.contentWindow.focus()
  frame.contentWindow.print()
}

onMounted(async () => {
  try {
    await loadLayout(false)
    // 一届全新系统里可能一次都没铺过默认章节，这里兜底补一次
    if (!(layout.value.sections || []).length) {
      await loadLayout(true)
    }
  } catch (e) {
    ElMessage.error('目录加载失败')
  }
  refreshPreview().catch(() => {})
})

onBeforeUnmount(() => {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
})
</script>

<style scoped>
.orderbook-designer { display: flex; flex-direction: column; gap: 12px; }

.pg-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  padding: 12px 16px; background: #fff; border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.pg-titles { display: flex; gap: 10px; }
.pg-ico {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 8px;
  background: #eaf1ff; color: #2b5cd9; flex: none;
}
.pg-title { margin: 0 0 2px; font-size: 17px; font-weight: 700; }
.pg-desc { margin: 0; font-size: 13px; color: #6b7686; line-height: 1.6; max-width: 760px; }
.pg-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }

.meta-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 6px 12px; background: #fafbfd; border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px; font-size: 12px; color: #6b7686;
}
.meta-bar .tip { color: #99a2b0; }

.design-row { margin-bottom: -6px; }
.pane-card { border-radius: 10px; }
.card-head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.card-head .sub { font-weight: 400; font-size: 12px; color: #8b95a5; }

.preview-card { border-radius: 10px; }
.preview-frame { height: 620px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; overflow: hidden; background: #fff; }
.preview-iframe { width: 100%; height: 100%; border: 0; display: block; }
.preview-empty {
  display: flex; align-items: center; justify-content: center; height: 100%;
  color: #9aa5b5; font-size: 13px;
}
</style>
