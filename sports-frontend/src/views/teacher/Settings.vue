<template>
  <div class="settings-page" v-loading="loading">
    <el-tabs v-model="activeTab" :tab-position="isMobile ? 'top' : 'left'">
      <!-- Basic Settings -->
      <el-tab-pane label="基本设置" name="basic">
        <el-card shadow="never">
          <template #header><span>运动会基本信息</span></template>
          <el-form :model="basicForm" label-width="120px" style="max-width: 600px">
            <el-form-item label="运动会名称">
              <el-input v-model="basicForm.name" placeholder="请输入运动会名称" />
            </el-form-item>
            <el-form-item label="举办日期">
              <el-date-picker v-model="basicForm.dateRange" type="daterange" range-separator="至"
                start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
            <el-form-item label="举办地点">
              <el-input v-model="basicForm.location" placeholder="请输入举办地点" />
            </el-form-item>
            <el-form-item label="主办单位">
              <el-input v-model="basicForm.organizer" placeholder="请输入主办单位" />
            </el-form-item>
            <el-form-item label="当前状态">
              <el-select v-model="basicForm.status" style="width: 100%">
                <el-option label="筹备中" value="PREPARING" />
                <el-option label="报名中" value="REGISTERING" />
                <el-option label="进行中" value="IN_PROGRESS" />
                <el-option label="已结束" value="FINISHED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveBasic">保存设置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- Scoring Rules -->
      <el-tab-pane label="积分规则" name="scoring">
        <el-card shadow="never">
          <template #header><span>积分规则设置</span></template>
          <el-form :model="scoringForm" label-width="120px" style="max-width: 500px">
            <el-form-item v-for="(_, rank) in scoringForm" :key="rank" :label="'第 '+(Number(rank)+1)+' 名积分'">
              <el-input-number v-model="scoringForm[rank]" :min="0" :max="100" />
            </el-form-item>
            <el-form-item label="破纪录加分">
              <el-input-number v-model="recordBonus" :min="0" :max="50" />
            </el-form-item>
            <el-form-item label="参与积分">
              <el-input-number v-model="participationPoints" :min="0" :max="20" />
            </el-form-item>
            <el-divider />
            <div class="section-title" style="margin-bottom:12px">📋 报名限制</div>
            <el-form-item label="每人最多报项">
              <el-input-number v-model="maxEventsPerAthlete" :min="1" :max="10" />
              <span style="margin-left:8px;color:#909399">项</span>
            </el-form-item>
            <el-form-item label="每班每项最多">
              <el-input-number v-model="maxAthletesPerEvent" :min="1" :max="20" />
              <span style="margin-left:8px;color:#909399">人</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveScoring">保存积分规则</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- Grade Settings -->
      <el-tab-pane label="年级设置" name="grade">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>年级组设置</span>
              <el-button type="primary" size="small" @click="openAddGrade">新增年级</el-button>
            </div>
          </template>
          <el-table :data="gradeList" border stripe>
            <el-table-column prop="name" label="年级名称" />
            <el-table-column prop="sortOrder" label="排序" width="100" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button type="primary" size="small" link @click="openEditGrade(row)">编辑</el-button>
                <el-button type="danger" size="small" link @click="deleteGradeItem(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 批量创建 — 仅管理员可见 -->
      <el-tab-pane v-if="authStore.isAdmin" label="批量创建" name="batch">
        <el-row :gutter="16">
          <!-- 批量创建班级 -->
          <el-col :span="12">
            <el-card shadow="never">
              <template #header><span>🏫 批量创建班级</span></template>
              <el-form label-width="100px">
                <el-form-item label="年级选择">
                  <el-collapse v-model="gradeActiveNames" style="width:100%">
                    <el-collapse-item v-for="grp in gradeGroups" :key="grp.label" :name="grp.label" :title="grp.label">
                      <el-checkbox-group v-model="batchClass.grades">
                        <el-checkbox v-for="g in grp.grades" :key="g" :label="g" :value="g" style="margin-right:12px" />
                      </el-checkbox-group>
                      <el-button size="small" link type="primary" @click.stop="selectGroup(grp)">全选</el-button>
                      <el-button size="small" link type="warning" @click.stop="deselectGroup(grp)">取消</el-button>
                    </el-collapse-item>
                  </el-collapse>
                </el-form-item>
                <el-form-item label="班级范围">
                  <el-input-number v-model="batchClass.from" :min="1" :max="30" size="small" controls-position="right" /> —
                  <el-input-number v-model="batchClass.to" :min="1" :max="30" size="small" controls-position="right" /> 班
                </el-form-item>
                <el-form-item label="班主任密码">
                  <el-input v-model="batchClass.defaultPwd" placeholder="默认123456" size="small" />
                  <span style="font-size:11px;color:#909399">每个班级自动生成班主任账号 (ct_高一1, ct_高一2...)</span>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="doBatchCreateClass" :loading="batchClass.loading">
                    批量创建班级
                  </el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>

          <!-- 批量创建用户 -->
          <el-col :span="12">
            <el-card shadow="never">
              <template #header><span>👥 批量创建用户</span></template>
              <el-form label-width="100px">
                <el-form-item label="用户角色">
                  <el-select v-model="batchUser.role" style="width:100%">
                    <el-option label="体育老师" value="TEACHER" />
                    <el-option label="班主任" value="CLASS_TEACHER" />
                    <el-option label="学生" value="STUDENT" />
                  </el-select>
                </el-form-item>
                <el-form-item label="创建数量">
                  <el-input-number v-model="batchUser.count" :min="1" :max="100" size="small" />
                </el-form-item>
                <el-form-item label="用户名前缀">
                  <el-input v-model="batchUser.prefix" placeholder="如 teacher_" size="small" />
                </el-form-item>
                <el-form-item label="默认密码">
                  <el-input v-model="batchUser.password" placeholder="123456" size="small" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="doBatchCreateUser" :loading="batchUser.loading">
                    批量创建用户
                  </el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- User Management — 仅管理员可见，按角色分类Tab -->
      <el-tab-pane v-if="authStore.isAdmin" label="用户管理" name="users">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>系统用户 (共 {{ userList.length }} 人)</span>
              <div class="header-actions">
                <el-button type="primary" size="small" @click="openAddUser">新增用户</el-button>
                <el-upload :action="userImportUrl" :headers="uploadHeaders" :show-file-list="false" accept=".xlsx,.xls" :on-success="onUserImportSuccess" style="display:inline-block;margin-left:6px">
                  <el-button type="success" size="small"><el-icon><Upload /></el-icon> 导入Excel</el-button>
                </el-upload>
                <el-button size="small" @click="downloadUserTemplate" style="margin-left:4px" plain><el-icon><DocumentCopy /></el-icon> 下载模板</el-button>
              </div>
            </div>
          </template>

          <el-tabs v-model="userRoleTab" @tab-change="onUserTabChange">
            <el-tab-pane v-for="rt in userRoleTabs" :key="rt.key" :name="rt.key">
              <template #label><span>{{ rt.icon }} {{ rt.label }} <el-tag size="small" style="margin-left:6px">{{ rt.count }}</el-tag></span></template>
            </el-tab-pane>
          </el-tabs>

          <!-- 班主任 Tab：可展开查看班级学生 -->
          <el-table v-if="userRoleTab==='CLASS_TEACHER'" :data="filteredUsers" border stripe row-key="id" @expand-change="onTeacherExpand">
            <el-table-column type="expand">
              <template #default="{row}">
                <div v-loading="expandLoading===row.id" style="padding:8px 24px">
                  <h4 style="margin:0 0 8px">{{ getTeacherClassName(row) }} — 学生列表 ({{ getTeacherStudents(row).length }}人)</h4>
                  <el-table :data="getTeacherStudents(row)" border size="small" v-if="getTeacherStudents(row).length">
                    <el-table-column prop="name" label="姓名" />
                    <el-table-column prop="studentId" label="学号" />
                    <el-table-column label="性别" width="60"><template #default="{row:r}">{{ r.gender==='M'?'男':r.gender==='F'?'女':'—' }}</template></el-table-column>
                    <el-table-column prop="grade" label="年级" width="80" />
                    <el-table-column prop="number" label="号码" width="100" />
                  </el-table>
                  <el-empty v-else description="该班级暂无学生" :image-size="40" />
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="username" label="用户名" /><el-table-column prop="realName" label="姓名(班主任)" />
            <el-table-column prop="role" label="角色"><template #default="{row}"><el-tag size="small" type="warning">{{roleLabel(row.role)}}</el-tag></template></el-table-column>
            <el-table-column label="管辖班级" width="140"><template #default="{row}"><el-tag size="small" effect="plain">{{ getTeacherClassName(row) }}</el-tag></template></el-table-column>
            <el-table-column prop="phone" label="电话" />
            <el-table-column label="操作" width="210"><template #default="{row}">
              <el-button type="primary" size="small" link @click="editUser(row)">编辑</el-button>
              <el-button type="warning" size="small" link @click="resetPassword(row)">重置密码</el-button>
              <el-button type="danger" size="small" link @click="deleteUser(row)">删除</el-button>
            </template></el-table-column>
          </el-table>

          <!-- 其他角色 Tab：普通平面表格 -->
          <el-table v-else :data="filteredUsers" border stripe>
            <el-table-column prop="username" label="用户名" /><el-table-column prop="realName" label="姓名" />
            <el-table-column prop="role" label="角色"><template #default="{row}"><el-tag size="small">{{roleLabel(row.role)}}</el-tag></template></el-table-column>
            <el-table-column prop="phone" label="电话" />
            <el-table-column label="操作" width="210"><template #default="{row}">
              <el-button type="primary" size="small" link @click="editUser(row)">编辑</el-button>
              <el-button type="warning" size="small" link @click="resetPassword(row)">重置密码</el-button>
              <el-button type="danger" size="small" link @click="deleteUser(row)">删除</el-button>
            </template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- User Dialog -->
    <el-dialog v-model="showUserDialog" :title="editingUser?'编辑用户':'新增用户'" width="500px">
      <el-form :model="userForm" label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="userForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="userForm.realName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="userForm.role" style="width:100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="体育老师" value="TEACHER" />
            <el-option label="班主任" value="CLASS_TEACHER" />
            <el-option label="学生" value="STUDENT" />
          </el-select>
        </el-form-item>
        <el-form-item label="密码" v-if="!editingUser">
          <el-input v-model="userForm.password" type="password" placeholder="默认123456" show-password />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="userForm.phone" placeholder="请输入电话" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUserDialog=false">取消</el-button>
        <el-button type="primary" @click="saveUser">确定</el-button>
      </template>
    </el-dialog>

    <!-- Grade Dialog -->
    <el-dialog v-model="showGradeDialog" :title="editingGrade?'编辑年级':'新增年级'" width="420px">
      <el-form :model="gradeForm" label-width="100px">
        <el-form-item label="年级名称">
          <el-input v-model="gradeForm.name" placeholder="如：高一年级" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="gradeForm.sortOrder" :min="0" :max="99" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showGradeDialog=false">取消</el-button>
        <el-button type="primary" @click="saveGrade">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, DocumentCopy } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(false)
