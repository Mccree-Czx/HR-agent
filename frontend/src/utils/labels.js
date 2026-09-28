/**
 * 枚举中文标签单一映射表（参照 HR Portal V2 约定：持久化枚举保持英文，中文标签集中于此）。
 * 列表 / 详情抽屉 / 驾驶舱 / 运行日志统一从这里取值，禁止散落硬编码。
 */

export const GREETING_STATUS = {
  SENT: { label: '已打招呼', tag: 'primary' },
  AGREED: { label: '候选人已同意', tag: 'success' },
  REQUESTED: { label: '已索要简历', tag: 'warning' },
  PENDING_CONFIRM: { label: '待确认', tag: 'info' },
  SEND_FAILED: { label: '发送失败', tag: 'danger' }
}

export const RECRUIT_STATUS = {
  PENDING_REVIEW: { label: '待筛选', tag: 'info' },
  QUALIFIED: { label: '合格', tag: 'success' },
  INTERVIEW_SCHEDULED: { label: '已约面', tag: 'warning' },
  NOT_SUITABLE: { label: '不合适', tag: 'danger' }
}

export const ROUND_MODE = {
  full: '完整轮',
  collectOnly: '只收模式'
}

/** 取中文标签（未知值回退原值/占位符） */
export function textOf(map, value, fallback = '-') {
  if (value === null || value === undefined || value === '') return fallback
  return map[value]?.label || String(value)
}

/** 取标签样式（Element tag type） */
export function tagOf(map, value, fallback = 'info') {
  return map[value]?.tag || fallback
}

/** 生成下拉选项（value/label） */
export function optionsOf(map) {
  return Object.entries(map).map(([value, meta]) => ({ value, label: meta.label }))
}

/** 评分（0-100）→ 星级（1-5，四舍五入；未评分返回 0） */
export function starsOf(score) {
  if (score === null || score === undefined || score === '') return 0
  const n = Number(score)
  if (Number.isNaN(n)) return 0
  return Math.max(1, Math.min(5, Math.round(n / 20)))
}

/** 星级字符串（★ 重复，参照产品列表呈现） */
export function starText(score) {
  const n = starsOf(score)
  return n > 0 ? '★'.repeat(n) : ''
}

/** 文件大小格式化 */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined || bytes === '') return '-'
  const n = Number(bytes)
  if (Number.isNaN(n)) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}
