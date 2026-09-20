<template>
  <div class="rule-scripts-page">
    <!-- 页面头 -->
    <div class="pg-head">
      <div class="pg-titles">
        <span class="pg-ico"><el-icon :size="20"><MagicStick /></el-icon></span>
        <div>
          <h3 class="pg-title">规则注入 · 自定义编排规则</h3>
          <p class="pg-desc">
            L1「自定义规则」层 · 形态一：用「积木」或「代码」描述规则片段（两者是同一棵 AST 的两种投影，双向同步），
            规则会作为**动态约束**注入编排（hard/medium/soft + veto）。
          </p>
        </div>
      </div>
      <div class="pg-actions">
        <el-tag v-for="e in engines" :key="e.name" size="small"
          :type="e.available ? 'success' : 'info'" effect="plain" style="margin-right:6px">
          {{ e.name }}{{ e.available ? ' 可用' : ' 需引入依赖' }}
        </el-tag>
        <el-button type="primary" :loading="saving" @click="saveAll">保存全部</el-button>
      </div>
    </div>

    <el-row :gutter="14">
      <!-- 脚本列表 -->
      <el-col :xs="24" :sm="8" :md="7" :lg="6">
        <el-card shadow="never" class="list-card">
          <template #header>
            <div class="card-head">
              <span>规则脚本（{{ scripts.length }}）</span>
              <el-button size="small" type="primary" plain @click="newScript">新建</el-button>
            </div>
          </template>
          <div v-if="!scripts.length" class="empty-tip">暂无规则脚本，点击「新建」开始</div>
          <div v-for="s in scripts" :key="s.id" :class="['script-item', { active: s.id === draft.id }]"
            @click="select(s)">
            <div class="si-main">
              <div class="si-name">
                <el-tag v-if="!s.enabled" size="small" type="info" effect="plain">停用</el-tag>
                {{ s.name || '(未命名)' }}
              </div>
              <div class="si-meta">{{ s.id }} · {{ s.engine || 'builtin' }}</div>
            </div>
            <el-button link type="danger" size="small" @click.stop="removeScript(s.id)">删除</el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 编辑器 -->
      <el-col :xs="24" :sm="16" :md="17" :lg="12">
        <el-card shadow="never">
          <el-form label-width="72px" size="default">
            <el-form-item label="名称">
              <el-input v-model="draft.name" placeholder="如：径赛前两道软惩罚" style="max-width:320px" />
              <el-switch v-model="draft.enabled" active-text="启用" style="margin-left:12px" />
            </el-form-item>
            <el-form-item label="引擎">
              <el-radio-group v-model="draft.engine">
                <el-radio-button value="builtin">内置伪代码</el-radio-button>
                <el-radio-button value="groovy">Groovy (JSR-223)</el-radio-button>
                <el-radio-button value="javascript">JavaScript (JSR-223)</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="模式">
              <el-radio-group v-model="mode" @change="onModeChange">
                <el-radio-button value="blocks">积木</el-radio-button>
                <el-radio-button value="code">代码</el-radio-button>
              </el-radio-group>
              <span class="hint">积木与代码实时双向同步（共享同一棵 AST）</span>
            </el-form-item>
          </el-form>

          <!-- 积木模式 -->
          <div v-if="mode === 'blocks'" class="blocks">
            <div v-for="(rule, ri) in block.rules" :key="ri" class="block-rule">
              <div class="br-head">
                规则 {{ ri + 1 }}
                <el-button link type="danger" size="small" @click="block.rules.splice(ri, 1)">移除</el-button>
              </div>
              <div class="br-cond">
                <span class="ctx">当</span>
                <el-select v-model="rule.join" size="small" style="width:88px">
                  <el-option value="&&" label="并且 &&" />
                  <el-option value="||" label="或者 ||" />
                </el-select>
                <div class="cond-list">
                  <div v-for="(c, ci) in rule.conditions" :key="ci" class="cond-row">
                    <el-select v-model="c.field" size="small" filterable style="width:190px">
                      <el-option v-for="f in FIELD_OPTIONS" :key="f.value" :label="f.label" :value="f.value" />
                    </el-select>
                    <el-select v-model="c.op" size="small" style="width:120px">
                      <el-option v-for="o in OP_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
                    </el-select>
                    <el-input v-model="c.value" size="small" style="width:130px" placeholder="值" />
                    <el-button link type="danger" size="small" @click="rule.conditions.splice(ci, 1)">×</el-button>
                  </div>
                  <el-button size="small" plain @click="rule.conditions.push({ field: '', op: '==', value: '' })">
                    + 条件
                  </el-button>
                </div>
              </div>
              <div class="br-act">
                <span class="ctx">则</span>
                <div class="cond-list">
                  <div v-for="(a, ai) in rule.actions" :key="ai" class="cond-row">
                    <el-select v-model="a.kind" size="small" style="width:170px">
                      <el-option v-for="t in ACTION_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
                    </el-select>
                    <el-input v-if="a.kind !== 'veto'" v-model.number="a.value" size="small"
                      style="width:110px" placeholder="数值" />
                    <el-button link type="danger" size="small" @click="rule.actions.splice(ai, 1)">×</el-button>
                  </div>
                  <el-button size="small" plain @click="rule.actions.push({ kind: 'soft', value: 10 })">
                    + 动作
                  </el-button>
                </div>
              </div>
            </div>
            <el-button size="small" @click="block.rules.push(emptyRule())">+ 新增规则</el-button>
          </div>

          <!-- 代码模式 -->
          <div v-else class="code">
            <el-input v-model="draft.source" type="textarea" :rows="9"
              placeholder="when event.category == &quot;径赛&quot; &amp;&amp; lane &lt;= 2 then soft += 30" />
          </div>
          <el-alert v-if="syncError" :title="syncError" type="warning" show-icon :closable="false"
            style="margin-top:8px" />

          <div class="preview">
            <div class="preview-title">DSL 预览</div>
            <pre class="preview-body">{{ draft.source || '（空）' }}</pre>
          </div>
        </el-card>
      </el-col>

      <!-- 试运行面板 -->
      <el-col :xs="24" :sm="24" :md="24" :lg="6">
        <el-card shadow="never">
          <template #header><div class="card-head"><span>运行面板 · 试运行</span></div></template>
          <div class="hint" style="margin-bottom:6px">上下文（JSON）</div>
          <el-input v-model="testContext" type="textarea" :rows="9" />
          <el-button type="success" style="margin-top:8px;width:100%" :loading="testing" @click="runTest">
            运行（不落库）
          </el-button>

          <div v-if="testResult" class="result">
            <el-alert :type="testResult.ok ? 'success' : 'error'" show-icon :closable="false"
              :title="testResult.ok ? '执行成功' : '执行失败'" />
            <div v-if="!testResult.ok" class="err">{{ testResult.error }}</div>
            <template v-else>
              <div class="kv"><span>hard</span><b>{{ testResult.hard }}</b></div>
              <div class="kv"><span>medium</span><b>{{ testResult.medium }}</b></div>
              <div class="kv"><span>soft</span><b>{{ testResult.soft }}</b></div>
              <div class="kv"><span>veto</span><b>{{ testResult.veto ? '是' : '否' }}</b></div>
              <div class="fired">
                <div v-for="(f, i) in testResult.fired" :key="i" class="fired-item">· {{ f }}</div>
              </div>
            </template>
          </div>
          <div class="hint" style="margin-top:10px">
            提示：改完脚本记得「保存全部」；保存后规则立即对后续编排生效（含优化模式求解）。
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import request from '@/utils/request'
import {
  FIELD_OPTIONS, OP_OPTIONS, ACTION_OPTIONS,
  emptyRule, emptyBlock, blocksToDsl, dslToBlocks, defaultContextJson
} from '@/utils/ruleDsl'

