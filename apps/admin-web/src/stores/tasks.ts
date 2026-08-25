import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type AsyncTaskKind = 'IMPORT' | 'EXPORT' | 'PRINT'
export type AsyncTaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED'

export interface AsyncTask {
  id: string
  kind: AsyncTaskKind
  title: string
  sourcePath: string
  projectId?: string
  status: AsyncTaskStatus
  progress: number
  createdAt: string
  startedAt?: string
  finishedAt?: string
  fileName?: string
  message?: string
  totalRows?: number
  successRows?: number
  failedRows?: number
}

interface TaskResult {
  blob?: Blob
  fileName?: string
  message: string
  totalRows?: number
  successRows?: number
  failedRows?: number
}

type TaskExecutor = (reportProgress: (progress: number) => void) => Promise<TaskResult>

const STORAGE_KEY = 'pms_async_tasks'
const resultBlobs = new Map<string, Blob>()
const executors = new Map<string, TaskExecutor>()
const timers = new Map<string, number>()

function newId() {
  return globalThis.crypto?.randomUUID?.() || `task-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function csvCell(value: unknown) {
  let text = value === null || value === undefined ? '' : String(value)
  if (/^[=+\-@]/.test(text)) text = `'${text}`
  return `"${text.replaceAll('"', '""')}"`
}

function parseCsvLine(line: string) {
  const values: string[] = []
  let current = ''
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (char === '"' && quoted && line[index + 1] === '"') {
      current += '"'
      index += 1
    } else if (char === '"') {
      quoted = !quoted
    } else if (char === ',' && !quoted) {
      values.push(current.trim())
      current = ''
    } else {
      current += char
    }
  }
  values.push(current.trim())
  return values
}

function escapeHtml(value: unknown) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char] || char)
}

function restoreTasks(): AsyncTask[] {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.slice(0, 50).map((task) => ({
      ...task,
      status: ['QUEUED', 'RUNNING'].includes(task.status) ? 'FAILED' : task.status,
      message: ['QUEUED', 'RUNNING'].includes(task.status) ? '页面恢复后任务执行上下文已失效，请重试。' : task.message,
      finishedAt: ['QUEUED', 'RUNNING'].includes(task.status) ? new Date().toISOString() : task.finishedAt,
    }))
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return []
  }
}

