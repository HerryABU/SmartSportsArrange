<template>
  <div class="referees-page" v-loading="loading">
    <!-- 页面头 -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">🏅</span>
        <div>
          <h3 class="pg-title">裁判管理</h3>
          <p class="pg-desc">裁判花名册（独立于登录账号）· 增删改查 · Excel 批量导入（专长项目支持 [a,b，c] 列表语法）· 模板下载；智能编排时按「组次裁判数量」自动分配</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#f0fdf4;color:#15803d">共 {{ refereeList.length }} 名裁判</span>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>裁判列表</span>
          <div class="header-actions">
            <el-button type="primary" size="small" @click="openAdd"><el-icon><Plus /></el-icon> 新增裁判</el-button>
            <el-upload :action="importUrl" :headers="uploadHeaders" :show-file-list="false" accept=".xlsx,.xls"
              :on-success="onImportSuccess" :on-error="onImportError" style="display:inline-block;margin-left:6px">
              <el-button type="success" size="small"><el-icon><Upload /></el-icon> 导入Excel</el-button>
            </el-upload>
            <el-button size="small" plain @click="downloadTemplate" style="margin-left:4px"><el-icon><DocumentCopy /></el-icon> 下载模板</el-button>
            <el-button size="small" type="warning" plain @click="openAllAccounts" :loading="accountWorking" style="margin-left:4px">
              <el-icon><Key /></el-icon> 批量开通账号
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="refereeList" border stripe>
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="phone" label="电话" min-width="140" />
        <el-table-column label="专长项目" min-width="220">
          <template #default="{ row }">
            <span v-if="row.specialtiesText">{{ row.specialtiesText }}</span>
            <el-tag v-else size="small" type="info" effect="plain">不限（通用）</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="登录账号" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.hasAccount ? 'success' : 'info'" effect="plain">
              {{ row.hasAccount ? '已开通' : '未开通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="openEdit(row)">编辑</el-button>
            <el-button v-if="!row.hasAccount" type="warning" size="small" link @click="openAccount(row)">开通账号</el-button>
            <el-button type="danger" size="small" link @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !refereeList.length" description="暂无裁判，请先新增或导入" :image-size="80" />
    </el-card>

    <!-- 裁判新增/编辑弹窗 -->
    <el-dialog v-model="showDialog" :title="editing ? '编辑裁判' : '新增裁判'" width="480px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="请输入裁判姓名" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="请输入电话" />
        </el-form-item>
        <el-form-item label="专长项目">
          <el-input v-model="specialtiesText" type="textarea" :rows="2"
            placeholder="用逗号分隔，如：立定跳远，拔河，50米蛙泳（支持中英文逗号）" />
          <div class="rule-desc">留空表示「通用裁判」，可参与任意项目吹判。智能编排时专长命中者优先入选。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="save">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload, DocumentCopy, Key } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const refereeList = ref([])
const showDialog = ref(false)
const editing = ref(null)

const form = reactive({ name: '', phone: '', specialties: '' })
const specialtiesText = ref('')

const importUrl = apiBase() + '/system/referees/import'
const uploadHeaders = computed(() => ({ Authorization: 'Bearer ' + authStore.token }))
const accountWorking = ref(false)

/** 为单个裁判开通登录账号（角色 REFEREE；用户名默认取手机号，密码默认 123456） */
async function openAccount(row) {
  try {
    const r = await request.post('/system/referees/' + row.id + '/account', {})
    if (r?.created) {
      ElMessageBox.alert(
        `裁判「${r.name}」账号已开通\n用户名：${r.username}\n初始密码：${r.password}`,
        '账号已开通',
        { confirmButtonText: '知道了', type: 'success' }
      )
    } else {
      ElMessage.info(r?.message || '该裁判已开通账号')
    }
    fetchList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '开通账号失败')
  }
}

/** 批量为尚未开通账号的裁判开通账号 */
async function openAllAccounts() {
  try {
    await ElMessageBox.confirm(
      '将为所有尚未开通账号的裁判创建登录账号（用户名默认取手机号，密码默认 123456），是否继续？',
      '批量开通裁判账号',
      { type: 'warning' }
    )
  } catch { return }
  accountWorking.value = true
  try {
    const r = await request.post('/system/referees/accounts/open-all')
    const created = r?.created || 0
    if (created > 0) {
      const lines = (r.accounts || []).map(a => `${a.name}：${a.username} / ${a.password}`).join('\n')
      ElMessageBox.alert(`已开通 ${created} 个裁判账号：\n${lines}`, '批量开通完成', {
        confirmButtonText: '知道了', type: 'success'
      })
    } else {
      ElMessage.success('所有裁判均已开通账号')
    }
    fetchList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '批量开通失败')
  } finally {
    accountWorking.value = false
  }
}

async function fetchList() {
  loading.value = true
  try {
    const res = await request.get('/system/referees')
    refereeList.value = Array.isArray(res) ? res : (res?.records || [])
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

function openAdd() {
  editing.value = null
  Object.assign(form, { name: '', phone: '', specialties: '' })
  specialtiesText.value = ''
  showDialog.value = true
}
function openEdit(row) {
  editing.value = row
  Object.assign(form, { name: row.name, phone: row.phone || '', specialties: '' })
  specialtiesText.value = row.specialtiesText || (Array.isArray(row.specialties) ? row.specialties.join('，') : '')
  showDialog.value = true
}
async function save() {
  if (!form.name || !form.name.trim()) { ElMessage.warning('请输入裁判姓名'); return }
  // 专长项目：中英文逗号分隔 → 交给后端统一解析为 [a,b,c] 标准列表
  const specs = specialtiesText.value
    ? specialtiesText.value.split(/[,，]/).map(s => s.trim()).filter(Boolean).join(',')
    : ''
  const payload = { name: form.name.trim(), phone: form.phone || '', specialties: specs }
  try {
    if (editing.value) {
      await request.put('/system/referees/' + editing.value.id, payload)
      ElMessage.success('更新成功')
    } else {
      await request.post('/system/referees', payload)
      ElMessage.success('创建成功')
    }
    showDialog.value = false
    fetchList()
  } catch (e) { console.error(e) }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm('确定删除裁判「' + row.name + '」吗？', '提示', { type: 'warning' })
    await request.delete('/system/referees/' + row.id)
    ElMessage.success('删除成功')
    fetchList()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}
function onImportSuccess(res) {
  if (res && res.code === 200) {
    const d = res.data || {}
    ElMessage.success('导入完成：成功 ' + (d.success ?? 0) + ' 条，失败 ' + (d.failed ?? 0) + ' 条')
    fetchList()
  } else {
    ElMessage.error(res?.message || '导入失败')
  }
}
function onImportError() { ElMessage.error('导入失败，请检查文件格式') }
function downloadTemplate() { window.open(apiBase() + '/system/referees/template', '_blank') }

onMounted(fetchList)
</script>

<style scoped>
.referees-page { padding: 0; }
.pg-head { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:16px; }
.pg-titles { display:flex; gap:14px; }
.pg-ico { font-size:32px; line-height:1; }
.pg-title { margin:0 0 6px; font-size:20px; font-weight:700; color:var(--text-primary); }
.pg-desc { margin:0; font-size:13px; color:#909399; max-width:880px; line-height:1.7; }
.card-header { display:flex; justify-content:space-between; align-items:center; }
.header-actions { display:flex; align-items:center; flex-wrap:wrap; gap:4px; }
.rule-desc { font-size:12px; color:#909399; margin-top:4px; }
</style>
