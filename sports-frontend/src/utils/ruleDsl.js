// 规则片段「积木 ↔ DSL」共享 AST（对应后端内置伪代码引擎 inject/builtin 的语法）。
//
// 设计要点（与 docs/DSL编排脚本-架构预告.md 一致）：
//   积木与代码不是两个系统，而是**同一棵 AST 的两种投影**——积木是结构化编辑，DSL 是文本投影。
//   本文件只做「AST ⇄ DSL 文本」的确定性换算，不含业务；页面负责渲染与同步。
//
// AST: { rules: [ { join: '&&' | '||', conditions: [{field, op, value}], actions: [{kind, value} | {kind:'veto'}] } ] }

/** 可参与条件的上下文字段（与后端 ruleContextOf 暴露的字段对齐） */
export const FIELD_OPTIONS = [
  { value: 'event.category', label: '项目类型', type: 'string' },
  { value: 'event.track', label: '是否径赛', type: 'bool' },
  { value: 'event.team', label: '是否团体', type: 'bool' },
  { value: 'event.teamMembers', label: '每组人数', type: 'number' },
  { value: 'event.venueCode', label: '场地编码', type: 'string' },
  { value: 'athlete.className', label: '班级', type: 'string' },
  { value: 'athlete.grade', label: '年级', type: 'string' },
  { value: 'athlete.gender', label: '性别', type: 'string' },
  { value: 'heat', label: '组次', type: 'number' },
  { value: 'lane', label: '道次', type: 'number' },
  { value: 'heats', label: '总组数', type: 'number' },
  { value: 'grade', label: '年级(赛程)', type: 'string' },
  { value: 'poolLabel', label: '并发池', type: 'string' },
  { value: 'duration', label: '时长(分)', type: 'number' },
  { value: 'placement.day', label: '比赛日', type: 'number' },
  { value: 'placement.startMinute', label: '开赛分钟', type: 'number' },
  { value: 'placement.poolLabel', label: '落位并发池', type: 'string' }
]

/** 比较运算符 */
export const OP_OPTIONS = [
  { value: '==', label: '等于' },
  { value: '!=', label: '不等于' },
  { value: '>', label: '大于' },
  { value: '>=', label: '大于等于' },
  { value: '<', label: '小于' },
  { value: '<=', label: '小于等于' }
]

/** 动作类型 */
export const ACTION_OPTIONS = [
  { value: 'hard', label: '硬惩罚 hard' },
  { value: 'medium', label: '中惩罚 medium' },
  { value: 'soft', label: '软惩罚 soft' },
  { value: 'veto', label: '否决 veto' }
]

/** 新建一条积木（默认：项目类型 == 径赛 → soft 30） */
export function emptyRule () {
  return {
    join: '&&',
    conditions: [{ field: 'event.category', op: '==', value: '径赛' }],
    actions: [{ kind: 'soft', value: 30 }]
  }
}

/** 新建空脚本对象 */
export function emptyBlock () {
  return { rules: [emptyRule()] }
}

