<template>
  <div class="setup-root">
    <div class="setup-card">
      <div class="setup-header">
        <div class="brand-mark">🏟️</div>
        <h1 class="setup-title">运动会智能编排系统 · 安装向导</h1>
        <p class="setup-sub">欢迎使用。请在几分钟内完成站点初始化配置。</p>
      </div>

      <el-steps :active="step" align-center finish-status="success" class="setup-steps">
        <el-step title="数据库配置" />
        <el-step title="站点信息" />
        <el-step title="管理员账号" />
      </el-steps>

      <div class="setup-body">
        <!-- Step 1：数据库配置 -->
        <div v-if="step === 0">
          <h3 class="step-title">选择数据库</h3>
          <div class="db-cards">
            <div class="db-card" :class="{ on: dbForm.type === 'sqlite' }" @click="dbForm.type = 'sqlite'">
              <div class="db-card-name">SQLite</div>
              <div class="db-card-desc">零配置单文件数据库，适合本地与中小规模部署，推荐。</div>
              <el-tag v-if="dbForm.type === 'sqlite'" type="success" size="small">已选择</el-tag>
            </div>
            <div class="db-card" :class="{ on: dbForm.type === 'mysql' }" @click="dbForm.type = 'mysql'">
              <div class="db-card-name">MySQL</div>
              <div class="db-card-desc">生产环境推荐，需提供 MySQL 服务器连接信息。</div>
              <el-tag v-if="dbForm.type === 'mysql'" type="success" size="small">已选择</el-tag>
            </div>
          </div>

          <el-form v-if="dbForm.type === 'mysql'" :model="dbForm" label-width="100px" class="db-form">
            <el-form-item label="主机地址"><el-input v-model="dbForm.host" placeholder="localhost" /></el-form-item>
            <el-form-item label="端口"><el-input v-model="dbForm.port" placeholder="3306" /></el-form-item>
            <el-form-item label="数据库名"><el-input v-model="dbForm.database" placeholder="sports" /></el-form-item>
            <el-form-item label="用户名"><el-input v-model="dbForm.username" placeholder="root" /></el-form-item>
            <el-form-item label="密码"><el-input v-model="dbForm.password" type="password" show-password /></el-form-item>
            <el-form-item>
              <el-button :loading="testingDb" @click="testDb">测试连接</el-button>
              <span v-if="dbTestResult" class="db-test-result" :class="dbTestResult.ok ? 'ok' : 'err'">
                {{ dbTestResult.message }}
              </span>
            </el-form-item>
          </el-form>

          <div class="step-actions">
            <el-button type="primary" @click="step = 1">下一步</el-button>
          </div>
        </div>

        <!-- Step 2：站点信息 -->
        <div v-if="step === 1">
          <h3 class="step-title">站点信息</h3>
          <el-form :model="siteForm" label-width="100px">
            <el-form-item label="站点名称" required>
              <el-input v-model="siteForm.siteName" placeholder="第X届田径运动会" />
            </el-form-item>
            <el-form-item label="站点描述">
              <el-input v-model="siteForm.siteDescription" type="textarea" :rows="3"
                        placeholder="简短描述（选填）" />
            </el-form-item>
          </el-form>
          <div class="step-actions">
            <el-button @click="step = 0">上一步</el-button>
            <el-button type="primary" @click="step = 2">下一步</el-button>
          </div>
        </div>

        <!-- Step 3：管理员账号 -->
        <div v-if="step === 2">
          <h3 class="step-title">设置管理员账号</h3>
          <el-form :model="adminForm" label-width="100px">
            <el-form-item label="用户名" required>
              <el-input v-model="adminForm.adminUsername" placeholder="admin" />
            </el-form-item>
            <el-form-item label="密码" required>
              <el-input v-model="adminForm.adminPassword" type="password" show-password placeholder="至少 6 位" />
            </el-form-item>
            <el-form-item label="确认密码" required>
              <el-input v-model="adminForm.adminPassword2" type="password" show-password placeholder="再次输入密码" />
            </el-form-item>
          </el-form>
          <el-alert type="info" :closable="false" show-icon
            title="安装完成后，安装向导将永久锁定，任何人无法再次进入。" style="margin-bottom:16px" />
          <div class="step-actions">
            <el-button @click="step = 1">上一步</el-button>
            <el-button type="primary" :loading="installing" @click="doInstall">立即安装</el-button>
          </div>
        </div>

        <!-- 安装结果 -->
        <div v-if="installed">
          <el-result icon="success" title="安装完成！" :sub-title="installMessage">
            <template #extra>
              <el-button type="primary" @click="goLogin">前往登录</el-button>
              <el-button v-if="needRestart" @click="reloadPage">立即重启应用</el-button>
            </template>
          </el-result>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const router = useRouter()