const activeTab = ref('basic')
const isMobile = ref(window.innerWidth <= 768)

function checkMobile() { isMobile.value = window.innerWidth <= 768 }
onMounted(() => { window.addEventListener('resize', checkMobile) })
onBeforeUnmount(() => { window.removeEventListener('resize', checkMobile) })
const showUserDialog = ref(false)
const showGradeDialog = ref(false)
const editingUser = ref(null)
const editingGrade = ref(null)

const basicForm = reactive({ name:'', dateRange:[], location:'', organizer:'', status:'PREPARING' })
const scoringForm = reactive({ '0':9,'1':7,'2':6,'3':5,'4':4,'5':3,'6':2,'7':1 })
const recordBonus = ref(5)
const participationPoints = ref(1)
const maxEventsPerAthlete = ref(3)
const maxAthletesPerEvent = ref(3)
const gradeList = ref([])
const userList = ref([])
const userRoleTab = ref('ALL')
const classList = ref([])                 // 班级列表
const expandLoading = ref(null)           // 正在加载学生的班主任ID
const teacherStudentsCache = ref({})      // { teacherUserId: [students] }
const userRoleTabs = computed(()=>{
  const cats=[
    {key:'ALL',icon:'👥',label:'全部'},
    {key:'ADMIN',icon:'🛡️',label:'管理员'},
    {key:'TEACHER',icon:'🏅',label:'体育老师'},
    {key:'CLASS_TEACHER',icon:'📚',label:'班主任'},
    {key:'STUDENT',icon:'👨‍🎓',label:'学生'},
  ]
  cats.forEach(c=>{c.count=c.key==='ALL'?userList.value.length:userList.value.filter(u=>u.role===c.key).length})
  return cats
})
const filteredUsers = computed(()=>{
  if(userRoleTab.value==='ALL')return userList.value
  return userList.value.filter(u=>u.role===userRoleTab.value)
})
function onUserTabChange(tab){userRoleTab.value=tab}

