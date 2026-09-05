<template>
  <div class="events-container">
    <!-- 页面标题（表格2 项目字典 · 工作流①导入） -->
    <div class="pg-head rise-in" style="margin-bottom:14px">
      <div class="pg-titles">
        <span class="pg-ico">🏆</span>
        <div>
          <h3 class="pg-title">比赛项目（表格2）</h3>
          <p class="pg-desc">A代码 | B项目 | C是否田径 | D道次（田赛=0）… 报名表 F 列通过「代码/名称」软链本表；径赛可开预赛淘汰并设置晋级人数</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#eff6ff;color:#2563eb">① 导入报名</span>
        <el-button plain @click="downloadTemplate" :icon="Download">下载表格2模板</el-button>
        <el-button plain @click="handleExport" :icon="Download">导出</el-button>
      </div>
    </div>

    <!-- 操作栏 -->
     <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="openTemplateDialog">
          <el-icon><DocumentCopy /></el-icon>
          预设模板
        </el-button>
        <el-button type="success" @click="openAddDialog">
          <el-icon><Plus /></el-icon>
          新增项目
        </el-button>
        <el-button type="success" plain @click="openBatchAdd">
          <el-icon><Plus /></el-icon>
          批量新增
        </el-button>
        <el-upload
          :action="importUrl"
          :headers="uploadHeaders"
          :show-file-list="false"
          accept=".xlsx,.xls,.csv"
          :on-success="onImportSuccess"
          :on-error="onImportError"
          style="display:inline-block;margin-left:8px"
        >
          <el-button type="warning"><el-icon><Upload /></el-icon> 导入Excel/CSV</el-button>
        </el-upload>
      </div>
      <div class="toolbar-right">
        <el-select
          v-model="filters.grade"
          placeholder="年级筛选"
          clearable
          filterable
          style="width: 130px"
          @change="handleFilterChange"
        >
          <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
        </el-select>
        <el-select
          v-model="filters.gender"
          placeholder="性别筛选"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option label="男子组" value="男子组" />
          <el-option label="女子组" value="女子组" />
          <el-option label="混合组" value="混合组" />
        </el-select>
        <el-select
          v-model="filters.eventType"
          placeholder="项目类型"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option label="径赛" value="径赛" />
          <el-option label="田赛" value="田赛" />
        </el-select>
      </div>
    </div>

    <!-- 批量操作条（勾选后出现） -->
    <transition name="el-fade-in">
      <div v-if="multipleSelection.length" class="batch-bar">
        <el-icon class="bb-ico"><CircleCheck /></el-icon>
        <span class="bb-info">已选 <b>{{ multipleSelection.length }}</b> 个项目</span>
        <el-button size="small" type="primary" @click="openBatchEdit">批量修改</el-button>
        <el-button size="small" type="success" plain @click="batchSetStatus(true)">批量启用</el-button>
        <el-button size="small" type="warning" plain @click="batchSetStatus(false)">批量禁用</el-button>
        <el-button size="small" type="danger" plain @click="confirmBatchDelete">批量删除</el-button>
        <el-button size="small" link type="info" @click="clearSelection">取消选择</el-button>
      </div>
    </transition>

    <!-- 数据表格 -->
    <el-table
      v-loading="loading"
      :data="tableData"
      border
      stripe
      row-key="id"
      ref="tableRef"
      style="width: 100%"
      :header-cell-style="{ background: '#f5f7fa', color: '#303133' }"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="46" align="center" />
      <el-table-column prop="name" label="项目名称" min-width="150" />
      <el-table-column prop="eventType" label="项目类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.eventType === '径赛' ? 'danger' : 'warning'" effect="light">
            {{ row.eventType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gender" label="性别" width="100" align="center" />
      <el-table-column prop="gradeGroup" label="年级组" width="120" align="center" />
      <el-table-column prop="maxParticipants" label="最大报名人数" width="120" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="禁用"
            inline-prompt
            @change="(val: boolean) => handleToggleStatus(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEditDialog(row)">
            编辑
          </el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 预设模板对话框 -->
    <el-dialog
      v-model="templateDialogVisible"
      title="预设模板"
      width="700px"
      :close-on-click-modal="false"
    >
      <el-tabs v-model="templateCategory" type="border-card">
        <el-tab-pane label="跑步类" name="跑步类">
          <div class="template-list">
            <div
              v-for="tpl in runningTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="跳跃类" name="跳跃类">
          <div class="template-list">
            <div
              v-for="tpl in jumpingTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="投掷类" name="投掷类">
          <div class="template-list">
            <div
              v-for="tpl in throwingTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="接力类" name="接力类">
          <div class="template-list">
            <div
              v-for="tpl in relayTemplates"
              :key="tpl.name"
              class="template-item"
              @click="selectTemplate(tpl)"
            >
              <span class="template-name">{{ tpl.name }}</span>
              <span class="template-info">{{ tpl.gender }} · {{ tpl.eventType }}</span>
              <el-icon :size="18" color="#67c23a"><CircleCheck /></el-icon>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="templateDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="isEdit ? '编辑项目' : '新增项目'"
      width="550px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="110px"
      >
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入项目名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="项目类型" prop="eventType">
          <el-select v-model="formData.eventType" placeholder="请选择项目类型" style="width: 100%"
                     @change="onEventTypeChange">
            <el-option label="径赛（田径/竞速，占道次）" value="径赛" />
            <el-option label="田赛（跳跃/投掷，不占道次）" value="田赛" />
          </el-select>
        </el-form-item>
        <el-form-item label="性别" prop="gender">
          <el-select v-model="formData.gender" placeholder="请选择性别组" style="width: 100%">
            <el-option label="男子组" value="男子组" />
            <el-option label="女子组" value="女子组" />
            <el-option label="混合组" value="混合组" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目编码" prop="code">
          <el-input v-model="formData.code" placeholder="如 100M / TY_F（报名表 F 列可填）" maxlength="20" />
        </el-form-item>
        <el-form-item label="道次" prop="laneCount">
          <el-input-number
            v-model="formData.laneCount"
            :min="0"
            :max="12"
            :disabled="formData.eventType === '田赛'"
            placeholder="径赛填道次，田赛为 0"
            style="width: 100%"
          />
          <div class="form-tip" v-if="formData.eventType === '田赛'">田赛不占道次，固定为 0</div>
          <div class="form-tip" v-else>径赛跑道数（默认 8）</div>
        </el-form-item>
        <template v-if="formData.eventType === '径赛'">
          <el-form-item label="预赛淘汰">
            <el-switch v-model="formData.needHeats" inline-prompt active-text="需要预赛淘汰"
              inactive-text="直接决赛" />
            <div class="form-tip">需要预赛的项目：编排页先「生成预赛 → 录预赛成绩 → 立即计算晋级」，系统自动排出决赛</div>
          </el-form-item>
          <el-form-item label="晋级人数" v-if="formData.needHeats">
            <el-input-number v-model="formData.advanceCount" :min="1" :max="99" style="width: 100%" />
            <div class="form-tip">预赛结束后全场取前 N 名晋级决赛</div>
          </el-form-item>
          <el-form-item label="每组上限">
            <el-input-number v-model="formData.maxPerHeat" :min="1" :max="12" style="width: 100%" />
            <div class="form-tip">单组最多人数（一般等于道次数）</div>
          </el-form-item>
        </template>
        <el-form-item label="团体每队人数" prop="teamSize">
          <el-input-number
            v-model="formData.teamSize"
            :min="0"
            :max="99"
            placeholder="0 = 非团体赛"
            style="width: 100%"
          />
          <div class="form-tip">接力等团体项目填写每队人数（4×100 → 4）；0 表示个人项目</div>
        </el-form-item>
        <el-form-item label="调度模式" prop="scheduleMode">
          <el-select v-model="formData.scheduleMode" style="width: 100%">
            <el-option label="串行（独占场地依次进行）" value="serial" />
            <el-option label="并行（多场地同时开赛）" value="parallel" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认场地" prop="defaultVenue">
          <el-input v-model="formData.defaultVenue" placeholder="如 田径场 / 田赛A区" maxlength="50" />
        </el-form-item>
        <el-form-item label="最大用时/间隔">
          <div style="display:flex;gap:8px;width:100%">
            <el-input-number v-model="formData.maxDurationMinutes" :min="1" :max="600" placeholder="最大用时(分)" style="flex:1" />
            <el-input-number v-model="formData.intervalMinutes" :min="0" :max="120" placeholder="间隔(分)" style="flex:1" />
          </div>
        </el-form-item>
        <el-form-item label="年级组" prop="gradeGroup">
          <el-select v-model="formData.gradeGroup" placeholder="请选择年级组" style="width: 100%" filterable>
            <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大报名人数" prop="maxParticipants">
          <el-input-number
            v-model="formData.maxParticipants"
            :min="1"
            :max="999"
            placeholder="请输入最大报名人数"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="排序号" prop="sortOrder">
          <el-input-number
            v-model="formData.sortOrder"
            :min="0"
            :max="9999"
            placeholder="数字越小越靠前"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入项目描述（选填）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 批量新增（公共字段 + 每行一个项目名[可选,编码]） ===== -->
    <el-dialog v-model="batchAddVisible" title="批量新增比赛项目" width="760px" :close-on-click-modal="false"
      top="5vh" @closed="resetBatchAdd">
      <el-alert type="info" show-icon :closable="false" style="margin-bottom:12px"
        title="下方先填好公共属性，再在文本区每行输入一个项目（支持「项目名」或「项目名,编码」），实时预览后可一次创建。单条失败不影响其它项目。" />
      <div class="batch-common">
        <el-form label-width="96px" label-position="top" class="bc-grid">
          <el-form-item label="项目类型" required>
            <el-radio-group v-model="batchAddForm.eventType" size="default">
              <el-radio-button value="径赛">径赛</el-radio-button>
              <el-radio-button value="田赛">田赛</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="性别组" required>
            <el-select v-model="batchAddForm.gender" style="width: 100%">
              <el-option label="男子组" value="男子组" />
              <el-option label="女子组" value="女子组" />
              <el-option label="混合组" value="混合组" />
            </el-select>
          </el-form-item>
          <el-form-item label="年级组">
            <el-select v-model="batchAddForm.gradeGroup" placeholder="选择年级组" clearable filterable style="width: 100%">
              <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
          <el-form-item label="团体人数">
            <el-input-number v-model="batchAddForm.teamSize" :min="0" :max="99" style="width: 100%" />
            <div class="form-tip">0 = 个人项目；&gt;0（如接力4）为团体</div>
          </el-form-item>
          <el-form-item v-if="batchAddForm.eventType === '径赛'" label="预赛淘汰">
            <el-switch v-model="batchAddForm.needHeats" inline-prompt active-text="预赛→决赛"
              inactive-text="直接决赛" />
            <template v-if="batchAddForm.needHeats">
              <div class="form-tip" style="margin-top:4px">晋级人数
                <el-input-number v-model="batchAddForm.advanceCount" :min="1" :max="99" size="small" style="width:120px" />
              </div>
            </template>
          </el-form-item>
          <el-form-item label="最大报名人数">
            <el-input-number v-model="batchAddForm.maxParticipants" :min="1" :max="999" style="width: 100%" />
          </el-form-item>
          <el-form-item label="默认场地">
            <el-input v-model="batchAddForm.defaultVenue" placeholder="如 田径场（选填）" maxlength="50" style="width:100%" />
          </el-form-item>
        </el-form>
      </div>

      <div class="batch-names-head">
        <span class="batch-label2">项目名称列表（一行一个）</span>
        <span class="batch-hint">支持「项目名,编码」两段，如：<code>100米,100M</code></span>
      </div>
      <el-input v-model="batchAddNames" type="textarea" :rows="6" class="batch-names"
        placeholder="100米,100M&#10;200米,200M&#10;4×100米接力,4X100M" />
      <div v-if="batchItems.length" class="batch-preview">
        <span class="bp-title">预览（共 {{ batchItems.length }} 项，将按上方公共属性创建）：</span>
        <div class="bp-tiles">
          <el-tag v-for="(it, i) in batchItems" :key="i" closable size="large" effect="plain"
            @close="removePreviewItem(i)">
            <b>{{ it.name }}</b><span v-if="it.code" class="bp-code">{{ it.code }}</span>
          </el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="batchAddVisible = false">取消</el-button>
        <el-button :disabled="!batchItems.length" @click="batchAddNames = ''">清空名称</el-button>
        <el-button type="primary" :loading="batchAddSubmitting" :disabled="!batchItems.length"
          @click="submitBatchAdd">
          创建 {{ batchItems.length }} 个项目
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 批量修改（留空 = 不修改） ===== -->
    <el-dialog v-model="batchEditVisible" title="批量修改比赛项目" width="620px" :close-on-click-modal="false"
      @closed="resetBatchEdit">
      <el-alert type="warning" show-icon :closable="false" style="margin-bottom:12px"
        title="以下字段留空表示「不修改」该属性；已选项目将全部应用填写项。" />
      <div class="be-names">
        已选：<el-tag v-for="r in multipleSelection" :key="r.id" size="small" effect="plain" style="margin:2px">{{ r.name }}</el-tag>
      </div>
      <el-form label-width="100px" style="margin-top:10px">
        <el-form-item label="项目类型">
          <el-select v-model="batchPatch.eventType" placeholder="不修改" clearable style="width: 100%">
            <el-option label="径赛（田径/竞速）" value="径赛" />
            <el-option label="田赛（跳跃/投掷）" value="田赛" />
          </el-select>
          <div class="form-tip">改为田赛会自动置道次 0 并关闭预赛；改为径赛保持原预赛设置</div>
        </el-form-item>
        <el-form-item label="性别组">
          <el-select v-model="batchPatch.gender" placeholder="不修改" clearable style="width: 100%">
            <el-option label="男子组" value="男子组" />
            <el-option label="女子组" value="女子组" />
            <el-option label="混合组" value="混合组" />
          </el-select>
        </el-form-item>
        <el-form-item label="年级组">
          <el-select v-model="batchPatch.gradeGroup" placeholder="不修改" clearable filterable style="width: 100%">
            <el-option v-for="g in gradeOptions" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item label="道次">
          <el-input-number v-model="batchPatch.laneCount" :min="0" :max="12" :clearable="true"
            value-on-clear="null" placeholder="不修改" style="width: 100%" />
          <div class="form-tip">田赛填 0；径赛为跑道数（默认 8）</div>
        </el-form-item>
        <el-form-item label="团体人数">
          <el-input-number v-model="batchPatch.teamSize" :min="0" :max="99" :clearable="true"
            value-on-clear="null" placeholder="0=个人" style="width: 100%" />
        </el-form-item>
        <el-form-item label="调度模式">
          <el-select v-model="batchPatch.scheduleMode" placeholder="不修改" clearable style="width: 100%">
            <el-option label="串行（依次进行）" value="serial" />
            <el-option label="并行（多场地同时）" value="parallel" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认场地">
          <el-input v-model="batchPatch.defaultVenue" placeholder="不修改（留空）" maxlength="50" style="width:100%" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-select v-model="batchPatch.enabled" placeholder="不修改" clearable style="width: 100%">
            <el-option label="启用" :value="true" />
            <el-option label="禁用" :value="false" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchEditSubmitting" @click="submitBatchEdit">
          应用到 {{ multipleSelection.length }} 个项目
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy, Plus, CircleCheck, Upload, Download } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'

// ==================== 类型定义 ====================
interface EventItem {
  id?: number
  name: string
  eventType: string
  gender: string
  gradeGroup: string
  maxParticipants: number
  description: string
  sortOrder: number
  enabled: boolean
  // 表格2 / 调度字段
  code?: string
  isTrack?: boolean
  laneCount?: number
  isTeam?: boolean
  teamSize?: number
  scheduleMode?: string
  defaultVenue?: string
  maxDurationMinutes?: number
  intervalMinutes?: number
  // 预赛淘汰字段（径赛 needHeats=true 时先预赛后晋级决赛）
  needHeats?: boolean
  advanceCount?: number
  maxPerHeat?: number
}

interface TemplateItem {
  name: string
  eventType: string
  gender: string
}

// ==================== 预设模板数据 ====================
const runningTemplates: TemplateItem[] = [
  { name: '100米', eventType: '径赛', gender: '男子组' },
  { name: '100米', eventType: '径赛', gender: '女子组' },
  { name: '200米', eventType: '径赛', gender: '男子组' },
  { name: '200米', eventType: '径赛', gender: '女子组' },
  { name: '400米', eventType: '径赛', gender: '男子组' },
  { name: '400米', eventType: '径赛', gender: '女子组' },
  { name: '800米', eventType: '径赛', gender: '男子组' },
  { name: '800米', eventType: '径赛', gender: '女子组' },
  { name: '1500米', eventType: '径赛', gender: '男子组' },
  { name: '1500米', eventType: '径赛', gender: '女子组' },
]

const jumpingTemplates: TemplateItem[] = [
  { name: '跳高', eventType: '田赛', gender: '男子组' },
  { name: '跳高', eventType: '田赛', gender: '女子组' },
  { name: '跳远', eventType: '田赛', gender: '男子组' },
  { name: '跳远', eventType: '田赛', gender: '女子组' },
]

const throwingTemplates: TemplateItem[] = [
  { name: '铅球', eventType: '田赛', gender: '男子组' },
  { name: '铅球', eventType: '田赛', gender: '女子组' },
  { name: '实心球', eventType: '田赛', gender: '男子组' },
  { name: '实心球', eventType: '田赛', gender: '女子组' },
]

const relayTemplates: TemplateItem[] = [
  { name: '4×100米接力', eventType: '径赛', gender: '男子组' },
  { name: '4×100米接力', eventType: '径赛', gender: '女子组' },
  { name: '4×100米接力', eventType: '径赛', gender: '混合组' },
]

// ==================== 状态 ====================
const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<EventItem[]>([])
const formRef = ref<FormInstance>()
// 年级下拉：动态来自 系统设置·年级管理（不硬编码）
const gradeOptions = ref<string[]>([])

const filters = reactive({
  grade: '',
  gender: '',
  eventType: '',
})

const pagination = reactive({
  page: 1,
  size: 10,
  total: 0,
})

const isEdit = ref(false)
const editingId = ref<number | null>(null)

const formDialogVisible = ref(false)
const templateDialogVisible = ref(false)
const templateCategory = ref('跑步类')
const formData = reactive<EventItem>({
  name: '',
  eventType: '',
  gender: '',
  gradeGroup: '',
  maxParticipants: 1,
  description: '',
  sortOrder: 0,
  enabled: true,
  code: '',
  isTrack: true,
  laneCount: 8,
  isTeam: false,
  teamSize: 0,
  scheduleMode: 'serial',
  defaultVenue: '',
  maxDurationMinutes: undefined,
  intervalMinutes: undefined,
  needHeats: true,
  advanceCount: 8,
  maxPerHeat: 8,
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  eventType: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  gender: [{ required: true, message: '请选择性别组', trigger: 'change' }],
  gradeGroup: [{ required: true, message: '请选择年级组', trigger: 'change' }],
  maxParticipants: [{ required: true, message: '请输入最大报名人数', trigger: 'blur' }],
}

// ==================== 导入/导出 ====================
const token = localStorage.getItem('token') || ''
const importUrl = apiBase() + '/events/import'
const uploadHeaders = computed(() => ({ Authorization: token ? `Bearer ${token}` : '' }))

function onImportSuccess(res: any) {
  ElMessage.success(`导入完成：成功 ${res?.success || res?.data?.success || 0} 条`)
  fetchData()
}
function onImportError() { ElMessage.error('导入失败，请检查文件格式') }

function downloadTemplate() {
  // 表格2 布局：A代码 / B项目 / C是否田径 / D道次（田赛=0）→ 与「导出」及 /events/import 解析一致
  const csv =
    '代码,项目,是否田径(是/否),道次(田赛写0),性别,年级组,是否团体(是/否),团体人数,调度模式(serial/parallel),场地,最大用时(分),间隔(分)\n' +
    '100M,100米,是,8,男子组,高一年级,否,0,serial,田径场,20,10\n' +
    '100F,100米(女子),是,8,女子组,高一年级,否,0,serial,田径场,20,10\n' +
    '4X100M,4×100米接力,是,8,男子组,高一年级,是,4,serial,田径场,30,15\n' +
    'TY_F,跳远(女子),否,0,女子组,高一年级,否,0,parallel,田赛A区,90,10\n'
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = '项目表模板_表格2.csv'; a.click()
  URL.revokeObjectURL(url)
}

async function handleExport() {
  try {
    await downloadApi('/events/export', '比赛项目导出.xlsx')
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e?.message || '导出失败，请重新登录后再试')
  }
}

// ==================== 方法 ====================

// 加载数据
async function fetchData() {
  loading.value = true
  try {
    const params: Record<string, string | number> = {
      page: pagination.page,
      size: pagination.size,
    }
    if (filters.grade) params.grade = filters.grade
    if (filters.gender) params.gender = filters.gender
    if (filters.eventType) params.eventType = filters.eventType

    const res = await request.get('/events', { params })
    // 拦截器已解包 res.data，可能是 { records, total } 或直接是数组
    if (Array.isArray(res)) {
      tableData.value = res
      pagination.total = res.length
    } else if (res && res.records) {
      tableData.value = res.records
      pagination.total = res.total ?? 0
    } else if (res && res.total !== undefined) {
      tableData.value = res.records ?? []
      pagination.total = res.total
    } else {
      tableData.value = (res as any) ?? []
      pagination.total = tableData.value.length
    }
  } catch {
    // 错误由拦截器统一处理
  } finally {
    loading.value = false
  }
}

// 筛选变化
function handleFilterChange() {
  pagination.page = 1
  fetchData()
}

// 分页
function handlePageChange(page: number) {
  pagination.page = page
  fetchData()
}

function handleSizeChange(size: number) {
  pagination.size = size
  pagination.page = 1
  fetchData()
}

// 打开预设模板对话框
function openTemplateDialog() {
  templateCategory.value = '跑步类'
  templateDialogVisible.value = true
}

// 选择模板
function selectTemplate(tpl: TemplateItem) {
  templateDialogVisible.value = false

  // 填充表单并打开编辑对话框
  resetFormData()
  formData.name = tpl.name
  formData.eventType = tpl.eventType
  formData.gender = tpl.gender
  formData.gradeGroup = ''
  formData.maxParticipants = 1
  formData.description = ''
  formData.sortOrder = 0
  formData.enabled = true
  onEventTypeChange(tpl.eventType)

  isEdit.value = false
  editingId.value = null
  formDialogVisible.value = true
}

// 新增
function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  resetFormData()
  formDialogVisible.value = true
}

