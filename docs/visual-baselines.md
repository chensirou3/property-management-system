# G1–G5 登录、壳层与 49 页视觉基线

基线日期：2026-08-25
运行平台：Windows / Chrome channel / Playwright 1.57
测试入口：`apps/admin-web/e2e/visual-baselines.spec.ts`、`apps/admin-web/e2e/page-catalog.visual.spec.ts`

## G1 固定覆盖

每个目标视口保存两张像素基线：

| 视口 | 登录页 | 登录后看板 |
|---|---|---|
| 1366×768 | `login-1366x768-win32.png` | `dashboard-1366x768-win32.png` |
| 1440×900 | `login-1440x900-win32.png` | `dashboard-1440x900-win32.png` |
| 1920×1080 | `login-1920x1080-win32.png` | `dashboard-1920x1080-win32.png` |

截图保存在 `apps/admin-web/e2e/visual-baselines.spec.ts-snapshots/`，与测试一同纳入版本控制。基线不含账号、密码、令牌或真实个人信息。

## 自动断言

- 登录页主标题和登录卡片可见；
- `body` 与根节点 `scrollWidth === clientWidth`，三个视口均无页面级横向溢出；
- 登录后房屋资产稳定显示 359；
- 顶栏、侧栏和工作页签可见；
- 顶栏为 42px，展开侧栏为 160px，工作页签为 38px；
- 主区域从 x=160 开始，宽度严格等于 `viewportWidth - 160`；
- 截图关闭动画并隐藏输入光标，允许最多 0.5% 的像素级抗锯齿差异。

## 基线清单

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `dashboard-1366x768-win32.png` | 183787 | `669ab8c80c311838e3975cb4d973edac3395f68ee761e069dd12724913db962c` |
| `dashboard-1440x900-win32.png` | 191512 | `8da6e49ec8717b9d47ef6a5dee2a17d8351b0250c055e70d55248f3af04e4430` |
| `dashboard-1920x1080-win32.png` | 212994 | `ee19cb149c161e92194b9f0242d1cfcfd90dd23456662abdc3fab62c6972431a` |
| `login-1366x768-win32.png` | 102488 | `7451cdf3487c4cd5cc3e55f39df2198bc469f199991aaa4ce1c8382fda4f866b` |
| `login-1440x900-win32.png` | 105290 | `ce3d9f558051f2fe8bbaa2a8d50748957537768837cccf6c93ca038d90c70163` |
| `login-1920x1080-win32.png` | 121696 | `98f1cd04a5475af8d9a51dcf13b1180eed27b5b5464c0533879c3ca8aeb64eb3` |

## G2—G5 的 49 页受控基线

`page-catalog.visual.spec.ts` 从 `src/config/page-catalog.json` 读取唯一页面清单，在 1366×768、1440×900、1920×1080 三个视口逐页登录、路由、核对标题、等待加载层和字体稳定，再保存 49 × 3 = 147 张图片。图片位于 `apps/admin-web/e2e/page-catalog.visual.spec.ts-snapshots/`，命名为 `page-NN-视口-win32.png`。

| 视口 | 文件数 | 总字节 | 清单摘要 SHA-256 |
|---|---:|---:|---|
| 1366×768 | 49 | 5,424,331 | `4e5aa50c58d84e3f4bb1f7eb5eefc12c7aa16cf6799864ae3285277f8fe10d12` |
| 1440×900 | 49 | 5,949,050 | `245560602846e427933d4228aa6ed789aa21c815622e5b0b5a593348dd67b049` |
| 1920×1080 | 49 | 6,791,916 | `0782a6ce4f8aea4f189a4103a55f98d309bd12c14206e300da2733afc6407ba9` |

清单摘要的计算方式为：按文件名排序，为每个文件生成 `文件名:文件SHA-256`，以 LF 连接后再次计算 SHA-256。它用于确认 49 张图的集合完整性，不能替代逐图像素比对。

自动门禁同时包括：