const step = ref(0)
const testingDb = ref(false)
const installing = ref(false)
const installed = ref(false)
const installMessage = ref('')
const needRestart = ref(false)
const dbTestResult = ref(null)

const dbForm = reactive({
  type: 'sqlite', host: 'localhost', port: '3306',
  database: 'sports', username: 'root', password: '', file: './sports_meet.db'
})
const siteForm = reactive({ siteName: '', siteDescription: '' })
const adminForm = reactive({ adminUsername: 'admin', adminPassword: '', adminPassword2: '' })

onMounted(async () => {
  try {
    const res = await request.get('/setup/status')
    if (res && res.installed) {
      router.replace('/login')
    }
  } catch (e) { /* ignore */ }
})

async function testDb() {
  testingDb.value = true
  dbTestResult.value = null
  try {
    const res = await request.post('/setup/check-db', { ...dbForm })
    dbTestResult.value = res
  } catch (e) {
    dbTestResult.value = { ok: false, message: e.message || '连接失败' }
  } finally {
    testingDb.value = false
  }
}

async function doInstall() {
  if (!siteForm.siteName) { ElMessage.warning('请输入站点名称'); return }
  if (adminForm.adminUsername.length < 3) { ElMessage.warning('用户名至少 3 个字符'); return }
  if (adminForm.adminPassword.length < 6) { ElMessage.warning('密码至少 6 位'); return }
  if (adminForm.adminPassword !== adminForm.adminPassword2) { ElMessage.warning('两次密码不一致'); return }
  if (dbForm.type === 'mysql' && !dbTestResult.value?.ok) {
    ElMessage.warning('请先测试数据库连接'); return
  }

  installing.value = true
  try {
    const res = await request.post('/setup/install', {
      dbType: dbForm.type,
      db: { ...dbForm },
      siteName: siteForm.siteName,
      siteDescription: siteForm.siteDescription,
      adminUsername: adminForm.adminUsername,
      adminPassword: adminForm.adminPassword
    })
    installed.value = true
    installMessage.value = res.message || '安装完成'
    needRestart.value = !!res.needRestart
    // 标记已安装，避免重复进入
    try { localStorage.setItem('setup_done', '1') } catch (e) {}
  } catch (e) {
    ElMessage.error(e.message || '安装失败')
  } finally {
    installing.value = false
  }
}

function goLogin() {
  window.location.href = '/login'
}
function reloadPage() {
  window.location.href = '/'
}
</script>

<style scoped>
.setup-root {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  background:
    radial-gradient(circle at 15% 10%, rgba(59,130,246,0.12), transparent 40%),
    radial-gradient(circle at 85% 90%, rgba(16,185,129,0.10), transparent 40%),
    linear-gradient(160deg, #f8fafc 0%, #eef2ff 55%, #ecfdf5 100%);
}
.setup-card {
  width: 100%;
  max-width: 720px;
  background: #fff;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 20px 50px rgba(30,41,59,0.12);
  padding: 36px 40px 40px;
}
.setup-header { text-align: center; margin-bottom: 24px; }
.brand-mark { font-size: 40px; }
.setup-title { font-size: 22px; font-weight: 700; color: #0f172a; margin: 10px 0 6px; }
.setup-sub { font-size: 13px; color: #64748b; }
.setup-steps { margin-bottom: 28px; }
.step-title { font-size: 16px; font-weight: 600; color: #303133; margin: 0 0 16px; }
.db-cards { display: flex; gap: 14px; margin-bottom: 20px; }
.db-card {
  flex: 1; padding: 18px 16px; border: 2px solid #e2e8f0; border-radius: 12px;
  cursor: pointer; transition: all .2s; position: relative; background: #fafafa;
}
.db-card:hover { border-color: #93c5fd; }
.db-card.on { border-color: #409eff; background: #ecf5ff; box-shadow: 0 2px 12px rgba(64,158,255,.15); }
.db-card .el-tag { position: absolute; top: 12px; right: 12px; }
.db-card-name { font-size: 16px; font-weight: 700; color: #303133; margin-bottom: 6px; }
.db-card-desc { font-size: 12px; color: #909399; line-height: 1.6; }
.db-form { margin-top: 8px; }
.db-test-result { font-size: 13px; margin-left: 10px; }
.db-test-result.ok { color: #16a34a; }
.db-test-result.err { color: #dc2626; }
.step-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 20px; }
@media (max-width: 640px) {
  .setup-card { padding: 24px 18px; }
  .db-cards { flex-direction: column; }
}
</style>
