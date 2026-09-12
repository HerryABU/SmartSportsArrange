<template>
  <div class="help-page">
    <div class="pg-head">
      <div class="pg-titles">
        <div class="pg-ico"><el-icon size="22"><Reading /></el-icon></div>
        <div>
          <h2 class="pg-title">系统使用说明书</h2>
          <p class="pg-desc">从首次部署到成绩出榜，全流程操作指引（管理员 / 体育老师版）</p>
        </div>
      </div>
      <div class="pg-actions">
        <el-button :icon="Guide" @click="guideVisible = true">重新查看新手引导</el-button>
        <el-button type="primary" :icon="Top" @click="scrollTop">回到顶部</el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 左侧锚点导航 -->
      <el-col :xs="24" :sm="8" :md="6">
        <div class="help-nav">
          <div class="nav-title">目录</div>
          <a
            v-for="s in sections"
            :key="s.id"
            class="nav-item"
            :class="{ on: activeId === s.id }"
            @click="scrollTo(s.id)"
          >{{ s.title }}</a>
        </div>
      </el-col>

      <!-- 右侧内容 -->
      <el-col :xs="24" :sm="16" :md="18">
        <div class="help-content">
          <section v-for="s in sections" :key="s.id" :id="s.id" class="help-sec">
            <h3 class="sec-h">
              <span class="sec-no">{{ s.no }}</span>{{ s.title }}
            </h3>
            <div class="sec-body" v-html="s.html"></div>
          </section>

          <section class="help-sec">
            <h3 class="sec-h"><span class="sec-no">★</span>默认账号</h3>
            <div class="sec-body">
              <table class="doc-table">
                <thead><tr><th>角色</th><th>账号</th><th>密码</th><th>权限范围</th></tr></thead>
                <tbody>
                  <tr><td>超级管理员</td><td><code>admin</code></td><td><code>admin123</code></td><td>全部权限 + 用户/数据库管理</td></tr>
                  <tr><td>体育老师</td><td><code>teacher</code></td><td><code>teacher123</code></td><td>编排 / 成绩 / 报表 / 基础数据</td></tr>
                  <tr><td>班主任</td><td><code>class_teacher</code></td><td><code>class123</code></td><td>本班名单 / 报名 / 赛程成绩查看</td></tr>
                  <tr><td>学生</td><td><code>student</code></td><td><code>student123</code></td><td>个人赛程 / 成绩 / 赛事浏览</td></tr>
                </tbody>
              </table>
              <p class="warn">⚠️ 生产环境请尽快修改默认密码（安装向导中即可设定管理员密码）。</p>
            </div>
          </section>
        </div>
      </el-col>
    </el-row>

    <OnboardingGuide v-model="guideVisible" />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Reading, Top, Guide } from '@element-plus/icons-vue'
import OnboardingGuide from '@/components/OnboardingGuide.vue'

const guideVisible = ref(false)
const activeId = ref('start')

