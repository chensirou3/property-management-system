import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const catalogPath = fileURLToPath(new URL('../src/config/page-catalog.json', import.meta.url))
const outputPath = fileURLToPath(new URL('../../../docs/page-acceptance-cards.md', import.meta.url))
const catalog = JSON.parse(readFileSync(catalogPath, 'utf8'))

const fieldTypeLabels = {
  text: '文本', number: '数值', money: '金额', area: '面积', percent: '百分比', status: '状态', masked: '脱敏文本',
  selection: '多选', date: '日期', datetime: '日期时间', month: '月份', 'date-range': '日期范围', 'month-range': '月份范围',
  select: '下拉选择', 'tree-select': '树选择', project: '项目选择', 'asset-select': '资产选择', 'customer-select': '客户选择',
}
const stateLabels = {
  normal: '正常', loading: '加载中', empty: '空数据', forbidden: '无权限', 'validation-error': '校验失败',
  'request-error': '请求失败', conflict: '并发/业务冲突', 'partial-failure': '批量部分失败',
}

function parseField(token) {
  const [key, label, type] = token.split('|')
  return { key, label, type }
}

function renderFields(tokens) {
  return [
    '| 字段键 | 显示名称 | 类型 |',
    '|---|---|---|',
    ...tokens.map((token) => {
      const field = parseField(token)
      return `| \`${field.key}\` | ${field.label} | ${fieldTypeLabels[field.type] || field.type} |`
    }),
  ].join('\n')
}

function renderPermissions(permissions) {
  const labels = { read: '页面读取', write: '写操作', import: '导入', export: '导出', print: '打印' }
  return Object.entries(permissions).map(([kind, code]) => `${labels[kind] || kind} \`${code}\``).join('；')
}

function renderPage(page) {
  return `## ${String(page.pageNo).padStart(2, '0')}. ${page.title}

| 项目 | 定义 |
|---|---|
| 路由 | \`${page.path}\` |
| 波次 / 领域 | ${page.wave} / \`${page.domain}\` |
| 页面实现 | \`${page.implementation}\` |
| 目标等级 | ${page.targetLevels.join(' / ')} |
| 权限 | ${renderPermissions(page.permissions)} |
| 主要操作 | ${page.operations.map((operation) => `\`${operation}\``).join('、')} |
| 必备状态 | ${catalog.requiredStates.map((state) => stateLabels[state]).join('、')} |
| 来源索引 | ${page.sourceRef} |
| 目标路由 | \`${page.targetPath}\` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

${renderFields(page.query)}

### 列模型

${renderFields(page.columns)}

### 验收记录

- 业务假设：${page.assumptions}
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。
`
}

const waveCounts = Object.fromEntries(['A', 'B', 'C', 'D'].map((wave) => [wave, catalog.pages.filter((page) => page.wave === wave).length]))
const routeIndex = [
  '| 序号 | 页面 | 目标只读路由 | 重构路由 | 证据索引 |',
  '|---:|---|---|---|---|',
  ...catalog.pages.map((page) => `| ${page.pageNo} | ${page.title} | \`${page.targetPath}\` | \`${page.path}\` | ${page.sourceRef} |`),
].join('\n')
const content = `# PMS3 49 页验收卡

> 本文档由 \`apps/admin-web/src/config/page-catalog.json\` 自动生成，请勿手工修改。目录版本：${catalog.version}。

## 使用规则

- 这 49 张卡是固定范围，不把辅助管理页误算为目标页面，也不以菜单出现代替完成。
- 每页必须具备稳定路由、明确查询/列模型、按钮权限和八类可见状态。
- “已登记”仅表示 G2 元数据完备；只有结构、功能、数据和外部边界证据齐全后，才可在最终追踪矩阵中标为完成。
- 复杂工作台、状态流转、报表、迁移和外部适配页面必须使用对应专用实现，不得退化为万能 CRUD。
- 生成命令：\`npm run pages:acceptance\`；漂移检查：\`npm run pages:acceptance:check\`。

## 覆盖摘要

| 项目 | 数量 |
|---|---:|
| 总页面 | ${catalog.pages.length} |
| 波次 A | ${waveCounts.A} |
| 波次 B | ${waveCounts.B} |
| 波次 C | ${waveCounts.C} |
| 波次 D | ${waveCounts.D} |
| 每页必备状态 | ${catalog.requiredStates.length} |

## 证据源索引

| 证据 | 位置 | 用途 |
|---|---|---|
| 页面与接口证据附录 | \`../PMS3目标系统调查/PMS3页面与接口证据附录.md\` | 49 页目标路由、静态字段、按钮、表格列与接口线索 |
| 只读审计快照 | \`../PMS3目标系统调查/scans/2026-08-24/pms3_audit_snapshot-2026-08-24.json\` | 2026-08-24 授权只读扫描的机器记录 |
| 脱敏数据统计 | \`../PMS3目标系统调查/scans/2026-08-24/优山美地基础数据统计（脱敏）-2026-08-24.json\` | 可授权使用的数据数量、字段与关系基线 |
| 目标评估报告 | \`../PMS3目标系统调查/PMS3目标系统功能与UI复刻评估报告.md\` | UI、功能、数据和风险判断 |
| 当前视觉基线 | \`apps/admin-web/e2e/visual-baselines.spec.ts-snapshots/\` | 当前登录与看板三视口回归基线 |

原始调查资料位于工程同级目录，属于只读授权证据，不复制账号、密码、Cookie、令牌或业务记录值到本工程。

## 目标与重构路由索引

${routeIndex}

${catalog.pages.map(renderPage).join('\n')}
`

if (process.argv.includes('--check')) {
  let existing = ''
  try {
    existing = readFileSync(outputPath, 'utf8')
  } catch {
    console.error(`Missing generated acceptance cards: ${outputPath}`)
    process.exit(1)
  }
  if (existing !== content) {
    console.error('Page acceptance cards are out of date. Run: npm run pages:acceptance')
    process.exit(1)
  }
  console.log(`Acceptance cards are current: ${catalog.pages.length} pages`)
} else {
  writeFileSync(outputPath, content, 'utf8')
  console.log(`Generated ${catalog.pages.length} acceptance cards: ${outputPath}`)
}
