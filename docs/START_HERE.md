# 从这里开始

## 为什么建立 V2

V1 已经具备 Project 上传、版本、Candidate、沙箱、持久化 Plan 和结果展示，但通用 Agent 核心逐渐形成以下问题：

- 后端规定过细的工具调用和 Plan 形状。
- Router、Planner、Evidence、Candidate、Sandbox 和 Final Synthesis 高度耦合。
- 中间 Evidence 格式可能提前终止本可恢复的任务。
- Agent 在隔离环境中的读写、执行、依赖和联网能力不足。
- 大量逻辑集中在少数超大 Service，导致修改和并行开发困难。
- 自动测试偏向固定内部路径，真实模型换一种表达就可能暴露新断层。

V2 不继续修补旧 Runtime。它重新实现 Agent 核心，同时选择性迁移经过审查的成熟外围能力。

## 已冻结的方向

1. 顶层只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
2. 需要 Project、工具或副作用的请求统一进入持久化 Plan。
3. TaskFrame 固定“完成什么”，Plan 和 Step 决定“怎样完成”。
4. Step 内允许模型自主读写、执行、测试、安装依赖和有界修复。
5. Plan 支持 checkpoint、暂停、取消、恢复和 bounded replan。
6. Agent 在隔离 Workspace 内拥有完整修改能力。
7. 原 ProjectVersion 保持不可变；用户接受 diff 后才生成新版本。
8. 网络和密钥通过 ExecutionProfile 分级提供。
9. 命令、文件、网络、stdout、stderr、diff 和 receipt 自动进入事件日志。
10. 不再要求后端预先预测模型每一次工具调用。

## 当前阶段

`Wave 0 / Bootstrap`、`Wave 1 / Runtime Contracts` 和 `Wave 2 / 独立基础能力` 已完成。
`Wave 3 / Runtime 主链` 正在按依赖顺序实现，已经不再是纯规划状态。

已完成并合并的 Wave 3 基础切片包括二元 Router、TaskFrame/Plan/Checkpoint 冻结、原子
Persistence bootstrap、execution start/recovery、Workspace 物化与校验、Plan execution
context 及其 Runtime composition、committed-H0 Step activation candidate materialization，以及 Runtime
Step activation composition（PR #77，Issue #76）、provider-neutral effect identity/replayability 与
durable intent（PR #81，Issue #80）、provider-neutral fenced effect progress/final result persistence
与 Receipt ownership（PR #85，Issue #84）、fenced Step completion 与 append-only revision
（PR #89，Issue #88），以及 fenced active-Step pause、fail、cancel facts（PR #93，Issue #92）。

PR #93 的 final fixed head 是 `f794daf5be54b33138eb96fc0ac3846434c2bb1a`，GitHub merge commit 是
当前 `main` 与 `origin/main` 的 `cf77d58399418bb8656ad2a517eb9f1f624e1104`。其修正保证
marker-backed exact replay 先重建 durable provenance、损坏时 fail-closed；execution-start marker 冻结
current Plan，使 pre-start revision recovery 与 Plan projection 移除后的 replay 仍可验证。
验证为 25 个 focused tests、`agent-contracts` 88 tests、`agent-persistence` 231 tests 和定向
`ExecutionStartRecoveryIntegrationTest` 4 tests，均为 0 failures、0 errors；`git diff --check`
和两个最终 fixed-head CI `verify` check 均已通过。当前安全停止点、精确提交、Issue/PR 状态、
测试证据和下一切片见 [当前开发状态](ACTIVE_DEVELOPMENT.md)。

仍未完成的关键产品能力：

- fenced replan、Step Recovery 主链。
- 单轮 Step kernel、bounded Step Agent Loop 和 bounded repair/replan。
- 真实模型或执行后端、耐久数据库适配器和生产级隔离。
- 产品 API、Web UI、用户接受/拒绝与新 ProjectVersion 闭环。
- 真实登录到结果的自动化用户旅程。
- V1 选择性迁移；V1 仍按 `MIGRATION_MAP.md` 默认 `UNASSESSED`，禁止直接复制。

当前内存 Persistence 不承诺进程重启耐久性；确定性 Sandbox 不等同于真实进程、网络、资源
或密钥隔离。数据库 schema 和线协议也尚未冻结。

## 下一步

1. 完整读取 `AGENTS.md`、本文件、`ARCHITECTURE.md`、`MIGRATION_MAP.md`、
   `DEVELOPMENT_PROCESS.md`、`ROADMAP.md` 和 [当前开发状态](ACTIVE_DEVELOPMENT.md)，再用 Git/GitHub
   核对记录的 `main`/`origin/main`、已合并 PR、已关闭 Issue 和是否已有开放的后续实现 Issue；
   外部状态可能在文档写入后变化，不得猜测。
2. 本次交接刷新 PR 合并后，运行 `git fetch origin`，记录该 PR 的 GitHub merge commit，并确认
   本地 `main` 与 `origin/main` 都指向该提交。该提交才是下一功能 fenced replan Issue 的唯一
   `baseCommit`；不能复用 `cf77d58399418bb8656ad2a517eb9f1f624e1104`。
3. 只以该 `baseCommit` 发布并冻结 fenced replan Issue，明确目标、非目标、owned paths、冻结契约、
   依赖、验收、必跑检查、停止条件和合并顺序。文档 PR 不创建 worktree 或开始实现 replan。
4. 只有 Issue 冻结后，实施子对话才使用独立 worktree、`codex/` 分支和 Draft PR；它负责实现、
   测试、commit、push，主对话固定 head 审查、检查 CI 并决定合并。后续严格按 fenced replan、
   atomic Step Recovery inspection、Runtime Step Recovery composition、single-turn Step kernel、
   bounded Step Agent Loop、bounded repair/replan 的顺序推进。

## 新主对话建议指令

> 完整读取 `AGENTS.md`、`docs/START_HERE.md`、`docs/ARCHITECTURE.md`、
> `docs/MIGRATION_MAP.md`、`docs/DEVELOPMENT_PROCESS.md`、`docs/ROADMAP.md` 和
> `docs/ACTIVE_DEVELOPMENT.md`。你是 PaperAgent V2 主对话，负责架构冻结、Issue、PR
> 审查、合并顺序和阶段验收，不直接承担普通功能实现。先核对 Git/GitHub 当前状态，再从
> `ACTIVE_DEVELOPMENT.md` 的停止点继续；不要复制 V1 代码或读取 `.env`。