/** 值字面量化：数字/布尔不加引号，其余加双引号（与后端 tokenizer 一致） */
function formatValue (v) {
  const s = String(v === null || v === undefined ? '' : v).trim()
  if (s === '') return '""'
  if (/^-?\d+(\.\d+)?$/.test(s)) return s
  if (s === 'true' || s === 'false' || s === 'null') return s
  return '"' + s.replace(/"/g, '\\"') + '"'
}

/** 单个条件 → 表达式文本 */
function conditionText (c) {
  return `${c.field} ${c.op} ${formatValue(c.value)}`
}

/** 单条规则 → DSL 语句 */
export function ruleToDsl (rule) {
  const conds = (rule.conditions || []).filter(c => c && c.field).map(conditionText)
  const condStr = conds.length ? conds.join(` ${rule.join === '||' ? '||' : '&&'} `) : 'true'
  const acts = (rule.actions || [])
    .filter(a => a && a.kind)
    .map(a => (a.kind === 'veto' ? 'veto' : `${a.kind} += ${Number(a.value) || 0}`))
  return `when ${condStr} then ${acts.length ? acts.join(', ') : 'soft += 0'}`
}

/** 整块积木 → DSL 源码（每行一条规则） */
export function blocksToDsl (block) {
  const rules = (block && block.rules) || []
  return rules.map(ruleToDsl).join('\n')
}

/** 去掉行注释（# 与 //），尊重引号 */
function stripComments (src) {
  const out = []
  const lines = String(src || '').split('\n')
  for (let line of lines) {
    let inStr = false
    let cut = -1
    for (let i = 0; i < line.length; i++) {
      const ch = line[i]
      if (inStr) {
        if (ch === '\\') { i++; continue }
        if (ch === '"' || ch === "'") inStr = false
        continue
      }
      if (ch === '"' || ch === "'") { inStr = true; continue }
      if (ch === '#' || (ch === '/' && line[i + 1] === '/')) { cut = i; break }
    }
    out.push(cut >= 0 ? line.slice(0, cut) : line)
  }
  return out.join('\n')
}

function unquote (raw) {
  const s = String(raw || '').trim()
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    return s.slice(1, -1).replace(/\\(.)/g, '$1')
  }
  return s
}

const COND_RE = /^([A-Za-z_][A-Za-z0-9_.]*)\s*(==|!=|>=|<=|>|<)\s*(.+)$/

/**
 * DSL 源码 → 积木（尽力而为）。仅支持本页可生成的子集：
 * 每行 `when <条件[ && 或 ||]> then <动作[,]>`；条件为 `字段 比较符 值`；动作为 `hard|medium|soft += N` 或 `veto`。
 * 解析不了返回 null（页面据此提示「该语法暂不支持积木模式，请用代码模式」）。
 */
export function dslToBlocks (src) {
  const cleaned = stripComments(src)
  const lines = cleaned.split('\n').map(l => l.trim()).filter(Boolean)
  if (!lines.length) return { rules: [] }
  const rules = []
  for (const line of lines) {
    const m = /^when\s+(.+?)\s+then\s+(.+)$/i.exec(line)
    if (!m) return null
    const condPart = m[1].trim()
    const actPart = m[2].trim()
    // 连接符：整行只允许同一种（&& 或 ||），与积木语义一致
    const hasAnd = condPart.includes('&&')
    const hasOr = condPart.includes('||')
    if (hasAnd && hasOr) return null
    const join = hasOr ? '||' : '&&'
    const separator = hasOr ? '||' : '&&'
    const conditions = []
    if (condPart !== 'true') {
      for (const piece of condPart.split(separator)) {
        const cm = COND_RE.exec(piece.trim())
        if (!cm) return null
        conditions.push({ field: cm[1], op: cm[2], value: unquote(cm[3]) })
      }
    }
    const actions = []
    for (const piece of actPart.split(/[;,]/)) {
      const a = piece.trim()
      if (!a) continue
      if (/^(veto|reject)$/i.test(a)) { actions.push({ kind: 'veto', value: 0 }); continue }
      const am = /^(hard|medium|soft)\s*\+=\s*(-?\d+(?:\.\d+)?)$/i.exec(a)
      if (!am) return null
      actions.push({ kind: am[1].toLowerCase(), value: Number(am[2]) })
    }
    if (!actions.length) return null
    rules.push({ join, conditions, actions })
  }
  return { rules }
}

/** 把两条规则片段换算成「试运行上下文」的默认 JSON 文本（便于用户上手） */
export function defaultContextJson () {
  return JSON.stringify({
    event: { category: '径赛', track: true, team: false, teamMembers: 1, venueCode: 'TRACK' },
    athlete: { className: '高一1班', grade: '高一年级', gender: 'M' },
    heat: 1,
    lane: 1,
    heats: 3
  }, null, 2)
}