// ============ 班主任 → 班级 → 学生 嵌套展开 ============
function getTeacherClass(teacherUser) {
  // 通过 teacherUserId 找到该班主任管辖的班级
  return classList.value.find(c => c.teacherUserId === teacherUser.id) || null
}
function getTeacherClassName(teacherUser) {
  const c = getTeacherClass(teacherUser)
  return c ? c.name : '未绑定班级'
}
function getTeacherStudents(teacherUser) {
  return teacherStudentsCache.value[teacherUser.id] || []
}
async function onTeacherExpand(row, expandedRows) {
  if (!expandedRows.includes(row)) return
  if (teacherStudentsCache.value[row.id]) return // 已缓存
  expandLoading.value = row.id
  try {
    const cls = getTeacherClass(row)
    if (!cls) { teacherStudentsCache.value[row.id] = []; return }
    const res = await request.get('/athletes', { params: { classId: cls.id, size: 200 } })
    teacherStudentsCache.value[row.id] = res.records || []
  } catch (e) { console.error(e); teacherStudentsCache.value[row.id] = [] }
  finally { expandLoading.value = null }
}

async function fetchClassList() {
  try {
    const res = await request.get('/classes', { params: { size: 500 } })
    classList.value = res.records || []
  } catch (e) { console.error(e) }
}

