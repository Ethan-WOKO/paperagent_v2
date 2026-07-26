# 当前开发状态与交接

最后核对时间：2026-07-26（Asia/Shanghai）

本文件是可恢复的工作状态，不覆盖 `AGENTS.md`、`ARCHITECTURE.md` 或 ADR 中的冻结决策。
新主对话必须先完整读取所有入口文档，再用只读 Git/GitHub 命令确认外部状态是否变化。

## 安全停止点

- Repository：`Ethan-WOKO/paperagent_v2`
- 最新合并的 Wave 3 功能 PR 是 [#93](https://github.com/Ethan-WOKO/paperagent_v2/pull/93)，对应
  [Issue #92](https://github.com/Ethan-WOKO/paperagent_v2/issues/92) 已关闭；fenced active-Step
  pause、fail、cancel facts 已完成。PR #93 的最终 fixed head 是
  `f794daf5be54b33138eb96fc0ac3846434c2bb1a`，GitHub merge commit 是当前 `main` 与
  `origin/main` 的 `cf77d58399418bb8656ad2a517eb9f1f624e1104`。
- [Issue #94](https://github.com/Ethan-WOKO/paperagent_v2/issues/94) 只刷新本交接文档；它的唯一
  `baseCommit` 是 `cf77d58399418bb8656ad2a517eb9f1f624e1104`，不是 Runtime 实现工作流。
- 本次文档 PR 合并前，不得创建或实现 fenced replan。合并后，必须记录该文档 PR 的 GitHub
  merge commit，并仅以该提交创建并冻结 fenced replan Issue；不得复用
  `cf77d58399418bb8656ad2a517eb9f1f624e1104` 作为该功能 Issue 的 `baseCommit`。

如果 GitHub 状态与本节不一致，以重新读取的 GitHub 状态为准，并先判断是谁、为什么改变，
不得根据编号或本地旧分支猜测。

## 已合并的 PR #93 能力

PR #93 由 Persistence authority 仅为当前唯一 `ACTIVE` Step 提供 lease/fence 保护的显式
pause、fail、cancel facts：

- 通过后原子写入对应的 `PAUSED`、`FAILED` 或 `CANCELLED` Step/Plan checkpoint、Event、永久
  marker、H0 mutation link 和 mutation head；不改变 Plan revision、CompletionFact、receipt、
  effect、Workspace 或 lease。
- marker-backed 精确回放会先从 bootstrap/start/marker links 和 event projections 重建持久
  provenance，再决定 replay；marker 孤立、链接或投影损坏时 fail-closed，且精确回放不访问
  Clock。
- execution-start marker 冻结当时的 current Plan，使 pre-start revision recovery 和可变 Plan projection
  被移除后的 replay 仍可验证。
- 不实现 resume、retry、replan、recovery 行为、Step kernel、Agent Loop、Runtime composition、
  API/UI、外部 effect、V1 或生产数据库适配器。

验证证据：

- 定向行为、边界、并发与 recovery：25 focused tests，0 failures，0 errors。
- 受影响模块聚合：`agent-contracts` 88 tests、`agent-persistence` 231 tests，均为 0 failures、
  0 errors。
- 定向 Runtime 回归：`ExecutionStartRecoveryIntegrationTest` 4 tests，0 failures，0 errors。
- `git diff --check` 通过；两个对应最终 fixed head 的 GitHub CI `verify` check 均已成功。
- 无 `.env`、密钥、用户文件、V1 复制或生产数据库适配器。

## 恢复后的第一动作

1. 核对本次文档 PR 的状态；若尚未合并，主对话先完成 fixed-head 审查和 CI 门禁，不得创建或
   实现下一功能 Issue。只有主对话可以决定 Ready 和合并，实施子对话不得合并。
2. 本次文档 PR 合并后运行 `git fetch origin`，记录其 GitHub merge commit，并确认本地 `main` 与
   `origin/main` 都指向该提交；该提交才是 fenced replan Issue 的唯一 `baseCommit`。
3. 主对话只创建并冻结 fenced replan Issue，写明 baseCommit、objective、nonGoals、ownedPaths、
   frozenContracts、dependencies、acceptanceTests、requiredChecks、stopConditions 和 mergeOrder；
   文档 PR 不实现 replan。
4. 只有 Issue 冻结后，实施子对话才能从该 `baseCommit` 创建独立 worktree 和 `codex/` 分支，
   实现、测试、提交、推送并创建 Draft PR；子对话不得合并。

## 下一能力与严格顺序

下一动作只能是创建并冻结 **fenced replan** Issue；本次文档 PR 合并前不得创建它，合并后也必须
先完成冻结，才可开始实现。

后续能力按以下顺序逐个 Issue/PR 推进，前一个冻结并合并前不实现依赖它的下游：

1. fenced replan。
2. atomic Step Recovery inspection。
3. Runtime Step Recovery composition。
4. single-turn Step kernel。
5. bounded Step Agent Loop。
6. bounded repair/replan。

V2 API、Web UI、用户接受/拒绝和新 ProjectVersion 属于 Wave 4。Recovery 的 Runtime
composition 属于 Wave 3，其用户入口/展示属于 Wave 4。V1 Project、版本、Provider、UI 的
选择性迁移和真实用户矩阵属于 Wave 5；V1 仍一律 `UNASSESSED`，不得直接复制。

## 尚未实现，禁止误报

- 没有完整 Step Agent Loop、effect 执行、resume/retry、fenced replan、recovery 或 repair/replan。
- 没有产品 API、Web UI、登录到结果的用户旅程。
- 没有真实 Model/Sandbox/网络/密钥隔离或耐久数据库。
- 没有迁移 V1 代码或 V1 数据兼容层。
- `.env` 即使存在于其他本地目录，也不得读取、复制、提交或输出。真实密钥测试必须等明确
  的安全注入边界和相应 Issue。

## 主对话与子对话运行纪律

- 主对话负责 Issue、契约冻结、审查、合并顺序和阶段验收；不承担普通功能实现。
- 实施子对话负责独立 worktree/branch 中的实现、测试、commit、push 和 Draft PR，永不合并。
- 子对话必须在 milestone 完成、异常停止或阻塞时主动汇报；主对话定期轮询。
- 多份汇报同时到达时排队逐项审查；修改意见只返回对应子对话。
- 已完成/中断的子对话需要可触发新轮次的 follow-up 才会恢复，普通消息不够。
- 无可观察进展且不响应状态检查时，主对话应中断并恢复，必要时把纯协调动作安全接管，不能
  让开发静默停止。
- 只因产品决策、身份/权限/OTP、成本或不可逆/生产范围扩张而等待用户。

## 冷启动自检答案

仅凭仓库文档，新主对话应能回答：

- 当前在哪里：Wave 3 进行中；PR #93 已合并、Issue #92 已关闭，fenced active-Step
  pause/fail/cancel facts 已完成；Issue #94 只刷新这份交接。
- 先做什么：完整读取入口文档，再重新核对 Git/GitHub。若本次文档 PR 尚未合并，先完成其
  审查和门禁，不能创建或开始下游实现。
- 下一功能是什么：本次文档 PR 合并后，以其 GitHub merge commit 为唯一 `baseCommit` 创建并
  冻结 fenced replan Issue；不能从交接 PR 开始实现它。
- 不能做什么：不能复制 V1、读取 `.env`、跳过 Issue/worktree/Draft PR、让子对话合并，或
  声称 API/UI/Agent Loop 已完成。
- 如何继续：严格使用 `DEVELOPMENT_PROCESS.md` 的汇报、轮询、fixed-head 审查、CI 和顺序合并
  门禁。