const engines = ref([])
const scripts = ref([])
const draft = reactive({ id: '', name: '', engine: 'builtin', enabled: true, source: '' })
const mode = ref('blocks')
const block = ref(emptyBlock())
const syncError = ref('')
const testContext = ref(defaultContextJson())
const testResult = ref(null)
const saving = ref(false)
const testing = ref(false)

// 防抖环：改积木 → 生成 DSL → 若回解析成同一份积木则不覆盖，避免死循环
let syncing = false

function genId () {
  return 'rule_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

async function load () {
  try {
    const res = await request.get('/arrange/rule-scripts')
    engines.value = res?.engines || []
    scripts.value = res?.scripts || []
    if (scripts.value.length) select(scripts.value[0])
    else newScript()
  } catch (e) {
    ElMessage.error('加载规则脚本失败')
  }
}

function select (s) {
  draft.id = s.id
  draft.name = s.name
  draft.engine = s.engine || 'builtin'
  draft.enabled = s.enabled !== false
  draft.source = s.source || ''
  syncBlocksFromSource(true)
}

function newScript () {
  draft.id = genId()
  draft.name = ''
  draft.engine = 'builtin'
  draft.enabled = true
  block.value = emptyBlock()
  draft.source = blocksToDsl(block.value)
  syncError.value = ''
  mode.value = 'blocks'
}

function removeScript (id) {
  ElMessageBox.confirm('确定删除该规则脚本？', '删除确认', { type: 'warning' }).then(() => {
    scripts.value = scripts.value.filter(s => s.id !== id)
    if (draft.id === id) {
      if (scripts.value.length) select(scripts.value[0])
      else newScript()
    }
  }).catch(() => {})
}

/** 把 draft 收进列表（保存前把编辑结果回写列表，保证「保存全部」包含当前编辑） */
function syncDraftIntoList () {
  const item = { id: draft.id, name: draft.name, engine: draft.engine, enabled: draft.enabled, source: draft.source }
  const i = scripts.value.findIndex(s => s.id === draft.id)
  if (i >= 0) scripts.value.splice(i, 1, item)
  else scripts.value.push(item)
}

async function saveAll () {
  syncDraftIntoList()
  saving.value = true
  try {
    const res = await request.put('/arrange/rule-scripts', { scripts: scripts.value })
    scripts.value = res || scripts.value
    ElMessage.success('规则脚本已保存')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 代码 → 积木（同步）；unparseable 时给出提示但不阻断代码编辑 */
function syncBlocksFromSource (silent) {
  if (syncing) return
  const parsed = dslToBlocks(draft.source)
  if (parsed) {
    syncing = true
    block.value = parsed
    syncing = false
    syncError.value = ''
  } else {
    syncError.value = '当前 DSL 暂不支持积木模式（可用代码模式继续编辑）'
    if (silent) syncError.value = ''
  }
}

/** 积木 → 代码（同步） */
function syncSourceFromBlocks () {
  if (syncing) return
  syncing = true
  draft.source = blocksToDsl(block.value)
  syncing = false
  syncError.value = ''
}

function onModeChange (m) {
  if (m === 'blocks') syncBlocksFromSource(true)
  else syncSourceFromBlocks()
}

// 双向同步：积木编辑 → 代码；代码编辑 → 积木
watch(block, () => {
  if (mode.value === 'blocks') syncSourceFromBlocks()
}, { deep: true })
watch(() => draft.source, () => {
  if (mode.value === 'code') syncBlocksFromSource(true)
})

async function runTest () {
  let ctx
  try {
    ctx = JSON.parse(testContext.value || '{}')
  } catch (e) {
    ElMessage.error('上下文 JSON 解析失败：' + e.message)
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const res = await request.post('/arrange/rule-scripts/test', {
      script: { id: draft.id, name: draft.name, engine: draft.engine, enabled: true, source: draft.source },
      context: ctx
    })
    testResult.value = res || null
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '试运行失败')
  } finally {
    testing.value = false
  }
}

load()
</script>

<style scoped>
.rule-scripts-page { display: flex; flex-direction: column; gap: 14px; }
.pg-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.pg-titles { display: flex; gap: 10px; align-items: flex-start; }
.pg-ico { display: inline-flex; padding: 8px; border-radius: 10px; background: #eef2ff; color: #4f46e5; }
.pg-title { margin: 0; font-size: 16px; }
.pg-desc { margin: 4px 0 0; font-size: 12px; color: #6b7280; max-width: 900px; line-height: 1.6; }
.pg-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.list-card { min-height: 320px; }
.empty-tip { color: #9ca3af; font-size: 13px; padding: 12px 4px; }
.script-item { display: flex; justify-content: space-between; align-items: center; padding: 8px 6px;
  border-radius: 8px; cursor: pointer; border: 1px solid transparent; }
.script-item:hover { background: #f8fafc; }
.script-item.active { background: #eef2ff; border-color: #c7d2fe; }
.si-name { font-size: 13px; font-weight: 600; }
.si-meta { font-size: 11px; color: #9ca3af; }
.blocks { border: 1px dashed #d1d5db; border-radius: 10px; padding: 10px; background: #fcfcfd; }
.block-rule { border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px; margin-bottom: 8px; background: #fff; }
.br-head { display: flex; justify-content: space-between; font-size: 12px; color: #6b7280; margin-bottom: 6px; }
.br-cond, .br-act { display: flex; gap: 8px; margin-bottom: 6px; align-items: flex-start; }
.ctx { font-size: 12px; color: #4f46e5; padding-top: 5px; }
.cond-list { display: flex; flex-direction: column; gap: 4px; }
.cond-row { display: flex; gap: 6px; align-items: center; }
.code { margin-top: 4px; }
.code :deep(textarea) { font-family: Consolas, Monaco, monospace; font-size: 12.5px; }
.preview { margin-top: 10px; }
.preview-title { font-size: 12px; color: #6b7280; margin-bottom: 4px; }
.preview-body { background: #0f172a; color: #a5f3fc; border-radius: 8px; padding: 10px;
  font-size: 12.5px; overflow: auto; max-height: 140px; white-space: pre-wrap; }
.hint { font-size: 11.5px; color: #9ca3af; margin-left: 8px; }
.result { margin-top: 10px; }
.err { margin-top: 6px; font-size: 12px; color: #b91c1c; word-break: break-all; }
.kv { display: flex; justify-content: space-between; font-size: 13px; padding: 3px 0; }
.fired { margin-top: 6px; }
.fired-item { font-size: 12px; color: #374151; }
</style>