const userForm = reactive({ username:'', realName:'', role:'TEACHER', password:'', phone:'' })
const gradeForm = reactive({ name:'', sortOrder:0 })

const userImportUrl = '/api/system/users/import'
const uploadHeaders = computed(() => ({
  Authorization: 'Bearer ' + authStore.token
}))

// ============ 批量创建 ============
const allGrades = ['高一','高二','高三','初一','初二','初三','一年级','二年级','三年级','四年级','五年级','六年级']
const gradeGroups = [
  { label:'🏫 高中', grades:['高一','高二','高三'] },
  { label:'🏫 初中', grades:['初一','初二','初三'] },
  { label:'🏫 小学', grades:['一年级','二年级','三年级','四年级','五年级','六年级'] }
]
const gradeActiveNames = ref(['高中'])  // 默认展开高中
const batchClass = reactive({ grades:['高一','高二','高三'], from:1, to:8, defaultPwd:'123456', loading:false })
function selectGroup(grp) { grp.grades.forEach(g => { if (!batchClass.grades.includes(g)) batchClass.grades.push(g) }) }
function deselectGroup(grp) { batchClass.grades = batchClass.grades.filter(g => !grp.grades.includes(g)) }
const batchUser = reactive({ role:'TEACHER', count:10, prefix:'', password:'123456', loading:false })