export const useTaskStore = defineStore('tasks', () => {
  const tasks = ref<AsyncTask[]>(restoreTasks())
  const drawerVisible = ref(false)
  const activeCount = computed(() => tasks.value.filter((task) => ['QUEUED', 'RUNNING'].includes(task.status)).length)
  const unreadCount = computed(() => tasks.value.filter((task) => ['SUCCEEDED', 'PARTIAL_FAILED', 'FAILED'].includes(task.status)).length)

  function persist() {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(tasks.value.slice(0, 50)))
  }

  function update(id: string, patch: Partial<AsyncTask>) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task) return
    Object.assign(task, patch)
    persist()
  }

  async function run(id: string) {
    timers.delete(id)
    const task = tasks.value.find((item) => item.id === id)
    const executor = executors.get(id)
    if (!task || !executor || task.status === 'CANCELLED') return
    update(id, { status: 'RUNNING', progress: 5, startedAt: new Date().toISOString(), message: '任务正在执行。' })
    try {
      const result = await executor((progress) => {
        const current = tasks.value.find((item) => item.id === id)
        if (current?.status === 'RUNNING') update(id, { progress: Math.max(5, Math.min(95, Math.round(progress))) })
      })
      const current = tasks.value.find((item) => item.id === id)
      if (!current || current.status === 'CANCELLED') return
      if (result.blob) resultBlobs.set(id, result.blob)
      const status: AsyncTaskStatus = (result.failedRows || 0) > 0 ? 'PARTIAL_FAILED' : 'SUCCEEDED'
      update(id, {
        status,
        progress: 100,
        finishedAt: new Date().toISOString(),
        fileName: result.fileName,
        message: result.message,
        totalRows: result.totalRows,
        successRows: result.successRows,
        failedRows: result.failedRows,
      })
    } catch (error) {
      const current = tasks.value.find((item) => item.id === id)
      if (current?.status !== 'CANCELLED') {
        update(id, { status: 'FAILED', progress: 100, finishedAt: new Date().toISOString(), message: error instanceof Error ? error.message : '任务执行失败。' })
      }
    }
  }

  function enqueue(input: Pick<AsyncTask, 'kind' | 'title' | 'sourcePath' | 'projectId'>, executor: TaskExecutor) {
    const id = newId()
    tasks.value.unshift({ ...input, id, status: 'QUEUED', progress: 0, createdAt: new Date().toISOString(), message: '任务已进入队列。' })
    executors.set(id, executor)
    persist()
    timers.set(id, window.setTimeout(() => void run(id), 30))
    return id
  }

  function createExportTask(input: {
    title: string
    sourcePath: string
    projectId?: string
    fileName: string
    columns: Array<{ key: string; label: string }>
    rows: Record<string, unknown>[]
  }) {
    return enqueue({ kind: 'EXPORT', title: input.title, sourcePath: input.sourcePath, projectId: input.projectId }, async (progress) => {
      progress(30)
      const lines = [input.columns.map((column) => csvCell(column.label)).join(',')]
      input.rows.forEach((row, index) => {
        lines.push(input.columns.map((column) => csvCell(row[column.key])).join(','))
        if (index % 50 === 0) progress(30 + (index / Math.max(1, input.rows.length)) * 55)
      })
      const blob = new Blob([`\uFEFF${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
      return { blob, fileName: input.fileName, message: `已生成 ${input.rows.length} 行 CSV，可在任务中心下载。`, totalRows: input.rows.length, successRows: input.rows.length, failedRows: 0 }
    })
  }

  function createPrintTask(input: {
    title: string
    sourcePath: string
    projectId?: string
    fileName: string
    columns: Array<{ key: string; label: string }>
    rows: Record<string, unknown>[]
  }) {
    return enqueue({ kind: 'PRINT', title: input.title, sourcePath: input.sourcePath, projectId: input.projectId }, async (progress) => {
      progress(40)
      const header = input.columns.map((column) => `<th>${escapeHtml(column.label)}</th>`).join('')
      const body = input.rows.map((row) => `<tr>${input.columns.map((column) => `<td>${escapeHtml(row[column.key])}</td>`).join('')}</tr>`).join('')
      const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${escapeHtml(input.title)}</title><style>body{font:12px Arial;color:#222}h1{font-size:18px}table{width:100%;border-collapse:collapse}th,td{padding:6px;border:1px solid #aaa;text-align:left}@media print{button{display:none}}</style></head><body><h1>${escapeHtml(input.title)}</h1><p>生成时间：${escapeHtml(new Date().toLocaleString('zh-CN'))}</p><table><thead><tr>${header}</tr></thead><tbody>${body}</tbody></table></body></html>`
      progress(90)
      return { blob: new Blob([html], { type: 'text/html;charset=utf-8' }), fileName: input.fileName, message: `已生成 ${input.rows.length} 行可打印 HTML。`, totalRows: input.rows.length, successRows: input.rows.length, failedRows: 0 }
    })
  }

  function createImportValidationTask(input: {
    title: string
    sourcePath: string
    projectId?: string
    file: File
    expectedHeaders: string[]
  }) {
    return enqueue({ kind: 'IMPORT', title: input.title, sourcePath: input.sourcePath, projectId: input.projectId }, async (progress) => {
      if (!input.file.name.toLowerCase().endsWith('.csv')) throw new Error('当前基础导入器仅接受 UTF-8 CSV 文件。')
      if (input.file.size > 10 * 1024 * 1024) throw new Error('导入文件不能超过 10 MB。')
      progress(20)
      const content = (await input.file.text()).replace(/^\uFEFF/, '')
      const lines = content.split(/\r?\n/).filter((line) => line.trim())
      if (lines.length < 2) throw new Error('CSV 至少需要表头和一行数据。')
      const headers = parseCsvLine(lines[0])
      const missing = input.expectedHeaders.filter((header) => !headers.includes(header))
      if (missing.length) throw new Error(`缺少必填表头：${missing.join('、')}`)
      let failedRows = 0
      const errorLines = ['行号,错误原因']
      lines.slice(1).forEach((line, index) => {
        const values = parseCsvLine(line)
        if (values.length !== headers.length) {
          failedRows += 1
          errorLines.push(`${index + 2},列数与表头不一致`)
        }
        if (index % 50 === 0) progress(25 + (index / Math.max(1, lines.length - 1)) * 65)
      })
      const totalRows = lines.length - 1
      const successRows = totalRows - failedRows
      const blob = failedRows ? new Blob([`\uFEFF${errorLines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' }) : undefined
      return {
        blob,
        fileName: failedRows ? `${input.file.name.replace(/\.csv$/i, '')}-校验错误.csv` : undefined,
        message: failedRows ? `校验完成：${successRows} 行通过，${failedRows} 行失败。未写入业务数据。` : `校验完成：${successRows} 行全部通过。未写入业务数据。`,
        totalRows,
        successRows,
        failedRows,
      }
    })
  }

  function cancel(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task || !['QUEUED', 'RUNNING'].includes(task.status)) return
    const timer = timers.get(id)
    if (timer) window.clearTimeout(timer)
    timers.delete(id)
    update(id, { status: 'CANCELLED', progress: 100, finishedAt: new Date().toISOString(), message: '任务已由用户取消。' })
  }

  function retry(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task || !executors.has(id) || !['FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(task.status)) return
    resultBlobs.delete(id)
    update(id, { status: 'QUEUED', progress: 0, startedAt: undefined, finishedAt: undefined, message: '任务已重新进入队列。', fileName: undefined })
    timers.set(id, window.setTimeout(() => void run(id), 30))
  }

  function download(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    const blob = resultBlobs.get(id)
    if (!task?.fileName || !blob) return false
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = task.fileName
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    return true
  }

  function clearFinished() {
    const removable = tasks.value.filter((task) => !['QUEUED', 'RUNNING'].includes(task.status)).map((task) => task.id)
    removable.forEach((id) => {
      resultBlobs.delete(id)
      executors.delete(id)
    })
    tasks.value = tasks.value.filter((task) => ['QUEUED', 'RUNNING'].includes(task.status))
    persist()
  }

  function openDrawer() { drawerVisible.value = true }

  return {
    tasks, drawerVisible, activeCount, unreadCount,
    createExportTask, createPrintTask, createImportValidationTask,
    cancel, retry, download, clearFinished, openDrawer,
  }
})