// 编辑
function openEditDialog(row: EventItem) {
  isEdit.value = true
  editingId.value = row.id ?? null
  fillFormFromRow(row)
  formDialogVisible.value = true
}

// 重置表单数据
function resetFormData() {
  formData.name = ''
  formData.eventType = ''
  formData.gender = ''
  formData.gradeGroup = ''
  formData.maxParticipants = 1
  formData.description = ''
  formData.sortOrder = 0
  formData.enabled = true
  formData.code = ''
  formData.isTrack = true
  formData.laneCount = 8
  formData.isTeam = false
  formData.teamSize = 0
  formData.scheduleMode = 'serial'
  formData.defaultVenue = ''
  formData.maxDurationMinutes = undefined
  formData.intervalMinutes = undefined
  formData.needHeats = true
  formData.advanceCount = 8
  formData.maxPerHeat = 8
}

// 组装提交体：径赛/田赛 自动联动 道次
function buildPayload() {
  const isTrack = formData.eventType !== '田赛'
  return {
    ...formData,
    isTrack,
    laneCount: isTrack ? (formData.laneCount ?? 8) : 0,
    teamSize: formData.teamSize ?? 0,
    needHeats: isTrack ? (formData.needHeats ?? true) : false,
    advanceCount: isTrack ? (formData.advanceCount ?? 8) : null,
    maxPerHeat: isTrack ? (formData.maxPerHeat ?? formData.laneCount ?? 8) : 1,
  }
}