const sections = [
  {
    id: 'start', no: '1', title: '快速开始（首次部署）',
    html: `
      <p>首次启动未安装时，所有页面会自动跳转到<strong>安装向导</strong>（<code>/setup</code>），三步即可完成：</p>
      <ol>
        <li><b>数据库配置</b>：本地/小规模推荐 <b>SQLite</b>（零配置单文件）；生产环境可选 <b>MySQL</b>（需填写连接并「测试连接」）。</li>
        <li><b>站点信息</b>：填写运动会名称（如「第 X 届田径运动会」）与描述。</li>
        <li><b>管理员账号</b>：设定用户名与密码（≥6 位）。安装完成后向导永久锁定，任何人无法再次进入。</li>
      </ol>
      <p>安装结束点击「前往登录」即可进入系统。<b>首次登录后本引导会自动弹出</b>，手把手带你完成后续初始化；之后可在左侧菜单「新手引导」随时重看。</p>`
  },
  {
    id: 'role', no: '2', title: '角色与权限',
    html: `
      <ul>
        <li><b>超级管理员（SA）</b>：体育老师全部功能 + 用户管理 + 号码簿规则 + 积分规则 + 数据库迁移/备份 + 应用运行配置。</li>
        <li><b>体育老师</b>：班级/运动员/项目/报名/编排/成绩/报表等全部业务功能。</li>
        <li><b>班主任</b>：本班名单导入、运动会报名、本班赛程与成绩查看。</li>
        <li><b>学生</b>：个人赛程、成绩、赛事浏览、个人中心。</li>
      </ul>
      <p>本说明书面向管理员 / 体育老师端的编排主流程。</p>`
  },
  {
    id: 'base', no: '3', title: '基础配置（系统设置）',
    html: `
      <p>进入 <b>系统设置</b>，完成运动会级别的参数：</p>
      <ul>
        <li><b>运动会名称 / 举办日期</b>：用于秩序册封面与赛程标题。</li>
        <li><b>日程（天 × 时段）</b>：设置第 1/2…天，每天含「上午 / 下午」时段与起止时间，可每天不同。</li>
        <li><b>年级出场顺序</b>：决定编排时年级的先后与分组依据。</li>
        <li><b>场地（名称 + 编码）</b>：第 1 个为径赛主场地，其余供田赛并行使用。<b>并数上限取决于场地数量</b>，请先录全，如：
          <code>田径场 / TRACK</code>、<code>田赛A区 / FIELD_A</code>、<code>田赛B区 / FIELD_B</code>。</li>
      </ul>
      <p class="tip">💡 编码（code）用于标识与展示，可自定义；名称用于在赛程表中显示。</p>`
  },
  {
    id: 'classes', no: '4', title: '班级与运动员',
    html: `
      <p><b>班级管理</b>：添加/导入班级，维护年级、编号、人数，并<b>绑定班主任</b>（班主任端数据可见的前提）。支持 Excel 导入导出、批量创建、自动生成班主任账号。</p>
      <p><b>运动员管理</b>：多维筛选（年级/班级/关键词），号码簿自动生成（规则在系统设置自定义），批量导入/导出。点击班级可展开查看学生明细。</p>
      <p><b>班主任端「班级名单」</b>：导入全班花名册（学号/姓名/性别）→ 自动创建学生账号 + 运动员记录。</p>`
  },
  {
    id: 'events', no: '5', title: '比赛项目（表格2 模板）',
    html: `
      <p>在 <b>比赛项目（表格2）</b> 页面，点「下载表格2模板」获取 CSV，按列填写后导入。关键列说明：</p>
      <table class="doc-table">
        <thead><tr><th>列</th><th>含义</th><th>说明</th></tr></thead>
        <tbody>
          <tr><td>代码 / 项目</td><td>项目标识 / 名称</td><td>如 <code>100M</code> / <code>100米</code></td></tr>
          <tr><td>是否田径</td><td>径赛 / 田赛</td><td>是=田赛，否=径赛</td></tr>
          <tr><td>道次</td><td>跑道数</td><td>径赛填实际道次，田赛填 0</td></tr>
          <tr><td>项目内并发</td><td>同时参与人数</td><td>田赛＝工位数（X 人同时试跳/试掷）；径赛留空按道次数</td></tr>
          <tr><td>顺序号</td><td>编排默认顺序</td><td>Excel 批量导入项目顺序，未列入者按此稳定追加</td></tr>
          <tr><td>并行捆绑组</td><td>同批并行字母</td><td>填相同字母（A/B/C…）的田赛自动安排在同一时段并行；留空则自动编排</td></tr>
          <tr><td>场地 / 最大用时 / 间隔</td><td>场地名 / 单轮分钟 / 项目间隔</td><td>用于估算时长与排布</td></tr>
        </tbody>
      </table>
      <p>也支持预设模板（跑步/跳跃/投掷/接力）一键生成，以及单个项目的启用/禁用、道数、预赛、计分配置。</p>`
  },
  {
    id: 'reg', no: '6', title: '报名导入与审核',
    html: `
      <p><b>班主任端报名</b>：导入名单 → 学号定位 → 点击项目卡片报名。约束：性别匹配、每人最多 3 项、不可重复。</p>
      <p><b>教师端「报名管理」</b>：查看报名列表、单个/批量审核（通过/拒绝）、报名统计（含满额率）、导出。</p>
      <p class="tip">💡 满额率为 0 时显示「不限」；报名进度按真实花名册人数统计，重复报名自动去重。</p>`
  },
  {
    id: 'schedule', no: '7', title: '赛程编排（核心）',
    html: `
      <p>在 <b>赛程编排</b> 页面把项目自动调度到「天 × 时段 × 场地」，采用 <b>1~n 并发位</b> 模型：</p>
      <table class="doc-table">
        <thead><tr><th>概念</th><th>配置</th><th>说明</th></tr></thead>
        <tbody>
          <tr><td>并数</td><td><code>trackSlots</code> / <code>fieldSlots</code></td><td>同一时刻可同时进行几个项目：<b>1 = 串行</b>，n = 并行。<b>上限取决于场地数量</b>（前端实时限制 + 保存时后端校验）。</td></tr>
          <tr><td>项目内并发</td><td><code>event.concurrency</code></td><td>单项目内同时人数，决定时长 = <code>ceil(人数/并发) × 单轮用时</code>，受最大用时封顶。</td></tr>
          <tr><td>自定义项目顺序</td><td><code>eventOrder</code> / <code>sortOrder</code></td><td>田赛+径赛混排；Excel「顺序号」列可批量导入，UI 支持置顶/上移/下移/置底。</td></tr>
          <tr><td>田赛分组</td><td><code>fieldGroups</code></td><td>同组田赛安排在同一时段并行；组内数量受 fieldSlots 约束，超出自动分波并提示。</td></tr>
          <tr><td>并行捆绑组</td><td><code>event.bundleGroup</code></td><td>项目级字母分组：相同字母自动同批并行，<b>优先级高于田赛分组配置</b>；留空由算法自动安排。</td></tr>
        </tbody>
      </table>
      <p>编排结果中若出现「未能在同一时段并行」告警，通常是时段容量或并发位数不足——提高田赛并发位数或增加场地即可。支持手动微调单项、导出赛程 Excel。</p>
      <p class="tip">💡 径赛用第 1 个场地；田赛的 n 个并发位依次占用其余场地，场地不足时复用并给出 warning。</p>`
  },
  {
    id: 'arrange', no: '8', title: '道次编排',
    html: `
      <p><b>道次编排</b> 对已审核报名自动分组分道（贪心 + 局部优化）：</p>
      <ol>
        <li>获取已审核报名 → 按班级分组；</li>
        <li>组数 = <code>ceil(总人数 / 项目内并发人数)</code>；</li>
        <li>大班优先，贪心分配最早空位，再做 5 轮局部优化；</li>
        <li>支持预览（不落库）、批量编排、手动调整、版本回滚、导出道次表。</li>
      </ol>
      <p>硬约束：同年级不混编、性别分离；软约束：同班不同道/不同组（可在编排规则开关）。可「生成预赛」并可开启「自动生成 Word 秩序册」。</p>`
  },
  {
    id: 'score', no: '9', title: '成绩录入与排名',
    html: `
      <p><b>成绩管理</b>：录入/修改/删除，支持 Excel 导入，自动排名计算。成绩格式：<code>12.34</code>(秒)、<code>2:35.67</code>(分:秒)、<code>6.78</code>(米)；状态 valid / dq / dns / dnf。</p>
      <p><b>合分排行</b>：单项目排名、个人积分、团体总分、破纪录榜；默认积分表 9-7-6-5-4-3-2-1（可自定义），支持并列、破纪录加分、接力加倍、团体总分。</p>`
  },
  {
    id: 'report', no: '10', title: '报表与秩序册',
    html: `
      <p><b>报表中心</b> 提供：</p>
      <ul>
        <li><b>秩序册</b>：Excel(.xlsx) 与 <b>Word(.docx)</b> 双形态。Word 版为真实排版（封面/目录/五章表格），支持一键下载，或开启「生成预赛后自动落盘」。无需 Apache POI、离线可构建。</li>
        <li><b>成绩册 / 报名统计 / 道次表 / 成绩汇总 / 团体总分榜</b>：均可导出。</li>
      </ul>`
  },
  {
    id: 'screen', no: '11', title: '数据大屏',
    html: `<p>左侧菜单「数据大屏」「排行榜大屏」进入全屏投屏视图，适合现场实时展示赛况与排名。教师端编排或成绩更新后，大屏可刷新查看。</p>`
  },
  {
    id: 'system', no: '12', title: '系统设置（管理员）',
    html: `
      <ul>
        <li><b>数据库热迁移</b>：SQLite ↔ MySQL 在线切换，连接测试 → 异步迁移 → 进度查询，<b>全程无需重启</b>。</li>
        <li><b>数据库备份</b>：手动/自动备份、列表、下载、删除。</li>
        <li><b>号码簿规则</b>：模板变量（如 <code>{grade}{class}{seq:02d}</code>）+ 实时预览；支持「按名单顺序补全生成」与「重排覆盖」两种操作，撞号自动顺延。</li>
        <li><b>积分规则 / 编排规则</b>：名次积分表、并列处理、破纪录加分、软约束开关、算法参数。</li>
        <li><b>应用运行配置</b>：服务端口、绑定地址（重启生效）。</li>
      </ul>`
  },
  {
    id: 'faq', no: '13', title: '常见问题 FAQ',
    html: `
      <p><b>Q：并数怎么设？与田赛分组什么关系？</b><br>并数＝同一时刻能同时进行几个项目：径赛设 1（串行），田赛按可用场地设 2~3（并行）。「田赛分组」指定哪些田赛必须同一时段并行；「并行捆绑组」用相同字母更精细地控制同批并行（优先级更高）。</p>
      <p><b>Q：编排后同组项目没在同一时间？</b><br>时段容量或并数不足导致自动分波。提高田赛并数或增加场地即可。</p>
      <p><b>Q：Excel 导入列名不匹配？</b><br>先下载对应模板（表格2 含 顺序号 / 项目内并发 / 并行捆绑组 等列），按表头填写。</p>
      <p><b>Q：满额率/报名进度显示异常？</b><br>确保班级名单与报名均已正确导入并审核；进度按真实花名册人数计算。</p>`
  }
]

