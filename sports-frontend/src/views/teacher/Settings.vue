<template>
  <div class="settings-page" v-loading="loading">
    <!-- 页面头 -->
    <div class="pg-head rise-in">
      <div class="pg-titles">
        <span class="pg-ico">⚙️</span>
        <div>
          <h3 class="pg-title">系统设置</h3>
          <p class="pg-desc">基本信息 · 积分规则 · 号码簿 · 年级管理 · 编排规则 · 数据库迁移 · 用户/批量创建（管理员）</p>
        </div>
      </div>
      <div class="pg-actions">
        <span class="chip" style="background:#f0fdf4;color:#15803d">配置即保存，所见即所得</span>
      </div>
    </div>
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

            <el-divider content-position="left">🌐 服务运行</el-divider>
            <el-form-item label="服务端口">
              <el-input-number v-model="appConfig.port" :min="1" :max="65535" controls-position="right" style="width:200px" />
              <span class="rule-desc">自定义应用访问端口，保存后重启生效（当前端口 {{ currentPort }}）</span>
            </el-form-item>
            <el-form-item label="访问地址">
              <el-input :model-value="'http://localhost:' + appConfig.port" disabled style="max-width:300px" />
            </el-form-item>

            <el-form-item>
              <el-button type="primary" @click="saveBasic">保存设置</el-button>
              <el-button type="warning" plain @click="saveAppConfig">保存端口配置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- Scoring Rules -->
      <el-tab-pane label="积分规则" name="scoring">
        <el-card shadow="never">
          <template #header><span>积分规则设置（完全自定义）</span></template>
          <el-form label-width="120px" style="max-width: 560px">
            <div class="section-title">🏅 名次积分</div>
            <el-form-item v-for="(_, rank) in scoringForm" :key="rank" :label="'第 '+(Number(rank)+1)+' 名积分'">
              <el-input-number v-model="scoringForm[rank]" :min="0" :max="100" />
            </el-form-item>

            <el-divider />
            <div class="section-title">⚖️ 特殊规则</div>
            <el-form-item label="成绩并列处理">
              <el-select v-model="tieHandling" style="width:200px">
                <el-option label="同名次并列" value="same_rank" />
                <el-option label="顺延名次" value="sequential" />
              </el-select>
            </el-form-item>
            <el-form-item label="破纪录加分">
              <el-switch v-model="recordBonusEnabled" />
              <el-input-number v-if="recordBonusEnabled" v-model="recordBonus" :min="0" :max="50" style="margin-left:12px" />
              <span v-if="recordBonusEnabled" style="margin-left:8px;color:#909399">分</span>
            </el-form-item>
            <el-form-item label="参与基础分">
              <el-switch v-model="participationEnabled" />
              <el-input-number v-if="participationEnabled" v-model="participationPoints" :min="0" :max="20" style="margin-left:12px" />
              <span v-if="participationEnabled" style="margin-left:8px;color:#909399">分</span>
            </el-form-item>
            <el-form-item label="接力积分倍数">
              <el-input-number v-model="relayMultiplier" :min="1" :max="5" :step="0.5" />
            </el-form-item>
            <el-form-item label="团体总分口径">
              <el-select v-model="teamScoreType" style="width:200px">
                <el-option label="按班级" value="class" />
                <el-option label="按年级" value="grade" />
              </el-select>
            </el-form-item>
            <el-form-item label="团体总分排序">
              <el-select v-model="teamScoreSort" style="width:200px">
                <el-option label="总分优先" value="total_score" />
                <el-option label="金牌优先" value="gold_first" />
              </el-select>
            </el-form-item>

            <el-divider />
            <div class="section-title">📋 报名限制</div>
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

      <!-- Number Rule -->
      <el-tab-pane label="号码簿规则" name="number">
        <el-card shadow="never">
          <template #header><span>号码簿生成规则（完全自定义）</span></template>
          <el-form label-width="140px" style="max-width: 760px">
            <el-form-item label="号码模板">
              <el-input v-model="numberRuleForm.template" placeholder="如 {grade}{class}{seq:02d}" style="max-width:380px" @input="previewNumber" />
            </el-form-item>
            <el-form-item label="可用变量">
              <div class="var-ref">
                <el-tag v-for="v in numberVars" :key="v.k" size="small" class="var-tag" type="info" @click="insertVar(v.k)">
                  {{ v.k }} · {{ v.label }}
                </el-tag>
              </div>
            </el-form-item>
            <el-form-item label="学校代码">
              <el-input v-model="numberRuleForm.school_code" style="width:120px" @input="previewNumber" />
            </el-form-item>
            <el-form-item label="自动补零">
              <el-switch v-model="numberRuleForm.auto_pad_zero" @change="previewNumber" />
            </el-form-item>
            <el-form-item label="自动提取班级序号">
              <el-switch v-model="numberRuleForm.auto_extract_class_number" @change="previewNumber" />
            </el-form-item>
            <el-form-item label="号码全局唯一">
              <el-switch v-model="numberRuleForm.unique_global" />
            </el-form-item>
            <el-form-item label="允许手动编辑">
              <el-switch v-model="numberRuleForm.allow_manual_edit" />
            </el-form-item>

            <el-divider content-position="left">年级编号映射</el-divider>
            <el-form-item label="年级映射">
              <div style="width:100%">
                <div v-for="(g, idx) in numberRuleForm.gradeMapping" :key="idx" class="mapping-row">
                  <el-input v-model="g.name" placeholder="年级名称" style="width:170px" @input="previewNumber" />
                  <span class="arrow">→</span>
                  <el-input v-model="g.code" placeholder="编号" style="width:110px" @input="previewNumber" />
                  <el-button link type="danger" :icon="Delete" @click="removeGradeMapping(idx)" />
                </div>
                <el-button size="small" :icon="Plus" @click="addGradeMapping">添加映射</el-button>
              </div>
            </el-form-item>

            <el-divider content-position="left">实时预览</el-divider>
            <el-form-item label="预览年级">
              <el-select v-model="numberRuleForm.previewGrade" style="width:170px" @change="previewNumber">
                <el-option v-for="g in numberRuleForm.gradeMapping" :key="g.name" :label="g.name" :value="g.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="预览班级">
              <el-input v-model="numberRuleForm.previewClass" style="width:170px" @input="previewNumber" />
            </el-form-item>
            <el-form-item label="预览序号">
              <el-input-number v-model="numberRuleForm.previewSeq" :min="1" :max="999" @change="previewNumber" />
            </el-form-item>
            <el-form-item label="生成结果">
              <el-input :model-value="numberPreview" disabled style="max-width:260px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveNumberRule">保存号码簿规则</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" style="margin-top:12px">
          <template #header><span>号码簿 · 按名单顺序重排</span></template>
          <el-alert type="info" :closable="false" style="margin-bottom:12px"
            title="按「年级（系统设置顺序）→ 班级序号 → 名单（导入顺序）」为运动员重新编号；同一班级内序号从 1 连续递增，号码仍套用上方模板。" />
          <el-form label-width="140px" style="max-width: 760px">
            <el-form-item label="范围年级">
              <el-select v-model="reassignGrade" clearable placeholder="全部年级" style="width:220px">
                <el-option v-for="g in reassignGradeOptions" :key="g" :label="g" :value="g" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="warning" :loading="reassigning" @click="doReassignNumberBook">
                生成 / 重排号码簿
              </el-button>
              <span class="form-tip" style="margin-left:10px">重排会覆盖现有号码，请先保存上方模板</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- Arrange Rule -->
      <el-tab-pane label="编排规则" name="arrange">
        <el-card shadow="never">
          <template #header><span>编排规则（软约束与算法参数）</span></template>
          <el-form label-width="170px" style="max-width: 640px">
            <div class="section-title">🎯 软约束</div>
            <el-form-item label="同班尽量不同组">
              <el-switch v-model="arrangeRuleForm.soft.prefer_diff_heat" />
            </el-form-item>
            <el-form-item label="同班尽量不同道">
              <el-switch v-model="arrangeRuleForm.soft.prefer_diff_lane" />
            </el-form-item>
            <el-form-item label="禁止同班同组">
              <el-switch v-model="arrangeRuleForm.soft.ban_same_class_same_lane" />
            </el-form-item>
            <el-form-item label="同年级打散">
              <el-switch v-model="arrangeRuleForm.soft.scramble_across_classes" />
            </el-form-item>
            <el-form-item label="成绩优秀者居中">
              <el-switch v-model="arrangeRuleForm.soft.center_best_athletes" />
            </el-form-item>
            <el-form-item label="同班同组最多人数">
              <el-input-number v-model="arrangeRuleForm.soft.same_class_max_per_heat" :min="0" :max="20" />
              <span class="rule-desc">0 表示不限制</span>
            </el-form-item>

            <el-divider content-position="left">⚙️ 算法参数</el-divider>
            <el-form-item label="最大尝试次数">
              <el-input-number v-model="arrangeRuleForm.params.max_attempts" :min="100" :max="100000" :step="100" />
            </el-form-item>
            <el-form-item label="超时时间(秒)">
              <el-input-number v-model="arrangeRuleForm.params.timeout_seconds" :min="5" :max="120" />
            </el-form-item>
            <el-form-item label="局部优化轮数">
              <el-input-number v-model="arrangeRuleForm.params.optimization_rounds" :min="1" :max="20" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveArrangeRule">保存编排规则</el-button>
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

      <!-- 数据库迁移（仅管理员，WP/Discuz 风格向导） -->
      <el-tab-pane v-if="authStore.isAdmin" label="数据库迁移" name="database">
        <el-card shadow="never">
          <template #header><span>数据库迁移（WordPress / Discuz 风格向导）</span></template>

          <!-- 当前数据库信息 -->
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:20px">
            <template #title>
              当前数据库：<b>{{ currentDb.type || '加载中…' }}</b>
              <span v-if="currentDb.url"> · {{ currentDb.url }}</span>
              <span v-if="currentDb.tableCount !== undefined"> · {{ currentDb.tableCount }} 张表</span>
              <el-tag v-if="currentDb.hasExternalConfig" type="warning" size="small" style="margin-left:8px">已配置外部连接（重启后生效）</el-tag>
            </template>
          </el-alert>

          <el-steps :active="step" finish-status="success" align-center style="margin-bottom:28px">
            <el-step title="选择目标" />
            <el-step title="连接参数" />
            <el-step title="测试连接" />
            <el-step title="执行迁移" />
          </el-steps>

          <!-- Step 0：选择目标数据库类型 -->
          <div v-if="step === 0">
            <div class="target-cards">
              <div v-for="t in targetTypes" :key="t.type" class="target-card"
                   :class="{ on: targetForm.type === t.type }" @click="targetForm.type = t.type">
                <div class="target-name">{{ t.label }}</div>
                <div class="target-desc">{{ t.desc }}</div>
              </div>
            </div>
            <el-button type="primary" @click="step = 1">下一步</el-button>
          </div>

          <!-- Step 1：填写连接参数 -->
          <el-form v-if="step === 1" :model="targetForm" label-width="120px" style="max-width:520px">
            <template v-if="targetForm.type === 'mysql'">
              <el-form-item label="主机地址">
                <el-input v-model="targetForm.host" placeholder="localhost / 127.0.0.1" />
              </el-form-item>
              <el-form-item label="端口">
                <el-input v-model="targetForm.port" placeholder="3306" />
              </el-form-item>
              <el-form-item label="数据库名">
                <el-input v-model="targetForm.database" placeholder="sports" />
              </el-form-item>
              <el-form-item label="用户名">
                <el-input v-model="targetForm.username" placeholder="root" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="targetForm.password" type="password" show-password placeholder="数据库密码" />
              </el-form-item>
            </template>
            <template v-else-if="targetForm.type === 'sqlite'">
              <el-form-item label="数据库文件">
                <el-input v-model="targetForm.file" placeholder="./sports_meet.db" />
              </el-form-item>
              <div class="rule-desc">SQLite 单文件数据库，无需服务器连接，填写文件路径即可。</div>
            </template>
            <div style="margin-top:8px">
              <el-button @click="step = 0">上一步</el-button>
              <el-button type="primary" :loading="testing" @click="doTestConnection">测试连接</el-button>
            </div>
          </el-form>

          <!-- Step 2：测试连接结果 -->
          <div v-if="step === 2">
            <el-result :icon="testResult.ok ? 'success' : 'error'"
                       :title="testResult.ok ? '连接成功' : '连接失败'"
                       :sub-title="testResult.message">
              <template #extra>
                <el-button @click="step = 1">返回修改</el-button>
                <el-button v-if="testResult.ok" type="warning" @click="step = 3">继续迁移</el-button>
              </template>
            </el-result>
          </div>

          <!-- Step 3：执行迁移 -->
          <div v-if="step === 3">
            <el-alert type="warning" :closable="false" show-icon
              title="迁移前将自动备份源数据库；迁移过程只读源库、写入目标库，失败可自动回滚。迁移完成后需重启应用以切换连接。"
              style="margin-bottom:16px" />

            <div v-if="!migrating">
              <el-button type="danger" :loading="startingMigration" @click="startMigration">开始迁移</el-button>
              <el-button @click="step = 0">取消</el-button>
            </div>
            <div v-else>
              <el-progress :percentage="migration.progress || 0"
                           :status="migration.status === 'failed' ? 'exception' : (migration.status === 'completed' ? 'success' : '')" />
              <div style="margin:12px 0;color:#606266">当前步骤：{{ migration.step || '准备中…' }}</div>
              <div class="migration-log" v-if="migration.logs && migration.logs.length">
                <div v-for="(l, i) in migration.logs" :key="i" class="log-line">{{ l }}</div>
              </div>
              <el-alert v-if="migration.status === 'completed'" type="success" :closable="false" show-icon
                        :title="migration.message" style="margin-top:12px">
                <template #default>
                  <p style="margin:0">{{ migration.message }}</p>
                  <p style="margin:6px 0 0;font-size:12px">请重启应用以切换到新数据库（可运行 <code>.\start.ps1</code> 重启）。</p>
                </template>
              </el-alert>
              <el-alert v-if="migration.status === 'failed'" type="error" :closable="false" show-icon
                        :title="'迁移失败：' + (migration.message || '未知错误')" style="margin-top:12px" />
            </div>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- 数据库备份（仅管理员） -->
      <el-tab-pane v-if="authStore.isAdmin" label="数据库备份" name="backup">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>数据库备份（支持定期自动备份）</span>
              <el-button type="primary" :loading="backingUp" :icon="Upload" @click="doBackupNow">立即备份</el-button>
            </div>
          </template>
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px"
            title="系统每天凌晨 3 点自动备份，备份文件保存在 data/backup 目录，保留最近 30 天。" />
          <el-table :data="backupList" v-loading="backupLoading" border stripe>
            <el-table-column prop="fileName" label="文件名" min-width="220" />
            <el-table-column prop="sizeLabel" label="大小" width="100" align="center" />
            <el-table-column prop="time" label="备份时间" width="180" align="center" />
            <el-table-column label="操作" width="160" align="center">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="downloadBackup(row)">下载</el-button>
                <el-button type="danger" link size="small" @click="deleteBackup(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!backupLoading && !backupList.length" description="暂无备份" :image-size="80" />
        </el-card>
      </el-tab-pane>

      <!-- 健康检查（仅管理员） -->
      <el-tab-pane v-if="authStore.isAdmin" label="健康检查" name="health">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>系统健康检查</span>
              <el-button :icon="Refresh" @click="fetchHealth" :loading="healthLoading">刷新</el-button>
            </div>
          </template>
          <div v-loading="healthLoading">
            <el-result v-if="health.status === 'DOWN'" icon="error" title="系统异常"
                       sub-title="数据库连接失败，请检查配置" />
            <el-row v-else :gutter="16">
              <el-col :span="12">
                <el-card shadow="never" class="health-card">
                  <template #header><span>🗄️ 数据库</span></template>
                  <el-descriptions :column="1" size="small">
                    <el-descriptions-item label="状态">
                      <el-tag :type="health.database?.status === 'UP' ? 'success' : 'danger'" size="small">
                        {{ health.database?.status || '—' }}
                      </el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="类型">{{ health.database?.type || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="产品">{{ health.database?.product || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="表数量">{{ health.database?.tableCount ?? '—' }}</el-descriptions-item>
                  </el-descriptions>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="never" class="health-card">
                  <template #header><span>💾 磁盘 / 内存</span></template>
                  <el-descriptions :column="1" size="small">
                    <el-descriptions-item label="磁盘可用">{{ health.disk?.freeLabel || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="磁盘总量">{{ health.disk?.totalLabel || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="内存已用">{{ health.memory?.usedLabel || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="内存上限">{{ health.memory?.maxLabel || '—' }}</el-descriptions-item>
                  </el-descriptions>
                </el-card>
              </el-col>
            </el-row>
            <el-row :gutter="16" style="margin-top:16px">
              <el-col :span="24">
                <el-card shadow="never" class="health-card">
                  <template #header><span>⚙️ 运行环境</span></template>
                  <el-descriptions :column="3" size="small">
                    <el-descriptions-item label="Java">{{ health.javaVersion || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="操作系统">{{ health.os || '—' }}</el-descriptions-item>
                    <el-descriptions-item label="检查时间">{{ health.time || '—' }}</el-descriptions-item>
                  </el-descriptions>
                </el-card>
              </el-col>
            </el-row>
          </div>
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
import { Upload, DocumentCopy, Plus, Delete, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { apiBase } from '@/utils/base'
import { downloadApi } from '@/utils/download'
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
const appConfig = reactive({ port: 8080 })
const currentPort = ref(window.location.port || '8080')
const scoringForm = reactive({ '0':9,'1':7,'2':6,'3':5,'4':4,'5':3,'6':2,'7':1 })
const recordBonus = ref(5)
const participationPoints = ref(1)
const maxEventsPerAthlete = ref(3)
const maxAthletesPerEvent = ref(3)
const tieHandling = ref('same_rank')
const recordBonusEnabled = ref(false)
const participationEnabled = ref(false)
const relayMultiplier = ref(2)
const teamScoreType = ref('class')
const teamScoreSort = ref('total_score')

// 号码簿规则
const numberVars = [
  { k: '{grade}', label: '年级编号' },
  { k: '{grade_name}', label: '年级名称' },
  { k: '{class}', label: '班级编号' },
  { k: '{class_name}', label: '班级名称' },
  { k: '{seq}', label: '序号' },
  { k: '{seq:02d}', label: '2位序号' },
  { k: '{seq:03d}', label: '3位序号' },
  { k: '{gender}', label: '性别代码' },
  { k: '{gender_ch}', label: '性别中文' },
  { k: '{year}', label: '年份后两位' },
  { k: '{school_code}', label: '学校代码' }
]
const numberRuleForm = reactive({
  template: '{grade}{class}{seq:02d}',
  school_code: '01',
  auto_pad_zero: true,
  auto_extract_class_number: true,
  unique_global: true,
  allow_manual_edit: true,
  gradeMapping: [],
  previewGrade: '高一年级',
  previewClass: '高一1班',
  previewSeq: 1
})
const numberPreview = ref('')

// 编排规则
const arrangeRuleForm = reactive({
  soft: {
    prefer_diff_heat: true,
    prefer_diff_lane: true,
    ban_same_class_same_lane: true,
    scramble_across_classes: false,
    center_best_athletes: false,
    same_class_max_per_heat: 0
  },
  params: {
    max_attempts: 1000,
    timeout_seconds: 30,
    optimization_rounds: 3
  }
})

// ============ 数据库迁移（WP/Discuz 风格向导） ============
const currentDb = ref({})
const targetTypes = ref([])
const step = ref(0)
const targetForm = reactive({
  type: 'mysql', host: 'localhost', port: '3306',
  database: 'sports', username: 'root', password: '', file: './sports_meet.db'
})
const testing = ref(false)
const testResult = reactive({ ok: false, message: '' })
const startingMigration = ref(false)
const migrating = ref(false)
const migration = reactive({ status: '', progress: 0, step: '', message: '', logs: [] })
let progressTimer = null

async function fetchDbMigrationInfo() {
  try {
    const [cur, targets] = await Promise.all([
      request.get('/db-migration/current'),
      request.get('/db-migration/targets')
    ])
    currentDb.value = cur || {}
    targetTypes.value = targets || []
    if (targetTypes.value.length && !targetTypes.value.some(t => t.type === targetForm.type)) {
      targetForm.type = targetTypes.value[0].type
    }
  } catch (e) { console.error(e) }
}

async function doTestConnection() {
  testing.value = true
  try {
    const res = await request.post('/db-migration/test', { ...targetForm })
    testResult.ok = !!res.ok
    testResult.message = res.message || ''
    step.value = 2
  } catch (e) { console.error(e) } finally { testing.value = false }
}

async function startMigration() {
  startingMigration.value = true
  try {
    const res = await request.post('/db-migration/start', { ...targetForm })
    migrating.value = true
    migration.status = 'running'
    migration.progress = 0
    migration.logs = []
    migration.message = ''
    pollProgress(res.taskId)
  } catch (e) { console.error(e) } finally { startingMigration.value = false }
}

function pollProgress(taskId) {
  if (progressTimer) clearInterval(progressTimer)
  progressTimer = setInterval(async () => {
    try {
      const res = await request.get('/db-migration/progress/' + taskId)
      migration.status = res.status
      migration.progress = res.progress || 0
      migration.step = res.step || ''
      migration.message = res.message || ''
      migration.logs = res.logs || []
      if (res.status === 'completed' || res.status === 'failed' || res.status === 'not_found') {
        clearInterval(progressTimer)
        progressTimer = null
        if (res.status === 'completed') ElMessage.success('数据库迁移完成，请重启应用切换连接')
        else if (res.status === 'failed') ElMessage.error('数据库迁移失败')
      }
    } catch (e) { /* 轮询失败忽略 */ }
  }, 1000)
}

// ============ 数据库备份 ============
const backupList = ref([])
const backupLoading = ref(false)
const backingUp = ref(false)

async function fetchBackupList() {
  backupLoading.value = true
  try {
    const res = await request.get('/backup/list')
    backupList.value = Array.isArray(res) ? res : []
  } catch (e) { console.error(e); backupList.value = [] }
  finally { backupLoading.value = false }
}

async function doBackupNow() {
  backingUp.value = true
  try {
    await request.post('/backup/now')
    ElMessage.success('备份完成')
    fetchBackupList()
  } catch (e) { console.error(e) }
  finally { backingUp.value = false }
}

async function deleteBackup(row) {
  try {
    await ElMessageBox.confirm(`确定删除备份「${row.fileName}」吗？`, '提示', { type: 'warning' })
    await request.delete('/backup/' + encodeURIComponent(row.fileName))
    ElMessage.success('删除成功')
    fetchBackupList()
  } catch (e) { if (e !== 'cancel') console.error(e) }
}

async function downloadBackup(row) {
  try {
    await downloadApi('/backup/download/' + encodeURIComponent(row.fileName), row.fileName || 'backup.zip')
    ElMessage.success('备份下载中')
  } catch (e) { ElMessage.error(e?.message || '备份下载失败，请重新登录后再试') }
}

// ============ 健康检查 ============
const health = ref({})
const healthLoading = ref(false)

async function fetchHealth() {
  healthLoading.value = true
  try {
    const res = await request.get('/system/health-detail')
    health.value = res || {}
  } catch (e) { console.error(e) }
  finally { healthLoading.value = false }
}

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

const userImportUrl = apiBase() + '/system/users/import'
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

// ---- 应用运行配置（服务端口） ----
async function fetchAppConfig() {
  try {
    const res = await request.get('/system/app-config')
    if (res && res.port) appConfig.port = Number(res.port) || 8080
  } catch (e) { console.error(e) }
}
async function saveAppConfig() {
  loading.value = true
  try {
    await request.put('/system/app-config', { port: appConfig.port })
    ElMessage.success('端口配置已保存，重启应用后生效')
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

async function saveScoring() {
  loading.value = true
  try {
    const rankScores = {}
    for (let i = 0; i < 8; i++) rankScores[String(i + 1)] = scoringForm[String(i)] || 0
    // 保存完整积分规则（scoring_rule）
    await request.put('/system/scoring-rule', {
      rank_scores: rankScores,
      tie_handling: tieHandling.value,
      record_bonus_enabled: recordBonusEnabled.value,
      record_bonus: recordBonus.value,
      participation_score_enabled: participationEnabled.value,
      participation_score: participationPoints.value,
      relay_multiplier: relayMultiplier.value,
      team_score_type: teamScoreType.value,
      team_score_sort: teamScoreSort.value
    })
    // 兼容旧配置（报名限制等独立 key）
    await request.put('/system/config/scoring', {
      scoringRules: { ...scoringForm },
      recordBonus: recordBonus.value,
      participationPoints: participationPoints.value,
      maxEventsPerAthlete: maxEventsPerAthlete.value,
      maxAthletesPerEvent: maxAthletesPerEvent.value
    })
    ElMessage.success('积分规则与报名限制保存成功')
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

// ---- 号码簿规则 ----
function addGradeMapping() {
  numberRuleForm.gradeMapping.push({ name: '', code: '' })
}
function removeGradeMapping(idx) {
  numberRuleForm.gradeMapping.splice(idx, 1)
  previewNumber()
}
function insertVar(k) {
  numberRuleForm.template += k
  previewNumber()
}
function previewNumber() {
  const tpl = numberRuleForm.template || ''
  const pad = numberRuleForm.auto_pad_zero
  const mapping = {}
  numberRuleForm.gradeMapping.forEach(g => { if (g.name) mapping[g.name] = g.code })
  const grade = numberRuleForm.previewGrade
  const gradeCode = mapping[grade] || '00'
  const classNum = (numberRuleForm.previewClass.match(/(\d+)/g) || []).pop() || '1'
  const seq = numberRuleForm.previewSeq
  const year = String(new Date().getFullYear() % 100)

  const raw = {
    grade: pad ? padNum(gradeCode) : String(gradeCode),
    grade_name: grade,
    class: pad ? padNum(classNum) : String(classNum),
    class_name: numberRuleForm.previewClass,
    seq: pad ? padNum(seq) : String(seq),
    gender: 'M', gender_ch: '男',
    year, school_code: numberRuleForm.school_code
  }
  const num = {
    grade: parseInt(gradeCode) || 0,
    class: parseInt(classNum) || 0,
    seq: parseInt(seq) || 0,
    year: parseInt(year) || 0,
    school_code: parseInt(numberRuleForm.school_code) || 0
  }
  numberPreview.value = tpl.replace(/\{([a-z_]+)(?::(\d+)d)?\}/g, (m, v, w) => {
    if (w && num[v] !== undefined) return String(num[v]).padStart(parseInt(w), '0')
    return raw[v] !== undefined ? raw[v] : ''
  })
}
function padNum(n) {
  return String(n).length < 2 ? '0' + String(n) : String(n)
}
async function fetchNumberRule() {
  try {
    const res = await request.get('/system/number-rule')
    if (res) {
      numberRuleForm.template = res.template || numberRuleForm.template
      numberRuleForm.school_code = res.school_code || '01'
      numberRuleForm.auto_pad_zero = res.auto_pad_zero !== false
      numberRuleForm.auto_extract_class_number = res.auto_extract_class_number !== false
      numberRuleForm.unique_global = res.unique_global !== false
      numberRuleForm.allow_manual_edit = res.allow_manual_edit !== false
      const gm = res.grade_mapping || {}
      const list = Object.entries(gm).map(([name, code]) => ({ name, code: String(code) }))
      numberRuleForm.gradeMapping = list.length ? list : defaultGradeMapping()
      if (!numberRuleForm.gradeMapping.some(g => g.name === numberRuleForm.previewGrade)) {
        numberRuleForm.previewGrade = numberRuleForm.gradeMapping[0]?.name || '高一年级'
      }
      previewNumber()
    }
  } catch (e) { console.error(e) }
}
function defaultGradeMapping() {
  return [['一年级','1'],['二年级','2'],['三年级','3'],['四年级','4'],['五年级','5'],['六年级','6'],
    ['初一年级','7'],['初二年级','8'],['初三年级','9'],['高一年级','10'],['高二年级','11'],['高三年级','12']]
    .map(([name, code]) => ({ name, code }))
}
async function saveNumberRule() {
  loading.value = true
  try {
    const grade_mapping = {}
    numberRuleForm.gradeMapping.forEach(g => { if (g.name) grade_mapping[g.name] = g.code })
    await request.put('/system/number-rule', {
      template: numberRuleForm.template,
      school_code: numberRuleForm.school_code,
      auto_pad_zero: numberRuleForm.auto_pad_zero,
      auto_extract_class_number: numberRuleForm.auto_extract_class_number,
      unique_global: numberRuleForm.unique_global,
      allow_manual_edit: numberRuleForm.allow_manual_edit,
      grade_mapping
    })
    ElMessage.success('号码簿规则保存成功')
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

// ---- 号码簿 · 按名单顺序重排 ----
const reassignGrade = ref('')
const reassigning = ref(false)
const reassignGradeOptions = computed(() => {
  const s = new Set()
  ;(numberRuleForm.gradeMapping || []).forEach(g => { if (g && g.name && g.name.trim()) s.add(g.name.trim()) })
  return [...s]
})
async function doReassignNumberBook() {
  try {
    await ElMessageBox.confirm(
      '将按「年级顺序 → 班级顺序 → 名单顺序」为范围内运动员重新生成号码簿并覆盖现有号码。是否继续？',
      '号码簿重排确认', { confirmButtonText: '开始重排', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  reassigning.value = true
  try {
    const res = await request.post('/system/number-rule/reassign', { grade: reassignGrade.value || '' })
    ElMessage.success(`号码簿重排完成：${res?.totalClasses ?? 0} 个班级、更新 ${res?.updated ?? 0} 人`)
    if (res?.sample && res.sample.length) {
      console.info('号码簿重排样例', res.sample)
    }
  } catch (e) { console.error(e) }
  finally { reassigning.value = false }
}

// ---- 编排规则 ----
async function fetchArrangeRule() {
  try {
    const res = await request.get('/system/arrange-rule')
    if (res) {
      if (res.soft_constraints) Object.assign(arrangeRuleForm.soft, res.soft_constraints)
      if (res.algorithm_params) Object.assign(arrangeRuleForm.params, res.algorithm_params)
    }
  } catch (e) { console.error(e) }
}
async function saveArrangeRule() {
  loading.value = true
  try {
    await request.put('/system/arrange-rule', {
      soft_constraints: { ...arrangeRuleForm.soft },
      algorithm_params: { ...arrangeRuleForm.params }
    })
    ElMessage.success('编排规则保存成功')
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

// ---- 积分规则（高级项）加载 ----
async function fetchScoringRule() {
  try {
    const res = await request.get('/system/scoring-rule')
    if (res) {
      if (res.rank_scores) {
        for (let i = 0; i < 8; i++) {
          const v = res.rank_scores[String(i + 1)]
          if (v !== undefined) scoringForm[String(i)] = Number(v)
        }
      }
      if (res.tie_handling) tieHandling.value = res.tie_handling
      if (res.record_bonus_enabled !== undefined) recordBonusEnabled.value = !!res.record_bonus_enabled
      if (res.record_bonus !== undefined) recordBonus.value = Number(res.record_bonus)
      if (res.participation_score_enabled !== undefined) participationEnabled.value = !!res.participation_score_enabled
      if (res.participation_score !== undefined) participationPoints.value = Number(res.participation_score)
      if (res.relay_multiplier !== undefined) relayMultiplier.value = Number(res.relay_multiplier)
      if (res.team_score_type) teamScoreType.value = res.team_score_type
      if (res.team_score_sort) teamScoreSort.value = res.team_score_sort
    }
  } catch (e) { console.error(e) }
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
  window.open(apiBase() + '/system/users/template', '_blank')
}

onMounted(() => {
  fetchSettings()
  fetchGradeList()
  fetchUserList()
  fetchClassList()
  fetchScoringRule()
  fetchNumberRule()
  fetchArrangeRule()
  fetchAppConfig()
  if (authStore.isAdmin) {
    fetchDbMigrationInfo()
    fetchBackupList()
    fetchHealth()
  }
})

onBeforeUnmount(() => {
  if (progressTimer) clearInterval(progressTimer)
})
</script>

<style scoped>
.settings-page { height:100%; overflow:hidden; }
.settings-page :deep(.el-tabs) { height:100%; }
.settings-page :deep(.el-tabs__content) { padding-left:20px; height:100%; overflow-y:auto; }
.settings-page :deep(.el-tabs__header) { margin-right:8px; }
.card-header { display:flex; justify-content:space-between; align-items:center; }
.header-actions { display:flex; align-items:center; flex-wrap:wrap; gap:4px; }
.section-title { font-size:14px; font-weight:600; color:#303133; margin-bottom:12px; }
.var-ref { display:flex; flex-wrap:wrap; gap:6px; }
.var-tag { cursor:pointer; }
.var-tag:hover { opacity:.8; }
.mapping-row { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
.arrow { color:#909399; }
.rule-desc { font-size:12px; color:#909399; margin-left:8px; }
.target-cards { display:flex; gap:16px; margin-bottom:20px; flex-wrap:wrap; }
.target-card { flex:1; min-width:200px; padding:20px 16px; border:2px solid #e4e7ed; border-radius:12px; cursor:pointer; transition:all .2s; background:#fafafa; }
.target-card:hover { border-color:#93c5fd; background:#f0f7ff; }
.target-card.on { border-color:#409eff; background:#ecf5ff; box-shadow:0 2px 12px rgba(64,158,255,.18); }
.target-name { font-size:16px; font-weight:700; color:#303133; margin-bottom:8px; }
.target-desc { font-size:12px; color:#909399; line-height:1.6; }
.migration-log { max-height:260px; overflow-y:auto; background:#0f172a; color:#a5f3fc; border-radius:8px; padding:12px 14px; font-family:Consolas,Monaco,monospace; font-size:12px; margin-top:12px; }
.log-line { line-height:1.7; white-space:pre-wrap; word-break:break-all; }
@media(max-width:768px) {
  .settings-page { height:auto; overflow:visible; }
  .settings-page :deep(.el-tabs) { height:auto; }
  .settings-page :deep(.el-tabs__content) { padding-left:0; padding-top:12px; height:auto; overflow:visible; }
  .settings-page :deep(.el-tabs__header) { margin-right:0; margin-bottom:8px; }
}
</style>
