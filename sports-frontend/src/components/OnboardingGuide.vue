<template>
  <el-dialog
    v-model="visible"
    width="760px"
    top="6vh"
    :close-on-click-modal="false"
    class="guide-dialog"
    append-to-body
    @closed="onClosed"
  >
    <template #header>
      <div class="guide-head">
        <div class="guide-emoji">🧭</div>
        <div>
          <div class="guide-title">新手引导 · 首次部署后这样用</div>
          <div class="guide-sub">跟着下面 10 步，即可完成一届运动会的初始化与智能编排</div>
        </div>
      </div>
    </template>

    <div class="guide-body">
      <div class="guide-steps">
        <el-steps :active="3" align-center finish-status="success" class="guide-psteps">
          <el-step title="基础配置" />
          <el-step title="班级名单" />
          <el-step title="项目报名" />
          <el-step title="编排成绩" />
        </el-steps>
      </div>

      <ul class="guide-list">
        <li v-for="(s, i) in steps" :key="i" class="guide-item">
          <div class="gi-no">{{ i + 1 }}</div>
          <div class="gi-main">
            <div class="gi-title">{{ s.title }}</div>
            <div class="gi-desc">{{ s.desc }}</div>
          </div>
          <el-button size="small" type="primary" plain @click="go(s.to)">去操作 →</el-button>
        </li>
      </ul>

      <el-alert type="info" :closable="false" show-icon class="guide-tip">
        <template #title>提示</template>
        每一步都可后续补做，不必一次性完成；左边菜单的「说明书」随时可查完整操作手册。
        项目字典可在「比赛项目」页<b>导出 JSON</b>（含全部字段与默认值）作为备份或跨机迁移，改完再用<b>导入 JSON</b> 回灌（按 code 覆盖/新增）。
      </el-alert>
    </div>

    <template #footer>
      <div class="guide-foot">
        <el-button text type="primary" @click="openHelp">查看完整说明书</el-button>
        <div class="spacer" />
        <el-checkbox v-model="dontShow" label="不再自动弹出" />
        <el-button @click="visible = false">稍后再说</el-button>
        <el-button type="primary" @click="finish">完成引导</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const router = useRouter()
const visible = ref(props.modelValue)
const dontShow = ref(false)

watch(() => props.modelValue, (v) => { visible.value = v })
watch(visible, (v) => { if (v !== props.modelValue) emit('update:modelValue', v) })

const steps = [
  { title: '配置运动会基础信息', desc: '运动会名称、举办日期、时段（上午/下午）、场地（名称+编码）、年级出场顺序。', to: '/teacher/settings' },
  { title: '创建 / 导入班级', desc: '维护班级、年级、编号，并绑定班主任，班主任端才能看到本班数据。', to: '/teacher/classes' },
  { title: '导入班主任 / 体育老师账号', desc: '管理员在「设置 → 用户管理」批量导入用户名单（角色选班主任 / 体育老师），或在「批量创建」一键生成；「批量创建」顶部还有四类名单导入入口速查。', to: '/teacher/settings?tab=users' },
  { title: '导入运动员（学生）名单', desc: '批量导入学生花名册，自动生成运动员并分配号码簿（支持列映射预览）。', to: '/teacher/athletes' },
  { title: '添加比赛项目（表格2 或 JSON）', desc: '用「表格2」模板导入（Excel/CSV，含顺序号/并发/捆绑组/组次裁判数量/抽签等列）；也可【导出 JSON】拿走全部字段与默认值，编辑后用【导入 JSON】回灌——支持全字段往返与项目字典备份。', to: '/teacher/events' },
  { title: '裁判名单与工作安排', desc: '管理员在「裁判管理」批量导入裁判（专长项目支持 [a,b，c] 列表语法）；裁判是被编排的人力资源、无需登录账号，分配结果在「裁判工作安排」按人汇总查看。', to: '/teacher/referee-board' },
  { title: '导入报名表并审核', desc: '班主任端报名后，在教师端报名管理里批量审核通过/拒绝。', to: '/teacher/registrations' },
  { title: '编排赛程', desc: '设置并数、自定义项目顺序、田赛分组/并行捆绑组，一键自动编排并支持手动微调。', to: '/teacher/schedule' },
  { title: '道次编排（自检 / 抽签 / 两阶段）', desc: '自动分组分道，内置「对抗式自检」——校验不通过会自动换种子重排；可按项目开启随机抽签（组内道次随机）；预赛成绩录入后可一键「重排全部决赛」；支持预留模拟空位与时间。', to: '/teacher/arrange' },
  { title: '录入成绩 → 排名 → 报表', desc: '录入成绩自动计算名次积分，生成合分排行、秩序册（Excel/Word）与数据大屏。', to: '/teacher/scores' }
]

function go(to) {
  visible.value = false
  router.push(to)
}

function openHelp() {
  visible.value = false
  router.push('/teacher/help')
}

function finish() {
  if (dontShow.value) {
    try { localStorage.setItem('sp_guide_done', '1') } catch (e) {}
  }
  visible.value = false
}

function onClosed() {
  // 关闭时若勾选不再弹出，则持久化（与 finish 双保险）
  if (dontShow.value) {
    try { localStorage.setItem('sp_guide_done', '1') } catch (e) {}
  }
}
</script>

<style scoped>
.guide-head { display: flex; align-items: center; gap: 14px; }
.guide-emoji {
  width: 46px; height: 46px; border-radius: 14px; flex-shrink: 0;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  display: inline-flex; align-items: center; justify-content: center; font-size: 24px;
  box-shadow: 0 6px 16px rgba(59,130,246,.28);
}
.guide-title { font-size: 18px; font-weight: 700; color: var(--text-primary); }
.guide-sub { font-size: 12.5px; color: var(--text-secondary); margin-top: 2px; }
.guide-body { padding: 4px 2px 0; }
.guide-steps { margin-bottom: 18px; }
.guide-psteps :deep(.el-step__title) { font-size: 12px; }
.guide-list { list-style: none; padding: 0; margin: 0 0 14px; }
.guide-item {
  display: flex; align-items: center; gap: 14px;
  padding: 12px 14px; border: 1px solid var(--border-light); border-radius: 12px;
  margin-bottom: 10px; background: var(--bg-card); transition: all .2s;
}
.guide-item:hover { box-shadow: var(--shadow-md); border-color: #93c5fd; }
.gi-no {
  width: 28px; height: 28px; flex-shrink: 0; border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6, #6366f1); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-weight: 700; font-size: 14px;
}
.gi-main { flex: 1; min-width: 0; }
.gi-title { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.gi-desc { font-size: 12px; color: var(--text-secondary); margin-top: 2px; line-height: 1.6; }
.guide-tip { margin-bottom: 4px; }
.guide-foot { display: flex; align-items: center; gap: 10px; }
.guide-foot .spacer { flex: 1; }
:deep(.guide-dialog) { border-radius: 18px; }
</style>
