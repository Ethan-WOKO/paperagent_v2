# V2 路线图

## Wave 0：Bootstrap

状态：`COMPLETE`

- 冻结产品范围。
- 冻结核心架构原则。
- 建立迁移规则和开发流程。
- 不迁移 V1 业务代码。

完成标准：Bootstrap Draft PR 通过审查并合并。

## Wave 1：Runtime Contracts

状态：`COMPLETE`

- 建立模块骨架。
- 定义 TaskFrame、Route、Plan、Revision、Step、Checkpoint。
- 定义 Workspace、Tool、Event、Receipt、Diff 和 ExecutionProfile。
- 只实现纯契约、校验和契约测试。

## Wave 2：独立基础能力

状态：`COMPLETE`

可在契约冻结后并行：

- Plan persistence/checkpoint/lease。
- Workspace 物化、文件读写和 diff。
- Sandbox 命令、输出、网络和资源。
- Provider ports 和测试 Provider。
- E2E Harness 骨架。

完成标准：独立模块 PR、Workspace 安全加固 PR 和确定性基础组合 Harness 均已通过主对话
审查、最新提交 CI 和阶段验收。详细证据见 [Wave 2 阶段验收](acceptance/WAVE_2.md)。

该状态只表示独立基础边界完成，不表示 Runtime、真实模型、真实执行后端、产品 API 或 UI
已经实现。

## Wave 3：Runtime 主链

状态：`IN_PROGRESS`

已完成并合并：

- 二元 Router、TaskFrame 构建与冻结、初始 Plan/revision/checkpoint。
- 完成事实历史、下一 Plan revision、全局事件游标和 checkpoint latest-revision 约束。
- 原子 Persistence bootstrap、lease 时间、execution start、fresh-execution gate。
- execution-start recovery inspection/materialization/runtime composition。
- Step activation Persistence authority。
- Workspace materialization specification、verified materialization 与执行 mutation authority。
- Plan execution context Persistence authority 与 Runtime composition。
- committed-H0 Step activation candidate materialization。
- Runtime Step activation composition（PR #77，Issue #76）。
- provider-neutral effect identity/replayability 与 durable intent（PR #81，Issue #80）。
- provider-neutral fenced effect progress/final result persistence 与 Receipt ownership（PR #85，Issue #84）。
- fenced Step completion 与 append-only revision（PR #89，Issue #88）。
- fenced active-Step pause、fail、cancel facts（PR #93，Issue #92）。

当前安全停止点：

- PR #93 已合并，Issue #92 已关闭；其 final fixed head 为
  `f794daf5be54b33138eb96fc0ac3846434c2bb1a`，GitHub merge commit 为当前 `main` 和
  `origin/main` 的 `cf77d58399418bb8656ad2a517eb9f1f624e1104`。
- 审查修正使 marker-backed 精确 replay 先重建 durable provenance，并在 marker 或相关持久
  链损坏时 fail-closed；execution-start marker 冻结 current Plan，使 pre-start revision recovery 和
  Plan projection 移除后的 replay 保持有效。
- 证据为 25 个 focused tests、`agent-contracts` 88 tests、`agent-persistence` 231 tests、
  `ExecutionStartRecoveryIntegrationTest` 4 tests，均为 0 failures、0 errors；`git diff --check`
  通过，两个最终 fixed-head CI `verify` check 成功。
- 当前是 Issue #94 的文档刷新。该文档 PR 合并后，其 GitHub merge commit 才是创建并冻结
  fenced replan Issue 的唯一 `baseCommit`；文档 PR 不实现 replan。精确状态见
  [当前开发状态](ACTIVE_DEVELOPMENT.md)。

仍待按依赖顺序完成：

- fenced replan。
- atomic Step Recovery inspection 与 Runtime Step Recovery composition。
- single-turn Step kernel、bounded Step Agent Loop、bounded repair/replan。

Wave 3 完成前不得把这些切片重新集中到单个大 Service，也不得用未审查的 V1 Runtime
填补缺口。V1 一律 `UNASSESSED`，除非后续独立迁移 Issue 在 `MIGRATION_MAP.md` 记录结论。

## Wave 4：产品闭环

- V2 产品 API 与 Web UI。
- Final Synthesis。
- Plan、事件、receipt 和 diff API。
- 用户确认、拒绝和新 ProjectVersion。
- 前端主流程。
- Recovery composition 的 Runtime 基础属于 Wave 3；面向用户的恢复入口和展示属于 Wave 4。

## Wave 5：选择性迁移与真实验收

- 按 `MIGRATION_MAP.md` 逐项审查 V1 能力。
- 迁移有价值的 Project、版本、Provider 和 UI 能力。
- 完成固定真实用户矩阵。
- 决定 V1 数据导入和旧仓库归档时间。
