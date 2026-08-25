<template>
  <div class="ct-athletes" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>🏃 本班运动员 (共 {{ total }} 人)</span>
          <div>
            <el-button type="success" @click="openAdd">
              <el-icon><Plus /></el-icon> 手动添加
            </el-button>
            <el-upload :action="uploadUrl" :headers="uploadHeaders" :show-file-list="false" accept=".xlsx,.xls" :on-success="onImportSuccess" :before-upload="beforeUpload" style="display:inline-block;margin-left:8px">
              <el-button type="primary"><el-icon><Upload /></el-icon> Excel导入</el-button>
            </el-upload>
            <el-button @click="downloadTemplate" plain style="margin-left:8px"><el-icon><DocumentCopy /></el-icon> 下载模板</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tableData" border stripe>
        <el-table-column prop="name" label="姓名" /><el-table-column prop="gender" label="性别" width="60"><template #default="{row}"><el-tag :type="row.gender==='M'?'primary':'danger'" size="small">{{row.gender==='M'?'男':row.gender==='F'?'女':row.gender}}</el-tag></template></el-table-column>
        <el-table-column prop="studentNo" label="学号" width="120" /><el-table-column prop="number" label="号码布" width="100" />
        <el-table-column prop="grade" label="年级" width="80" /><el-table-column label="状态" width="80"><template #default="{row}"><el-tag :type="row.status==='normal'?'success':'warning'" size="small">{{row.status==='normal'?'正常':row.status}}</el-tag></template></el-table-column>
      </el-table>
      <div class="pagination-wrap" style="margin-top:12px"><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" layout="total,prev,pager,next" @current-change="fetchData"/></div>
    </el-card>

    <!-- 手动添加对话框 -->
    <el-dialog v-model="showAdd" title="添加运动员" width="400px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名" required><el-input v-model="form.name" placeholder="姓名"/></el-form-item>
        <el-form-item label="性别" required>
          <el-select v-model="form.gender" style="width:100%"><el-option label="男" value="M"/><el-option label="女" value="F"/></el-select>
        </el-form-item>
        <el-form-item label="学号" required><el-input v-model="form.studentId" placeholder="学号（也是登录账号）"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="showAdd=false">取消</el-button><el-button type="primary" @click="doAdd" :loading="adding">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, DocumentCopy, Plus } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { useAuthStore } from '@/stores/auth'

const authStore=useAuthStore();const loading=ref(false);const tableData=ref([])
const page=ref(1);const size=ref(50);const total=ref(0);const showAdd=ref(false);const adding=ref(false)
const form=reactive({name:'',gender:'M',studentId:''})
const uploadUrl=apiBase()+'/class-teacher/import-roster';const uploadHeaders=computed(()=>({Authorization:'Bearer '+authStore.token}))

function beforeUpload(f){const e=f.name.endsWith('.xlsx')||f.name.endsWith('.xls');if(!e)ElMessage.error('请上传.xlsx/.xls');return e}
function onImportSuccess(r){if(r?.code===200){ElMessage.success(`导入：学生${r.data.createdUsers}个，运动员${r.data.createdAthletes}个`);fetchData()}else ElMessage.error(r?.message||'失败')}
function downloadTemplate(){const t='学号,姓名,性别\n2024001,张三,男\n2024002,李四,女\n';const b=new Blob(['\uFEFF'+t],{type:'text/csv'});const u=URL.createObjectURL(b);const a=document.createElement('a');a.href=u;a.download='名单模板.csv';a.click();URL.revokeObjectURL(u)}

function openAdd(){form.name='';form.gender='M';form.studentId='';showAdd.value=true}
async function doAdd(){
  if(!form.name||!form.studentId){ElMessage.warning('请填写完整');return}
  adding.value=true
  try{
    // 先导入单个学生（复用 import-roster 逻辑：通过创建临时 CSV）
    const csv=`学号,姓名,性别\n${form.studentId},${form.name},${form.gender==='M'?'男':'女'}\n`
    const blob=new Blob(['\uFEFF'+csv],{type:'text/csv'})
    const fd=new FormData();fd.append('file',blob,'add.csv')
    await request.post('/class-teacher/import-roster',fd,{headers:{'Content-Type':'multipart/form-data'}})
    ElMessage.success('添加成功');showAdd.value=false;fetchData()
  }catch(e){console.error(e)}
  finally{adding.value=false}
}

async function fetchData(){loading.value=true;try{const r=await request.get('/class-teacher/athletes',{params:{page:page.value,size:size.value}});tableData.value=r.records||[];total.value=r.total||0}catch(e){console.error(e)}finally{loading.value=false}}
onMounted(()=>fetchData())
</script>

<style scoped>
.ct-athletes{display:flex;flex-direction:column}.card-header{display:flex;justify-content:space-between;align-items:center}
</style>
