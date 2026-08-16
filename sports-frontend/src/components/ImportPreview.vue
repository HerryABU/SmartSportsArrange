<template>
  <el-dialog
    v-model="visible"
    :title="'导入预览 - ' + (previewData?.fileName || '')"
    width="800px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <div v-if="previewData" v-loading="previewing">
      <!-- 文件信息 -->
      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        <template #title>
          文件: {{ previewData.fileName }} | 
          类型: {{ typeLabel }} | 
          大小: {{ formatSize(previewData.fileSize) }} | 
          共 {{ previewData.sheets?.[0]?.totalRows || 0 }} 行
        </template>
      </el-alert>

      <!-- Sheet选择（多Sheet） -->
      <el-tabs v-if="previewData.sheets?.length > 1" v-model="activeSheet">
        <el-tab-pane 
          v-for="(sheet, idx) in previewData.sheets" 
          :key="idx" 
          :label="sheet.name" 
          :name="String(idx)" 
        />
      </el-tabs>

      <template v-if="currentSheet">
        <!-- 列映射 -->
        <div class="mapping-section">
          <h4>列映射配置</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item 
              v-for="(header, idx) in currentSheet.headers" 
              :key="idx"
              :label="'第' + (idx + 1) + '列'"
            >
              <div style="display:flex;align-items:center;gap:8px">
                <el-tag size="small" type="info">{{ header || '(空)' }}</el-tag>
                <el-icon><ArrowRight /></el-icon>
                <el-select 
                  v-model="columnMap[idx]" 
                  size="small" 
                  placeholder="选择字段"
                  clearable
                  style="width:140px"
                >
                  <el-option 
                    v-for="(label, field) in currentSheet.availableFields" 
                    :key="field" 
                    :label="label" 
                    :value="field" 
                  />
                </el-select>
              </div>
            </el-descriptions-item>
          </el-descriptions>
          <div style="margin-top:8px">
            <el-button size="small" @click="autoMap" type="primary" plain>
              <el-icon><MagicStick /></el-icon> 智能匹配
            </el-button>
            <el-button size="small" @click="clearAllMappings" plain>
              <el-icon><Delete /></el-icon> 清除映射
            </el-button>
          </div>
        </div>

        <!-- 数据预览 -->
        <div class="preview-section" style="margin-top:16px">
          <h4>数据预览 (前 {{ currentSheet.previewCount || currentSheet.previewRows?.length || 0 }} 行)</h4>
          <el-table 
            :data="currentSheet.previewRows" 
            border 
            size="small" 
            max-height="300"
            :row-class-name="(_, idx) => idx === 0 ? 'header-row' : ''"
          >
            <el-table-column 
              v-for="(header, idx) in currentSheet.headers" 
              :key="idx"
              :label="'列' + (idx + 1)"
              min-width="100"
            >
              <template #default="{ row }">
                <span :style="{ color: idx === 0 && row[idx] ? '#303133' : '#909399' }">
                  {{ row[idx] || '-' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <el-empty v-else description="暂无预览数据" :image-size="100" />
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="confirmImport" :loading="importing">
        {{ importing ? '导入中...' : '确认导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, MagicStick, Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'

const props = defineProps({
  modelValue: Boolean,
  file: Object,
  type: { type: String, default: 'athlete' }
})

const emit = defineEmits(['update:modelValue', 'imported'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const previewing = ref(false)
const importing = ref(false)
const previewData = ref(null)
const activeSheet = ref('0')
const columnMap = ref({})

const currentSheet = computed(() => {
  if (!previewData.value?.sheets) return null
  const idx = parseInt(activeSheet.value) || 0
  return previewData.value.sheets[idx] || previewData.value.sheets[0]
})

const typeLabel = computed(() => {
  const m = { athlete:'运动员', score:'成绩', registration:'报名', class:'班级', user:'用户', event:'项目' }
  return m[previewData.value?.type] || previewData.value?.type || ''
})

watch(visible, async (val) => {
  if (val && props.file) {
    await doPreview()
  }
})

function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

async function doPreview() {
  previewing.value = true
  columnMap.value = {}
  try {
    const formData = new FormData()
    formData.append('file', props.file)
    const res = await request.post('/excel/preview', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    previewData.value = res
    // 自动填充智能匹配
    autoMap()
  } catch (e) {
    ElMessage.error('预览失败: ' + (e.message || '未知错误'))
    visible.value = false
  } finally {
    previewing.value = false
  }
}

function autoMap() {
  if (!currentSheet.value?.suggestedMappings) return
  columnMap.value = { ...currentSheet.value.suggestedMappings }
}

function clearAllMappings() {
  columnMap.value = {}
}

async function confirmImport() {
  if (!previewData.value) return
  
  // 检查是否有列映射
  const hasMappings = Object.keys(columnMap.value).length > 0
  if (!hasMappings) {
    ElMessage.warning('请先配置列映射或点击"智能匹配"')
    return
  }

  importing.value = true
  try {
    const formData = new FormData()
    formData.append('file', props.file)
    formData.append('type', previewData.value.type || props.type)
    formData.append('sheetIndex', activeSheet.value)
    formData.append('hasHeader', 'true')
    formData.append('columnMap', JSON.stringify(columnMap.value))

    const res = await request.post('/excel/import-with-mapping', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })

    if (res.success > 0) {
      ElMessage.success(`导入完成: 成功 ${res.success} 条${res.failed > 0 ? ', 失败 ' + res.failed + ' 条' : ''}`)
    } else {
      ElMessage.error('导入失败，请检查数据格式')
    }

    if (res.errors?.length) {
      console.warn('导入错误:', res.errors)
    }

    visible.value = false
    emit('imported', res)
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.message || '未知错误'))
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.mapping-section h4,
.preview-section h4 {
  font-size: 14px;
  color: #303133;
  margin-bottom: 12px;
  padding-left: 8px;
  border-left: 3px solid #409EFF;
}

:deep(.header-row) {
  background-color: #f5f7fa;
  font-weight: bold;
}
</style>
