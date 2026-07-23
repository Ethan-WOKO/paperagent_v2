# Wave 2 阶段验收

## 验收结论

`Wave 2 / 独立基础能力` 已完成并通过阶段验收；项目进入
`Wave 3 / Runtime 主链` 的规划阶段。Wave 2 提供的是可独立验证的基础端口、参考实现和
测试 Harness，不是 Runtime 或产品闭环。

- 本验收分支的精确基线：`b28b6cc5b8fd498a797486511bb321152ebc4e06`
  （PR #17 的 GitHub 合并提交）。
- Wave 3 下游基线：`PENDING`。本验收 PR 尚未合并，不能预先填写不存在的 SHA。
  合并后，GitHub 为本验收 PR 生成的合并提交是所有 Wave 3 Issue 唯一有效的
  `baseCommit`。
- 禁止使用本验收分支提交、任一 Wave 2 PR 的分支提交或 PR #17 的合并提交代替上述
  Wave 3 基线。

## 已合并 PR 与提交矩阵

分支提交是被审查和执行 latest-head CI 的内容；合并提交才是该 PR 进入 `main` 的事实。
两者不得混用。

| PR | 能力 | 被验收的最新分支提交 | GitHub 合并提交 | latest-head CI |
|---|---|---|---|---|
| [#11](https://github.com/Ethan-WOKO/paperagent_v2/pull/11) | Workspace 物化、文件操作和 diff | `b01ecc4eabcba313aef1e81d7ae86213dbeba3ee` | `56562c018159413e9d7a355d518213628276dffc` | 两个 `ci / verify` 均 `SUCCESS` |
| [#10](https://github.com/Ethan-WOKO/paperagent_v2/pull/10) | Persistence 端口与内存参考适配器 | `957e2757de9ec07fafb37ee921619468604a96ce` | `cdcb3cb8c1615151f7ac7c9734dcedd1f19b3314` | 两个 `ci / verify` 均 `SUCCESS` |
| [#9](https://github.com/Ethan-WOKO/paperagent_v2/pull/9) | Model Provider 端口与脚本适配器 | `fcf4085ddb4bbddb3e1d9b70c6871d6282778b62` | `fcf85ddeb01847e4e3d156a460dc41635c3fb54f` | 两个 `ci / verify` 均 `SUCCESS` |
| [#13](https://github.com/Ethan-WOKO/paperagent_v2/pull/13) | Workspace replace/link-swap 加固 | `418d56c7563116cd3de8a0d1f3d9088d3fd25857` | `1c8a944cf892e850734d98c40a0de475e3d421a2` | 两个 `ci / verify` 均 `SUCCESS` |
| [#15](https://github.com/Ethan-WOKO/paperagent_v2/pull/15) | Sandbox 端口与脚本适配器 | `81c126c3e161c908830538e77c536813739404c8` | `ec83ca9e3efd0222a5651c150944b31f624030e3` | 两个 `ci / verify` 均 `SUCCESS` |
| [#17](https://github.com/Ethan-WOKO/paperagent_v2/pull/17) | 确定性基础组合 E2E Harness | `96e4cdae7ae1acaf660693ec88cf03310601ea67` | `b28b6cc5b8fd498a797486511bb321152ebc4e06` | 两个 `ci / verify` 均 `SUCCESS` |

Git 历史核对还确认：每个表列合并提交的第二父提交都是同一行的最新分支提交。

## 已实现能力

### Persistence

- 提供 TaskFrame、Plan/revision、event、receipt、checkpoint、lease 和 idempotency 端口。
- 提供线程安全、确定性的内存参考适配器和稳定错误码。
- 覆盖 create-once、append-only、checkpoint CAS、lease fencing、幂等状态转换及并发失败。
- 所有身份、时间、过期时间、token 和 fingerprint 由调用方提供。

这是行为参考实现，不是数据库适配器，也不承诺进程重启后的耐久性。

### Workspace

- 通过公开端口提供 ProjectVersion 物化、list/stat/read、create/replace/delete/move、diff 和
  cleanup。
- 本地 JDK 实现校验 source hash、路径、容量、文件数量和 workspace/source 隔离。
- diff 顺序确定，返回集合与 source snapshot 保持不可变。
- link/reparse 和 provider-root 检查覆盖已观测到的路径逃逸与替换场景。

### Model Provider

- 提供框架和 SDK 无关的单轮同步 Model Provider 端口。
- 明确区分 `ProposedToolCall` 与已经执行的契约 `ToolCall`。
- 确定性脚本适配器支持成功、失败、取消、错序、耗尽、未消费断言和并发 exactly-once
  消费。

### Sandbox

- 提供一次尝试的 provider-neutral Sandbox 请求、结果、失败和协议校验边界。
- 请求携带调用方提供的 `ToolCallId`、`WorkspaceRef`、argv、逻辑 `SecretRef`、
  `ExecutionProfile` 和预取消信号。
- 确定性脚本适配器不启动进程或访问网络，可稳定验证失败、不消费错误脚本步骤和并发消费。

### 确定性基础组合 Harness

- 测试专用 Harness 使用公开 API 组合 Contracts、Persistence、Workspace、Model Provider
  和 Sandbox。
- 同一组调用方提供的 ID、时间和 typed references 能跨边界保持一致。
- 成功、失败和两个独立 Harness 实例之间的 persistence、script observations、Workspace
  和 source 均保持隔离。
- Harness 明确证明 model 输出仍是 proposal；它没有把 proposal 转换为 `ToolCall`，也没有
 伪造 receipt、event 或 checkpoint。

## 主对话审查中确认的修复

- Persistence checkpoint 使用严格 CAS：即使待保存 checkpoint 与现值完全相同，只要
  `expectedVersion` 已过期，也必须返回 `STALE_VERSION`，不得当作幂等 replay。
- Workspace replace/restore 的 prior-content backup 改为有界 `NOFOLLOW_LINKS` 读取和
  `CREATE_NEW` staging 文件，并在 backup、move 和 restore 前重新校验边界；link swap
  不得读取或覆盖外部目标。
- JDK-only Workspace 检查缩小了攻击窗口，但无法消除敌对跨进程参与者在最后一次校验与
  文件系统调用之间替换父路径组件的 TOCTOU 风险。
- Model Provider 与 Sandbox 的脚本适配器在 mismatch、out-of-order 或 pre-cancel 时
  不消费错误的下一脚本步骤，并返回稳定失败码。
- Sandbox 保留非首 argv token 和普通 environment value 中的空字符串或纯空白；
  `argv[0]` 仍必须非空白，所有值继续拒绝 NUL 和超限长度。
- E2E Harness 只证明公开边界的可组合性、隔离性和失败传播，不实现或模拟 Runtime 把
  proposal 转换成执行事实的过程。

## 构建、测试与 CI 证据

在精确基线 `b28b6cc5b8fd498a797486511bb321152ebc4e06` 创建的验收分支上执行：

```text
mvn -B -ntp clean verify
```

本地 Windows 结果：

- 10-module reactor：`BUILD SUCCESS`。
- 161 tests，0 failures，0 errors。
- 模块计数：Contracts 58、Persistence 28、Workspace 26、Sandbox 28、Provider 16、
  E2E Harness 5。
- Workspace 的 5 个真实符号链接测试因本机无法创建符号链接而按既有 assumption 跳过；
  其他测试没有跳过。

GitHub 核对结果：

- 2026-07-24 读取 PR #9、#10、#11、#13、#15 和 #17 的 merge 状态、merge commit、
  latest head 和 check rollup；六个 PR 均为 `MERGED`，表列 SHA 均匹配，每个 latest head
  的两个 `ci / verify` 均为 `SUCCESS`。
- PR #17 的 Ubuntu `verify` 日志进一步显示 `WorkspaceLinkSecurityTest` 5 tests、
  0 failures/errors/skips，Workspace 模块 26 tests、0 skips，证明 Windows 跳过项在该
  Linux CI 运行中实际执行。

CI 状态和日志证明相应提交在当时的 Ubuntu job 中通过；它们不等同于数据库耐久性、
生产安全审计、真实 Provider/Sandbox 集成或长期保留的测试制品。

## 跳过项与平台处理

- Wave 2 新增的 Persistence、Provider、Sandbox 和 E2E Harness 测试在本次本地验收中
  没有跳过。
- 仅 Workspace 的 5 个真实符号链接测试在当前 Windows 主机跳过；原因是主机不具备创建
  符号链接的能力，而不是功能断言被禁用。
- Ubuntu latest-head CI 已执行这 5 个测试且全部通过。后续改动若触及 Workspace 安全
  边界，仍必须保留 Linux 实链接覆盖，不能只依赖 Windows 结果。

## 明确未实现范围与剩余风险

- `agent-runtime` 仍为空边界；二元 Router、TaskFrame builder、Plan executor、
  Step Agent Loop、repair/replan、暂停、取消、恢复和 Runtime 幂等协调尚未实现。
- 内存 Persistence 不跨进程重启持久化；数据库 schema、事务模型和线协议尚未确定。
- Sandbox 没有真实进程、容器、E2B、网络、CPU、内存、进程数或密钥解析/隔离能力。
- Model Provider 没有真实模型、SDK、streaming、wire mapping、retry/backoff、凭据或
  live-account 集成。
- Workspace 的 JDK path-based 防护不能宣称消除了敌对跨进程 TOCTOU；Runtime/Sandbox
  仍须保持 host path 私有并控制并发访问。
- Harness 不是 Runtime/coordinator，不执行 proposal，也不生成 receipt、event、
  checkpoint、diff apply 或新 ProjectVersion。
- 产品 API、UI、登录、账号、真实网络、用户验收旅程和 ProjectVersion 接受/拒绝闭环均
  未实现，本验收不表示产品已可用。
- `docs/MIGRATION_MAP.md` 在本验收中保持不变。V1 能力继续维持该文件已有的
  `UNASSESSED`、`REWRITE`、`DROP` 或 `DEFER` 结论；没有新增评估、复用、复制或迁移。

## 下游基线规则

本验收 PR 必须先通过主对话审查和两项 latest-head CI，再由主对话合并。合并发生前不得
启动任何 Wave 3 Runtime 实现 Issue。

合并后，主对话必须从 GitHub 读取本验收 PR 的实际 merge commit，并把该完整 SHA 写入
每个 Wave 3 Issue 的 `baseCommit`。在此之前，下游基线保持 `PENDING`；不得猜测、推导或
用分支 head 代替。