async function doBatchCreateClass() {
  if (batchClass.grades.length === 0) { ElMessage.warning('请选择年级'); return }
  batchClass.loading = true
  try {
    const res = await request.post('/classes/batch', {
      grades: batchClass.grades,
      classFrom: batchClass.from,
      classTo: batchClass.to,
      defaultPwd: batchClass.defaultPwd || '123456'
    })
    ElMessage.success(`班级 ${res.createdClasses} 个 + 班主任 ${res.createdTeachers} 个，跳过 ${res.skipped} 个`)
  } catch(e) { console.error(e) } finally { batchClass.loading = false }
}

async function doBatchCreateUser() {
  if (!batchUser.prefix) { ElMessage.warning('请输入用户名前缀'); return }
  batchUser.loading = true
  try {
    const users = []
    for (let i = 1; i <= batchUser.count; i++) {
      users.push({
        username: batchUser.prefix + String(i).padStart(2, '0'),
        realName: batchUser.prefix + String(i).padStart(2, '0'),
        role: batchUser.role,
        password: batchUser.password || '123456'
      })
    }
    const res = await request.post('/system/users/batch', { users })
    ElMessage.success(`用户批量创建完成：新增 ${res.created} 个，跳过 ${res.skipped} 个`)
    fetchUserList()
  } catch(e) { console.error(e) } finally { batchUser.loading = false }
}

function roleLabel(role) {
  const m = { ADMIN:'管理员', TEACHER:'体育老师', CLASS_TEACHER:'班主任', STUDENT:'学生' }
  return m[role] || role
}

// ---- fetch ----
async function fetchSettings() {
  loading.value = true
  try {
    const res = await request.get('/system/config')
    if (res) {
      if (res.name) basicForm.name = res.name
      if (res.location) basicForm.location = res.location
      if (res.organizer) basicForm.organizer = res.organizer
      if (res.status) basicForm.status = res.status
      if (res.scoringRules) Object.assign(scoringForm, res.scoringRules)
      if (res.recordBonus!==undefined) recordBonus.value = res.recordBonus
      if (res.participationPoints!==undefined) participationPoints.value = res.participationPoints
      if (res.maxEventsPerAthlete!==undefined) maxEventsPerAthlete.value = parseInt(res.maxEventsPerAthlete)||3
      if (res.maxAthletesPerEvent!==undefined) maxAthletesPerEvent.value = parseInt(res.maxAthletesPerEvent)||3
    }
  } catch(e) { console.error(e) }
  finally { loading.value = false }
}

async function fetchGradeList() {
  try {
    const res = await request.get('/system/grades')
    gradeList.value = Array.isArray(res) ? res : []
  } catch(e) { console.error(e) }
}

async function fetchUserList() {
  try {
    const res = await request.get('/system/users')
    userList.value = Array.isArray(res) ? res : (res?.records || [])
  } catch(e) { console.error(e) }
}

// ---- save basic / scoring ----
async function saveBasic() {
  loading.value = true
  try {
    await request.put('/system/config/basic', basicForm)
    ElMessage.success('基本设置保存成功')
  } catch(e) { console.error(e) }
  finally { loading.value = false }
}

async function saveScoring() {
  loading.value = true
  try {
    await request.put('/system/config/scoring', { scoringRules:{...scoringForm}, recordBonus:recordBonus.value, participationPoints:participationPoints.value, maxEventsPerAthlete:maxEventsPerAthlete.value, maxAthletesPerEvent:maxAthletesPerEvent.value })
    ElMessage.success('积分规则与报名限制保存成功')
  } catch(e) { console.error(e) }
  finally { loading.value = false }
}

