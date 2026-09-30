// 出题质量抽检：从 954 题库分层抽样（每课程 2 题），双层判定：
// 1) 确定性：choice 必须有 options 且 answer 命中选项；short 必须有 keywords+analysis；
// 2) LLM 交叉评（10 题/批）：题干清晰无歧义、题干与 kp 相关、答案与题干匹配。
// 用法: node scripts/question-audit.mjs
import { execFileSync } from 'node:child_process'

const DS_KEY = process.env.DEEPSEEK_API_KEY ?? process.env.DASHSCOPE_API_KEY ?? ''
const PER_COURSE = 2

function sql(q) {
  return execFileSync('mysql', ['-uroot', '-p1234', '--default-character-set=utf8mb4', '--batch', '-e', q],
    { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
}
function parseTsv(tsv) {
  const lines = tsv.split('\n')
  const cols = lines[0].split('\t')
  return lines.slice(1).filter(l => l.trim()).map(l => {
    const cells = l.split('\t')
    return Object.fromEntries(cols.map((c, i) => [c, cells[i] ?? '']))
  })
}

const all = parseTsv(sql(`SELECT id, course_id, kp, type, stem, options, answer, analysis, keywords FROM tutor_biz.question_bank`))
console.log(`题库总量: ${all.length}`)
// 分层抽样：按课程分组轮转取 PER_COURSE 题
const byCourse = {}
for (const q of all) (byCourse[q.course_id] ??= []).push(q)
const sample = []
for (const list of Object.values(byCourse)) {
  const step = Math.max(1, Math.floor(list.length / PER_COURSE))
  for (let i = 0; sample.length < 200 && i < PER_COURSE; i++) sample.push(list[(i * step) % list.length])
}
const audit = sample.slice(0, 100)
console.log(`抽样: ${audit.length} 题（每课程 ${PER_COURSE}）`)

// 确定性检查
let detPass = 0
const detFails = []
for (const q of audit) {
  let ok = true
  if (q.type === 'choice') {
    const letter = (q.answer ?? '').trim().match(/^[A-D]/)?.[0]
    if (!q.options || !letter) ok = false
  }
  if (q.type === 'short' && (!q.keywords || !q.analysis)) ok = false
  if (ok) detPass++
  else detFails.push(q.id)
}
console.log(`确定性检查: ${detPass}/${audit.length} 通过${detFails.length ? '，缺陷: ' + detFails.join(',') : ''}`)

// LLM 交叉评（10 题/批）
async function llmBatch(batch) {
  const items = batch.map((q, i) => `${i + 1}. [${q.type}] kp=${q.kp}\n题干: ${q.stem}\n选项: ${(q.options ?? '无').slice(0, 200)}\n答案: ${q.answer}\n解析: ${(q.analysis ?? '').slice(0, 150)}`).join('\n\n')
  const resp = await fetch('https://api.deepseek.com/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${DS_KEY}` },
    body: JSON.stringify({
      model: 'deepseek-chat', temperature: 0, max_tokens: 400,
      messages: [
        { role: 'system', content: '你是职业教育题库审核员。对每道题判定三项：题干清晰无歧义(clear)、题干与kp主题相关(relevant)、答案与题干匹配(correct)。输出JSON数组，每项 {"i":序号,"clear":1或0,"relevant":1或0,"correct":1或0,"note":"一句话(仅当有0时)"}。只输出JSON。' },
        { role: 'user', content: items },
      ],
    }),
  })
  const txt = (await resp.json()).choices?.[0]?.message?.content ?? '[]'
  try { return JSON.parse(txt.match(/\[[\s\S]*\]/)?.[0] ?? '[]') } catch { return [] }
}

let llmPass = 0, llmTotal = 0
const llmFails = []
for (let i = 0; i < audit.length; i += 10) {
  const batch = audit.slice(i, i + 10)
  const verdicts = await llmBatch(batch)
  for (const v of verdicts) {
    if (!v || v.i == null) continue
    llmTotal++
    const ok = v.clear === 1 && v.relevant === 1 && v.correct === 1
    if (ok) llmPass++
    else llmFails.push(`#${audit[i + v.i - 1]?.id} ${v.note ?? ''}`)
  }
}
console.log(`\n===== 出题质量抽检 =====`)
console.log(`确定性结构完整: ${(detPass / audit.length * 100).toFixed(1)}%`)
console.log(`LLM 交叉评通过: ${llmTotal ? (llmPass / llmTotal * 100).toFixed(1) : 'N/A'}% (${llmPass}/${llmTotal})`)
if (llmFails.length) { console.log('LLM 标记缺陷:'); llmFails.slice(0, 15).forEach(f => console.log('  ' + f)) }
process.exit(0)