- `page-catalog.smoke.spec.ts`：49/49 路由、最终 URL、标题、壳层、非“开发中”和页面级横向溢出；
- `page-catalog.visual.spec.ts`：147/147 当前实现像素基线，允许最多 0.8% 的抗锯齿差异；
- capability 工作区可用 `?__state=normal|loading|empty|forbidden|validation-error|request-error|conflict|partial-failure` 确定性演练 8 类验收状态；
- 代表性人工抽检覆盖页面 1 看板、2 组织管理、12 收银台、30 收缴率报表、48 银行信托、49 访客记录，未发现空白、裁切、重叠或异常横向滚动。

G3 将页面 6 房产信息、7 客户信息、20 网格管理、21 车位信息接入真实领域数据与专用工作台，受审更新这 4 页在三个视口下的 12 张领域图片。最终并发复跑又发现 IAM 软停用历史会污染截图：页面 2/3/4/18/19 现默认展示 ACTIVE，组织树遵守同一状态筛选，视觉采集前移开鼠标避免溢出提示进入截图，因此更新 15 张 IAM 图片。为强制把阈值内的旧 IAM 内容替换为精确现状，执行过一次全量精确重录，另有 9 张图片产生仅字节级/抗锯齿刷新；人工差异检查未发现业务内容或布局变化。G3 合计变更 36/147 张图片。

页面 2/3/4/18/19 已人工检查有效组织树、有效角色/人员/企业/岗位列表；页面 6/7/20/21 已人工检查资产树、查询区、实数汇总、操作列和空状态。更新后无更新纯比对 147/147 通过，并与会写数据的 E2E 以 5 worker 并发全量复跑 13/13 通过。

G4 将页面 17 从浏览器合成能力页替换为真实迁移中心，受审更新三个视口的 3 张图片。页面展示真实批次查询、JSON 上传、32+1 验收样本、模板、状态和操作入口；详情抽屉另由浏览器生命周期测试覆盖五层计数、隔离错误、映射、对账和回滚。为避免依法保留的迁移审计批次使截图随测试次数变化，视觉测试只对该列表使用确定性空响应，业务 API 仍由集成测试和专用 E2E 实测。页面 17 的 1440×900 图片已经人工检查，无空白壳层、裁切、重叠或横向溢出；更新后纯比对 147/147、5 worker 全量 14/14 通过。

G5 将页面 8—11 从结构演练升级为真实费用定义/标准/分配、周期应收和临时应收工作区，共受审更新 11/147 张图片。页面 8 展示财税属性、舍入和标准版本，页面 9 展示先预览后分配及有效期，页面 10 展示完整试算快照和异步任务，页面 11 展示独立临时费用明细和金额校验。人工检查覆盖四页真实数据、三视口页面级无横向溢出和 V14 临时费用选项；应收任务列表在视觉用例中固定为空以避免历史任务时间戳漂移，真实完成/失败/对账状态由专用 G5 E2E 覆盖。更新后纯比对 147/147、串行共享后端全量 15/15 通过。

完整 Playwright 使用单 worker，因为 IAM、迁移和应收生命周期共享一个持久验收后端；这避免另一个用例在截图期间产生瞬时对象。串行化不是性能结论，未来并行 CI 必须为每个 worker 提供独立后端和数据库。

这 147 张图是 G2—G5 的“当前实现防回退基线”，证明 49 页均有可追踪页面且可稳定渲染，并记录 G3 四个真实档案工作区、G4 迁移中心和 G5 费用/应收工作区；它不等同于目标站逐像素相似度结论，也不表示 G6–G9 的业务闭环已经完成。

## 运行与受控更新

先按 `runbook.md` 启动 API/Web，并只通过本地忽略的环境变量提供 E2E 管理员配置：

```powershell
npm run test:visual
npm run test:catalog-smoke
npm run test:catalog-visual
```

只有经过人工检查并确认 UI 变更符合验收卡时，才允许更新基线：

```powershell
npx playwright test e2e/visual-baselines.spec.ts --update-snapshots
npx playwright test e2e/page-catalog.visual.spec.ts --update-snapshots
npm run test:visual
npm run test:catalog-visual
```

更新后必须重新计算本文件的字节数和 SHA-256，并在同一提交中说明视觉变化原因。Linux/macOS 字体栅格不同，当前 `win32` 基线只作为受控 Windows 验收门禁；后续远程 CI 若增加视觉作业，应使用固定 Windows 镜像或独立维护平台基线。