// 把服务端行数据填回表单（含表格2/调度字段兼容）
function fillFormFromRow(row: EventItem) {
  const isTrack = row.eventType !== '田赛'
  formData.name = row.name
  formData.eventType = row.eventType
  formData.gender = row.gender
  formData.gradeGroup = row.gradeGroup
  formData.maxParticipants = row.maxParticipants
  formData.description = row.description ?? ''
  formData.sortOrder = row.sortOrder ?? 0
  formData.enabled = row.enabled
  formData.code = row.code ?? ''
  formData.isTrack = row.isTrack ?? isTrack
  formData.laneCount = isTrack ? (row.laneCount ?? 8) : 0
  formData.isTeam = !!row.isTeam
  formData.teamSize = row.teamSize ?? 0
  formData.scheduleMode = row.scheduleMode || (isTrack ? 'serial' : 'parallel')
  formData.defaultVenue = row.defaultVenue ?? ''
  formData.maxDurationMinutes = row.maxDurationMinutes ?? undefined
  formData.intervalMinutes = row.intervalMinutes ?? undefined
  formData.needHeats = row.needHeats ?? true
  formData.advanceCount = row.advanceCount ?? 8
  formData.maxPerHeat = row.maxPerHeat ?? (isTrack ? (row.laneCount ?? 8) : 1)
}

