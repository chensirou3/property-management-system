# G1 登录与应用壳层视觉基线

基线日期：2026-08-25
运行平台：Windows / Chrome channel / Playwright 1.57
测试入口：`apps/admin-web/e2e/visual-baselines.spec.ts`

## 固定覆盖

每个目标视口保存两张像素基线：

| 视口 | 登录页 | 登录后项目看板 |
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
| `dashboard-1366x768-win32.png` | 181059 | `d63011963388bf42e13a2d3147b6f7463deee5879ca0e076b05aff301d1fad24` |
| `dashboard-1440x900-win32.png` | 191512 | `8da6e49ec8717b9d47ef6a5dee2a17d8351b0250c055e70d55248f3af04e4430` |
| `dashboard-1920x1080-win32.png` | 212994 | `ee19cb149c161e92194b9f0242d1cfcfd90dd23456662abdc3fab62c6972431a` |
| `login-1366x768-win32.png` | 102488 | `7451cdf3487c4cd5cc3e55f39df2198bc469f199991aaa4ce1c8382fda4f866b` |
| `login-1440x900-win32.png` | 105290 | `ce3d9f558051f2fe8bbaa2a8d50748957537768837cccf6c93ca038d90c70163` |
| `login-1920x1080-win32.png` | 121696 | `98f1cd04a5475af8d9a51dcf13b1180eed27b5b5464c0533879c3ca8aeb64eb3` |

## 运行与受控更新

先按 `runbook.md` 启动 API/Web，并只通过本地忽略的环境变量提供 E2E 管理员配置：

```powershell
npm run test:visual
```

只有经过人工检查并确认 UI 变更符合验收卡时，才允许更新基线：

```powershell
npx playwright test e2e/visual-baselines.spec.ts --update-snapshots
npm run test:visual
```

更新后必须重新计算本文件的字节数和 SHA-256，并在同一提交中说明视觉变化原因。Linux/macOS 字体栅格不同，当前 `win32` 基线只作为受控 Windows 验收门禁；后续远程 CI 若增加视觉作业，应使用固定 Windows 镜像或独立维护平台基线。
