# G1–G2 登录、壳层与 49 页视觉基线

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

## G2 的 49 页结构基线

`page-catalog.visual.spec.ts` 从 `src/config/page-catalog.json` 读取唯一页面清单，在 1366×768、1440×900、1920×1080 三个视口逐页登录、路由、核对标题、等待加载层和字体稳定，再保存 49 × 3 = 147 张图片。图片位于 `apps/admin-web/e2e/page-catalog.visual.spec.ts-snapshots/`，命名为 `page-NN-视口-win32.png`。

| 视口 | 文件数 | 总字节 | 清单摘要 SHA-256 |
|---|---:|---:|---|
| 1366×768 | 49 | 5,595,626 | `d12028ef993470be3763eb99947c1f7dd06e938225067c80314902dd4435df98` |
| 1440×900 | 49 | 6,138,566 | `d3530f3786a55652e7b291232b4a3b052c6f88b0c199237b988e6de9918962c5` |
| 1920×1080 | 49 | 6,942,629 | `8aca8c603452f94f19060aa8c9751e6015df17dad08c53deb37c9aa347ed2419` |

清单摘要的计算方式为：按文件名排序，为每个文件生成 `文件名:文件SHA-256`，以 LF 连接后再次计算 SHA-256。它用于确认 49 张图的集合完整性，不能替代逐图像素比对。

自动门禁同时包括：

- `page-catalog.smoke.spec.ts`：49/49 路由、最终 URL、标题、壳层、非“开发中”和页面级横向溢出；
- `page-catalog.visual.spec.ts`：147/147 当前实现像素基线，允许最多 0.8% 的抗锯齿差异；
- capability 工作区可用 `?__state=normal|loading|empty|forbidden|validation-error|request-error|conflict|partial-failure` 确定性演练 8 类验收状态；
- 代表性人工抽检覆盖页面 1 看板、2 组织管理、12 收银台、30 收缴率报表、48 银行信托、49 访客记录，未发现空白、裁切、重叠或异常横向滚动。

这 147 张图是 G2 的“当前结构防回退基线”，证明 49 页均有可追踪页面且可稳定渲染；它不等同于目标站逐像素相似度结论，也不表示 G3–G9 的业务闭环已经完成。

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