function onEventTypeChange(val: string) {
  formData.eventType = val
  if (val === '田赛') {
    formData.laneCount = 0
    formData.scheduleMode = 'parallel'
  } else {
    formData.laneCount = formData.laneCount && formData.laneCount > 0 ? formData.laneCount : 8
    formData.scheduleMode = formData.scheduleMode === 'parallel' ? 'serial' : formData.scheduleMode
  }
}

// 关闭对话框时重置表单
function resetForm() {
  formRef.value?.resetFields()
}

// 提交表单
async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value && editingId.value !== null) {
      await request.put(`/events/${editingId.value}`, payload)
      ElMessage.success('项目更新成功')
    } else {
      await request.post('/events', payload)
      ElMessage.success('项目创建成功')
    }
    formDialogVisible.value = false
    fetchData()
  } catch {
    // 错误由拦截器统一处理
  } finally {
    submitLoading.value = false
  }
}

// 启用/禁用
async function handleToggleStatus(row: EventItem, val: boolean) {
  const action = val ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}项目"${row.name}"吗？`,
      `${action}确认`,
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    // 用户取消，回滚 switch
    fetchData()
    return
  }

  try {
    await request.put(`/events/${row.id}/status`, { enabled: val })
    ElMessage.success(`项目已${action}`)
    fetchData()
  } catch {
    fetchData()
  }
}

// 删除
async function handleDelete(row: EventItem) {
  try {
    await ElMessageBox.confirm(
      `确定要删除项目"${row.name}"吗？删除后不可恢复。`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
  } catch {
    return
  }

  try {
    await request.delete(`/events/${row.id}`)
    ElMessage.success('项目已删除')
    // 如果当前页删空，回到上一页
    if (tableData.value.length === 1 && pagination.page > 1) {
      pagination.page--
    }
    fetchData()
  } catch {
    // 错误由拦截器统一处理
  }
}

// ==================== 批量操作 ====================
const tableRef = ref<any>()
const multipleSelection = ref<EventItem[]>([])
const batchAddVisible = ref(false)
const batchEditVisible = ref(false)
const batchAddSubmitting = ref(false)
const batchEditSubmitting = ref(false)
const batchAddNames = ref('')
const batchItems = ref<{ name: string; code?: string }[]>([])

const batchAddForm = reactive({
  eventType: '径赛',
  gender: '男子组',
  gradeGroup: '',
  teamSize: 0,
  needHeats: true,
  advanceCount: 8,
  maxParticipants: 1,
  defaultVenue: '',
})

const batchPatch = reactive<Record<string, any>>({
  eventType: undefined,
  gender: undefined,
  gradeGroup: undefined,
  laneCount: null,
  teamSize: null,
  scheduleMode: undefined,
  defaultVenue: undefined,
  enabled: undefined,
})

function onSelectionChange(rows: EventItem[]) {
  multipleSelection.value = rows
}
function clearSelection() {
  tableRef.value?.clearSelection()
}

// 批量新增名称行解析（watch 文本域 → 重建可编辑列表）
watch(batchAddNames, () => {
  const arr: { name: string; code?: string }[] = []
  batchAddNames.value.split('\n').forEach(line => {
    const s = line.trim()
    if (!s) return
    const parts = s.split(/[,，]/).map(x => x.trim())
    const name = parts[0] || ''
    if (!name) return
    arr.push({ name, code: parts.length > 1 && parts[1] ? parts[1] : undefined })
  })
  batchItems.value = arr
})
function removePreviewItem(i: number) {
  batchItems.value.splice(i, 1)
  batchAddNames.value = batchItems.value.map(x => (x.code ? `${x.name},${x.code}` : x.name)).join('\n')
}

function openBatchAdd() {
  Object.assign(batchAddForm, {
    eventType: '径赛', gender: '男子组', gradeGroup: '', teamSize: 0,
    needHeats: true, advanceCount: 8, maxParticipants: 1, defaultVenue: '',
  })
  batchAddNames.value = ''
  batchAddVisible.value = true
}

function buildBatchItem(it: { name: string; code?: string }, idx: number) {
  const isTrack = batchAddForm.eventType !== '田赛'
  const needHeats = isTrack && batchAddForm.needHeats
  const item: Record<string, any> = {
    name: it.name,
    eventType: batchAddForm.eventType,
    gender: batchAddForm.gender,
    maxParticipants: batchAddForm.maxParticipants,
    isTrack,
    laneCount: isTrack ? 8 : 0,
    isTeam: batchAddForm.teamSize > 0,
    teamSize: batchAddForm.teamSize || 0,
    needHeats,
    advanceCount: needHeats ? batchAddForm.advanceCount : null,
    maxPerHeat: isTrack ? 8 : 1,
    scheduleMode: isTrack ? 'serial' : 'parallel',
    defaultVenue: batchAddForm.defaultVenue.trim() || undefined,
    enabled: true,
    sortOrder: pagination.total + idx,
  }
  if (it.code) item.code = it.code
  if (batchAddForm.gradeGroup) item.gradeGroup = batchAddForm.gradeGroup
  return item
}

async function submitBatchAdd() {
  if (!batchItems.value.length) return
  batchAddSubmitting.value = true
  try {
    const payload = batchItems.value.map((it, i) => buildBatchItem(it, i))
    const res: any = await request.post('/events/batch', payload)
    ElMessage.success(`批量创建完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    if (res?.failed) {
      const first = (res.errors || [])[0]
      if (first) ElMessage.warning(`失败示例：${first.name || first.message}`)
      console.warn(res.errors)
    }
    batchAddVisible.value = false
    fetchData()
  } catch {
    // 拦截器已提示
  } finally {
    batchAddSubmitting.value = false
  }
}

