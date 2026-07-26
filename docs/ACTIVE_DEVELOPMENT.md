# 当前开发状态与交接

最后核对时间：2026-07-26（Asia/Shanghai）

本文件是可恢复的工作状态，不覆盖 `AGENTS.md`、`ARCHITECTURE.md` 或 ADR 中的冻结决策。
新主对话必须先完整读取所有入口文档，再用只读 Git/GitHub 命令确认外部状态是否变化。

## 安全停止点

- Repository：`Ethan-WOKO/paperagent_v2`
- 最新合并的 Wave 3 PR：[#89](https://github.com/Ethan-WOKO/paperagent_v2/pull/89)，对应
  [Issue #88](https://github.com/Ethan-WOKO/paperagent_v2/issues/88) 已关闭；fenced Step completion
  与 append-only revision 已完成。PR #89 的 fixed head 是
  `d1e252475b255e74dd90c081853df6a8c7ff2bba`，GitHub merge commit 是当前 `main` 的
  `965cf025fcd5ecd7ca2e8630bff4bae532e26626`。
- Issue #90 追踪本次文档刷新；它不是 Runtime 实现工作流。pause/fail/cancel 的实现 Issue
  尚未发布。
- `965cf025fcd5ecd7ca2e8630bff4bae532e26626` 仅是 Issue #90 的文档刷新 base，不是下一实现
  Issue 的 base。本文件 PR 合并后，必须记录其 GitHub merge commit，并将该提交作为下一
  pause/fail/cancel Issue 的唯一 `baseCommit`。

如果 GitHub 状态与本节不一致，以重新读取的 GitHub 状态为准，并先判断是谁、为什么改变，
不得根据编号或本地旧分支猜测。

## 已合并的 PR #89 能力

PR #89 由 Persistence authority 提供 fenced Step completion 与 append-only Plan revision：

- 当前 lease、source 和 evidence 校验通过后，completion revision、event、checkpoint、永久 marker、
  provenance link 与 mutation head 原子追加；结果保持 token-free。
- 审查修正将 completion marker 与追加的 Plan revision 绑定，并在该关系不一致时 fail-closed。
- 不执行外部 effect，不改变 Provider、Sandbox、Workspace、API/UI、V1 或生产数据库适配器。

验证证据：

- 定向行为/边界：22 focused tests，0 failures，0 errors。
- 受影响模块聚合：`agent-contracts` 88 tests、`agent-persistence` 214 tests，均为 0 failures、0 errors。
- 两个 fixed-head CI `verify` check 均已成功；`git diff --check` 通过。
- 无 `.env`、密钥、用户文件、V1 复制或生产数据库适配器。

## 恢复后的第一动作

1. 核对本次文档 PR 的状态；若尚未合并，主对话先完成 fixed-head 审查和 CI 门禁，不得发布下一
   实现 Issue。只有主对话可以决定 Ready 和合并，实施子对话不得合并。
2. 本次文档 PR 合并后运行 `git fetch origin`，记录其 GitHub merge commit，并确认本地 `main` 与
   `origin/main` 都指向该提交；不得复用本 Issue 的 base
   `965cf025fcd5ecd7ca2e8630bff4bae532e26626`。
3. 检查是否已有开放的 pause/fail/cancel 实现 Issue；
   若尚未发布，主对话以本次文档 PR 的 GitHub merge commit 为唯一 `baseCommit` 创建该 Issue，并冻结目标、非目标、
   owned paths、契约、依赖、验收、必跑检查、停止条件和合并顺序。
4. 只有 Issue 冻结后，实施子对话才能从该 `baseCommit` 创建独立 worktree 和 `codex/` 分支，
   实现、测试、提交、推送并创建 Draft PR；子对话不得合并。

## 下一能力与严格顺序

下一实现 Issue 只能是 **pause/fail/cancel**。它尚未创建，必须先冻结 Issue 边界再实现；不得跳到
fenced replan、recovery 或 Agent Loop。

后续能力按以下顺序逐个 Issue/PR 推进，前一个冻结并合并前不实现依赖它的下游：

1. pause/fail/cancel。
2. fenced replan。
3. atomic Step Recovery inspection。
4. Runtime Step Recovery composition。
5. single-turn Step kernel。
6. bounded Step Agent Loop。
7. bounded repair/replan。

V2 API、Web UI、用户接受/拒绝和新 ProjectVersion 属于 Wave 4。Recovery 的 Runtime
composition 属于 Wave 3，其用户入口/展示属于 Wave 4。V1 Project、版本、Provider、UI 的
选择性迁移和真实用户矩阵属于 Wave 5。

## 尚未实现，禁止误报

- 没有完整 Step Agent Loop、effect 执行、pause/fail/cancel 或 repair/replan。
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

- 当前在哪里：Wave 3 进行中；PR #89 已合并、Issue #88 已关闭，fenced Step completion 与
  append-only revision 已完成，Issue #90 负责刷新交接文档。
- 先做什么：重新核对 Git/GitHub；若本次文档 PR 尚未合并，先完成其审查和门禁，不能直接开始
  下游实现。
- 下一功能是什么：本次文档 PR 合并后，以其 GitHub merge commit 为唯一 `baseCommit` 发布并
  冻结 pause/fail/cancel Issue。
- 不能做什么：不能复制 V1、读取 `.env`、跳过 Issue/worktree/Draft PR、让子对话合并，或
  声称 API/UI/Agent Loop 已完成。
- 如何继续：严格使用 `DEVELOPMENT_PROCESS.md` 的汇报、轮询、顺序审查和合并门禁。
