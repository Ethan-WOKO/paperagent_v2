# 当前开发状态与交接

最后核对时间：2026-07-26（Asia/Shanghai）

本文件是可恢复的工作状态，不覆盖 `AGENTS.md`、`ARCHITECTURE.md` 或 ADR 中的冻结决策。
新主对话必须先完整读取所有入口文档，再用只读 Git/GitHub 命令确认外部状态是否变化。

## 安全停止点

- Repository：`Ethan-WOKO/paperagent_v2`
- 最新合并的 Wave 3 功能 PR 是 [#97](https://github.com/Ethan-WOKO/paperagent_v2/pull/97)，对应
  [Issue #96](https://github.com/Ethan-WOKO/paperagent_v2/issues/96) 已关闭；fenced append-only
  Plan replan 已完成。PR #97 的 final fixed head 是
  `ad3e2acf2bdc782fd6af779a2d425410395400d4`，GitHub merge commit 是当前 `main` 与
  `origin/main` 的 `76ab532d769c8e4b83a78ae2c583c046d86f545b`。
- [Issue #98](https://github.com/Ethan-WOKO/paperagent_v2/issues/98) 只刷新本交接文档；它的唯一
  `baseCommit` 是 `76ab532d769c8e4b83a78ae2c583c046d86f545b`，不是 Runtime 实现工作流。
- 本次文档 PR 合并前，不得创建或实现 atomic Step Recovery inspection。合并后，必须记录该文档 PR
  的 GitHub merge commit，并仅以该提交创建并冻结名为 **atomic Step Recovery inspection** 的 Issue；
  不得复用 `76ab532d769c8e4b83a78ae2c583c046d86f545b` 作为该功能 Issue 的 `baseCommit`。

如果 GitHub 状态与本节不一致，以重新读取的 GitHub 状态为准，并先判断是谁、为什么改变，
不得根据编号或本地旧分支猜测。

## 已合并的 PR #97 能力

PR #97 的 Persistence authority 只提供狭窄的 Steps-between replan：

- 源 Plan 必须为 `ACTIVE`，且不得存在 `ACTIVE`、`PAUSED`、`FAILED` 或 `CANCELLED` Step；该限制
  排除了 active Step、暂停、失败和取消中的执行恢复路径。
- 成功后只追加新的 Plan revision；已完成 CompletionFacts 以及与它们绑定的 Step 定义不可改写。
  不完整未来 Steps 的 checkpoint state 会重置，而不是伪装成恢复、retry 或 pause 后 resume。
- marker-backed exact replay 必须先重建 durable provenance、再读取 Clock；任一 marker、链接或
  projection 造成 provenance 撕裂或不一致时 fail-closed。
- 不实现 pause 后 resume、retry、Step Recovery、Runtime composition、Step kernel、Agent Loop、
  repair/replan、API/UI、外部 effect、V1 或生产数据库适配器。

验证证据：

- 定向行为、边界、并发与 replay：21 focused tests，0 failures，0 errors。
- 受影响模块聚合：`agent-contracts` 88 tests、`agent-persistence` 242 tests，均为 0 failures、
  0 errors。
- Recovery regression：25 tests，0 failures，0 errors。
- `git diff --check` 通过；两个对应最终 fixed head 的 GitHub CI `verify` check 均已成功。
- 无 `.env`、密钥、用户文件、V1 复制或生产数据库适配器。

## 恢复后的第一动作

1. 核对本次文档 PR 的状态；若尚未合并，主对话先完成 fixed-head 审查和 CI 门禁，不得创建或
   实现下一功能 Issue。只有主对话可以决定 Ready 和合并，实施子对话不得合并。
2. 本次文档 PR 合并后运行 `git fetch origin`，记录其 GitHub merge commit，并确认本地 `main` 与
   `origin/main` 都指向该提交；该提交才是 **atomic Step Recovery inspection** Issue 的唯一
   `baseCommit`。
3. 主对话只创建并冻结 **atomic Step Recovery inspection** Issue，写明 baseCommit、objective、
   nonGoals、ownedPaths、frozenContracts、dependencies、acceptanceTests、requiredChecks、
   stopConditions 和 mergeOrder；本交接 PR 不创建下游 Issue，不创建 worktree，也不实现任何
   Step Recovery 代码。
4. 只有 Issue 冻结后，实施子对话才能从该 `baseCommit` 创建独立 worktree 和 `codex/` 分支，
   实现、测试、提交、推送并创建 Draft PR；子对话不得合并。

## 下一能力与严格顺序

下一动作只能是创建并冻结 **atomic Step Recovery inspection** Issue；本次文档 PR 合并前不得创建它，
合并后也必须先完成冻结，才可开始实现。

后续能力按以下顺序逐个 Issue/PR 推进，前一个冻结并合并前不实现依赖它的下游：

1. atomic Step Recovery inspection。
2. Runtime Step Recovery composition。
3. single-turn Step kernel。
4. bounded Step Agent Loop。
5. bounded repair/replan。

V2 API、Web UI、用户接受/拒绝和新 ProjectVersion 属于 Wave 4。Recovery 的 Runtime
composition 属于 Wave 3，其用户入口/展示属于 Wave 4。V1 Project、版本、Provider、UI 的
选择性迁移和真实用户矩阵属于 Wave 5；V1 仍一律 `UNASSESSED`，不得直接复制。

## 尚未实现，禁止误报

- 没有完整 Step Agent Loop、effect 执行、resume/retry、Step Recovery 或 repair/replan。
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

- 当前在哪里：Wave 3 进行中；PR #97 已合并、Issue #96 已关闭，fenced append-only Plan replan
  已完成；Issue #98 只刷新这份交接。
- 先做什么：完整读取入口文档，再重新核对 Git/GitHub。若本次文档 PR 尚未合并，先完成其
  审查和门禁，不能创建或开始下游实现。
- 下一功能是什么：本次文档 PR 合并后，以其 GitHub merge commit 为唯一 `baseCommit` 创建并
  冻结 atomic Step Recovery inspection Issue；不能从交接 PR 开始实现它。
- 不能做什么：不能复制 V1、读取 `.env`、跳过 Issue/worktree/Draft PR、让子对话合并，或
  声称 API/UI/Agent Loop 已完成。
- 如何继续：严格使用 `DEVELOPMENT_PROCESS.md` 的汇报、轮询、fixed-head 审查、CI 和顺序合并
  门禁。