function openBatchEdit() {
  Object.assign(batchPatch, {
    eventType: undefined, gender: undefined, gradeGroup: undefined,
    laneCount: null, teamSize: null, scheduleMode: undefined,
    defaultVenue: undefined, enabled: undefined,
  })
  batchEditVisible.value = true
}

function buildPatchPayload(): Record<string, any> {
  const p: Record<string, any> = {}
  if (batchPatch.eventType) {
    p.eventType = batchPatch.eventType
    const isTrack = batchPatch.eventType !== '田赛'
    p.isTrack = isTrack
    if (!isTrack) { p.laneCount = 0; p.needHeats = false }
  }
  if (batchPatch.gender) p.gender = batchPatch.gender
  if (batchPatch.gradeGroup) p.gradeGroup = batchPatch.gradeGroup
  if (batchPatch.laneCount !== undefined && batchPatch.laneCount !== null) p.laneCount = batchPatch.laneCount
  if (batchPatch.teamSize !== undefined && batchPatch.teamSize !== null) {
    p.teamSize = batchPatch.teamSize
    p.isTeam = batchPatch.teamSize > 0
  }
  if (batchPatch.scheduleMode) p.scheduleMode = batchPatch.scheduleMode
  if (batchPatch.defaultVenue && String(batchPatch.defaultVenue).trim()) {
    p.defaultVenue = String(batchPatch.defaultVenue).trim()
  }
  if (batchPatch.enabled !== undefined && batchPatch.enabled !== null) p.enabled = batchPatch.enabled
  return p
}