function scrollTo(id) {
  activeId.value = id
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function scrollTop() {
  const c = document.querySelector('.help-content')
  if (c) c.scrollIntoView({ behavior: 'smooth', block: 'start' })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

let observer = null
onMounted(() => {
  observer = new IntersectionObserver((entries) => {
    entries.forEach((e) => { if (e.isIntersecting) activeId.value = e.target.id })
  }, { rootMargin: '-10% 0px -75% 0px', threshold: 0 })
  sections.forEach((s) => {
    const el = document.getElementById(s.id)
    if (el) observer.observe(el)
  })
})
onBeforeUnmount(() => { if (observer) observer.disconnect() })
</script>

<style scoped>
.help-page { padding-bottom: 40px; }
.help-nav {
  position: sticky; top: 12px;
  background: var(--bg-card); border: 1px solid var(--border-light);
  border-radius: var(--radius-lg); padding: 12px 10px; max-height: calc(100vh - 100px); overflow-y: auto;
}
.nav-title { font-size: 12px; color: var(--text-muted); padding: 4px 10px 8px; letter-spacing: .5px; }
.nav-item {
  display: block; padding: 8px 12px; border-radius: 8px; font-size: 13px;
  color: var(--text-secondary); cursor: pointer; transition: all .15s; text-decoration: none;
}
.nav-item:hover { background: rgba(59,130,246,.08); color: var(--text-primary); }
.nav-item.on { background: linear-gradient(135deg, rgba(59,130,246,.15), rgba(99,102,241,.12)); color: var(--color-primary-dark); font-weight: 600; }
.help-content { }
.help-sec { scroll-margin-top: 12px; margin-bottom: 22px; background: var(--bg-card); border: 1px solid var(--border-light); border-radius: var(--radius-lg); padding: 18px 20px; }
.sec-h { font-size: 16.5px; font-weight: 700; color: var(--text-primary); margin: 0 0 12px; display: flex; align-items: center; gap: 10px; }
.sec-no {
  width: 26px; height: 26px; border-radius: 8px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--color-primary), var(--color-info)); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 700;
}
.sec-body { font-size: 13.5px; color: var(--text-secondary); line-height: 1.85; }
.sec-body :deep(p) { margin: 0 0 10px; }
.sec-body :deep(ol), .sec-body :deep(ul) { margin: 0 0 10px; padding-left: 22px; }
.sec-body :deep(li) { margin-bottom: 6px; }
.sec-body :deep(code) { background: rgba(59,130,246,.1); color: var(--color-primary-dark); padding: 1px 6px; border-radius: 5px; font-size: 12.5px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.sec-body :deep(strong) { color: var(--text-primary); }
.tip { background: var(--color-info-bg); border-left: 3px solid var(--color-info); padding: 8px 12px; border-radius: 8px; color: var(--text-secondary); }
.warn { background: var(--color-warning-bg); border-left: 3px solid var(--color-warning); padding: 8px 12px; border-radius: 8px; }
.doc-table { width: 100%; border-collapse: collapse; margin: 0 0 10px; font-size: 12.5px; }
.doc-table th, .doc-table td { border: 1px solid var(--border-light); padding: 7px 10px; text-align: left; vertical-align: top; }
.doc-table th { background: #f8fafc; color: var(--text-primary); font-weight: 600; }
.doc-table td code { background: rgba(59,130,246,.1); color: var(--color-primary-dark); padding: 1px 5px; border-radius: 4px; }
html.dark .doc-table th { background: #1e293b; }
</style>