// ---- grades ----
function openAddGrade() {
  editingGrade.value = null
  gradeForm.name = ''
  gradeForm.sortOrder = gradeList.value.length + 1
  showGradeDialog.value = true
}
function openEditGrade(row) {
  editingGrade.value = row
  gradeForm.name = row.name
  gradeForm.sortOrder = row.sortOrder
  showGradeDialog.value = true
}
async function saveGrade() {
  try {
    if (editingGrade.value) {
      await request.put('/system/grades/' + editingGrade.value.id, { name:gradeForm.name, sortOrder:gradeForm.sortOrder })
      ElMessage.success('年级更新成功')
    } else {
      await request.post('/system/grades', { name:gradeForm.name, sortOrder:gradeForm.sortOrder })
      ElMessage.success('年级添加成功')
    }
    showGradeDialog.value = false
    fetchGradeList()
  } catch(e) { console.error(e) }
}
async function deleteGradeItem(row) {
  try {
    await ElMessageBox.confirm('确定删除「'+row.name+'」吗？', '提示', { type:'warning' })
    await request.delete('/system/grades/' + row.id)
    ElMessage.success('删除成功')
    fetchGradeList()
  } catch(e) { if(e!=='cancel') console.error(e) }
}

// ---- users ----
function openAddUser() {
  editingUser.value = null
  Object.assign(userForm, { username:'', realName:'', role:'TEACHER', password:'', phone:'' })
  showUserDialog.value = true
}
function editUser(row) {
  editingUser.value = row
  Object.assign(userForm, { username:row.username, realName:row.realName||'', role:row.role, phone:row.phone||'', password:'' })
  showUserDialog.value = true
}
async function saveUser() {
  try {
    if (editingUser.value) {
      await request.put('/system/users/' + editingUser.value.id, { username:userForm.username, realName:userForm.realName, role:userForm.role, phone:userForm.phone })
      ElMessage.success('用户更新成功')
    } else {
      await request.post('/system/users', { username:userForm.username, realName:userForm.realName, role:userForm.role, password:userForm.password||'123456', phone:userForm.phone })
      ElMessage.success('用户创建成功')
    }
    showUserDialog.value = false
    editingUser.value = null
    fetchUserList()
  } catch(e) { console.error(e) }
}
async function deleteUser(row) {
  try {
    await ElMessageBox.confirm('确定删除用户「'+row.username+'」吗？', '提示', { type:'warning' })
    await request.delete('/system/users/' + row.id)
    ElMessage.success('删除成功')
    fetchUserList()
  } catch(e) { if(e!=='cancel') console.error(e) }
}
async function resetPassword(row) {
  try {
    await ElMessageBox.confirm('确定重置「'+row.username+'」的密码为 123456 吗？', '提示', { type:'warning' })
    await request.put('/system/users/' + row.id + '/reset-password')
    ElMessage.success('密码已重置为 123456')
  } catch(e) { if(e!=='cancel') console.error(e) }
}
function onUserImportSuccess(res) {
  if (res && res.code === 200) {
    const d = res.data
    ElMessage.success('导入完成：成功 ' + (d.success || 0) + ' 条，失败 ' + (d.failed || 0) + ' 条')
    fetchUserList()
  }
}
function onUserImportError() {
  ElMessage.error('导入失败，请检查文件格式')
}
function downloadUserTemplate() {
  window.open('/api/system/users/template', '_blank')
}

onMounted(() => {
  fetchSettings()
  fetchGradeList()
  fetchUserList()
  fetchClassList()
})
</script>

<style scoped>
.settings-page { height:100%; overflow:hidden; }
.settings-page :deep(.el-tabs) { height:100%; }
.settings-page :deep(.el-tabs__content) { padding-left:20px; height:100%; overflow-y:auto; }
.settings-page :deep(.el-tabs__header) { margin-right:8px; }
.card-header { display:flex; justify-content:space-between; align-items:center; }
.header-actions { display:flex; align-items:center; flex-wrap:wrap; gap:4px; }
@media(max-width:768px) {
  .settings-page { height:auto; overflow:visible; }
  .settings-page :deep(.el-tabs) { height:auto; }
  .settings-page :deep(.el-tabs__content) { padding-left:0; padding-top:12px; height:auto; overflow:visible; }
  .settings-page :deep(.el-tabs__header) { margin-right:0; margin-bottom:8px; }
}
</style>