async function submitBatchEdit() {
  const ids = multipleSelection.value.map(r => r.id).filter(Boolean) as number[]
  if (!ids.length) { ElMessage.info('请先勾选要修改的项目'); return }
  const patch = buildPatchPayload()
  if (!Object.keys(patch).length) { ElMessage.info('请至少填写一个要修改的属性'); return }
  batchEditSubmitting.value = true
  try {
    const res: any = await request.put('/events/batch', { ids, patch })
    ElMessage.success(`批量更新完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    if (res?.failed) console.warn(res.errors)
    batchEditVisible.value = false
    clearSelection()
    fetchData()
  } catch {
    // 拦截器已提示
  } finally {
    batchEditSubmitting.value = false
  }
}

async function batchSetStatus(enabled: boolean) {
  const ids = multipleSelection.value.map(r => r.id).filter(Boolean) as number[]
  if (!ids.length) return
  const action = enabled ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定要${action}已选的 ${ids.length} 个项目吗？`, `${action}确认`, { type: 'warning' })
  } catch {
    return
  }
  try {
    const res: any = await request.post('/events/batch-status', { ids, enabled })
    ElMessage.success(`批量${action}完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    clearSelection()
    fetchData()
  } catch {
    // 拦截器已提示
  }
}

async function confirmBatchDelete() {
  const rows = multipleSelection.value
  const ids = rows.map(r => r.id).filter(Boolean) as number[]
  if (!ids.length) return
  const names = rows.slice(0, 5).map(r => r.name).join('、') + (rows.length > 5 ? ` 等 ${rows.length} 个` : '')
  try {
    await ElMessageBox.confirm(
      `确定删除已选 ${ids.length} 个项目（${names}）吗？删除后不可恢复。`,
      '批量删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const res: any = await request.post('/events/batch-delete', { ids })
    ElMessage.success(`批量删除完成：成功 ${res?.success || 0} 条，失败 ${res?.failed || 0} 条`)
    if (tableData.value.length === ids.length && pagination.page > 1) pagination.page--
    clearSelection()
    fetchData()
  } catch {
    // 拦截器已提示
  }
}

// ==================== 生命周期 ====================
async function loadGradeOptions() {
  try {
    const res = await request.get('/system/grades')
    const list = Array.isArray(res) ? res : (res?.records || [])
    gradeOptions.value = list.map((g: any) => (g && g.name) || '').filter(Boolean)
  } catch {
    gradeOptions.value = []
  }
}

onMounted(() => {
  fetchData()
  loadGradeOptions()
})
</script>

<style scoped>
.events-container {
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  min-height: calc(100vh - 100px);
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 6px 0;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.page-desc {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 10px;
}

.toolbar-left {
  display: flex;
  gap: 10px;
}

.toolbar-right {
  display: flex;
  gap: 10px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* 模板列表 */
.template-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  padding: 8px 0;
}

.template-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.template-item:hover {
  border-color: #67c23a;
  background: #f0f9eb;
}

.template-item .template-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.template-item .template-info {
  flex: 1;
  font-size: 12px;
  color: #909399;
}
@media(max-width:768px) {
  .events-container { padding: 8px; }
  .toolbar { flex-direction: column; align-items: flex-start; }
  .toolbar-left, .toolbar-right { flex-wrap: wrap; }
}

.form-tip {
  width: 100%;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
}

/* ===== 批量操作条 ===== */
.batch-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 12px; padding: 8px 14px;
  background: linear-gradient(120deg, #eff6ff, #eef2ff);
  border: 1px solid #bfdbfe; border-radius: 12px;
}
.bb-ico { color: #2563eb; font-size: 16px; }
.bb-info { font-size: 13px; color: #1e3a8a; margin-right: 4px; }
.bb-info b { font-size: 15px; }

/* ===== 批量新增 ===== */
.batch-common { background: #f8fafc; border-radius: 12px; padding: 12px 14px 2px; margin-bottom: 12px; }
.bc-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  column-gap: 16px; row-gap: 2px;
}
.batch-names-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 6px; flex-wrap: wrap; }
.batch-label2 { font-size: 13px; font-weight: 700; color: #0f172a; }
.batch-hint { font-size: 12px; color: #94a3b8; }
.batch-hint code { background: #f1f5f9; padding: 0 4px; border-radius: 4px; }
.batch-preview { margin-top: 10px; }
.bp-title { font-size: 12.5px; color: #475569; }
.bp-tiles { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; max-height: 150px; overflow-y: auto; }
.bp-code { margin-left: 4px; font-size: 11px; color: #94a3b8; font-weight: 400; }

/* ===== 批量修改 ===== */
.be-names { font-size: 13px; color: #475569; line-height: 1.8; }
</style>
