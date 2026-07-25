# 当前开发状态与交接

最后核对时间：2026-07-25（Asia/Shanghai）

本文件是可恢复的工作状态，不覆盖 `AGENTS.md`、`ARCHITECTURE.md` 或 ADR 中的冻结决策。
新主对话必须先完整读取所有入口文档，再用只读 Git/GitHub 命令确认外部状态是否变化。

## 安全停止点

- Repository：`Ethan-WOKO/paperagent_v2`
- 本次交接刷新前已核对的 `main`/`origin/main` Runtime baseline：
  `958d2e1af58d3d6816f136f75e529a0d53357554`
- 最后合并的 Runtime PR：[#72](https://github.com/Ethan-WOKO/paperagent_v2/pull/72)，对应
  [Issue #70](https://github.com/Ethan-WOKO/paperagent_v2/issues/70) 已关闭。
- PR #73（上一版交接文档）也已合并。
- 在本次交接刷新 Issue #74 发布前，没有开放的 GitHub Issue；Issue #74 追踪本次文档刷新，
  PR #75 承载对应文档变更；两者都不是 Runtime 实现工作流。当前没有开放的 Runtime Step
  activation composition 实现 Issue。
- `958d2e1af58d3d6816f136f75e529a0d53357554` 是 Issue #74/PR #75 的 base，不是下一实现
  Issue 的 base。PR #75 合并后，必须记录其新的 GitHub merge commit，并将该提交作为下一
  实现 Issue 的唯一 `baseCommit`。

如果 GitHub 状态与本节不一致，以重新读取的 GitHub 状态为准，并先判断是谁、为什么改变，
不得根据编号或本地旧分支猜测。

## 已合并的 PR #72 能力

PR #72 从权威 committed-H0 execution snapshot 纯确定性生成 Step activation candidate：

- 生成精确 activation event 和 next checkpoint proposal。
- 不获取 lease，不读取 repository/current context，不提交 activation。
- 真实公开 `InMemoryPersistence` 测试证明首次 `APPLIED`、exact replay `REPLAYED`。
- authority 已推进时，旧 H0 生成的不同 candidate 被精确拒绝为
  `STALE_VERSION / request.expectedCheckpointVersion`。
- source-backed Step 在 context 未确认时，materialization 不改变 context，Persistence 精确
  拒绝为 `STEP_ACTIVATION_NOT_ELIGIBLE / stepActivation.source`。

验证证据：

- 定向行为/边界：23 tests，0 failures，0 errors，0 skips。
- Runtime aggregate：628 tests，0 failures，0 errors，10 skips。
- 全仓：677 tests，0 failures，0 errors，10 skips。
- 10 个 skip 均为既有 Windows 文件系统限制：8 个 symlink、1 个大小写敏感、1 个反斜杠
  文件名；Linux CI 执行对应场景。
- 两路独立最终审查均为 B/M/m = 0/0/0。
- `git diff --check` 通过；最终 `mvn -T 1 clean` 后无 `target` 目录。
- 无 `.env`、密钥、用户文件、V1 复制或生产 test-only Persistence 依赖。

## 恢复后的第一动作

1. 核对 PR #75 的状态；若尚未合并，主对话先完成 fixed-head 审查和 CI 门禁，不得发布下一
   实现 Issue。只有主对话可以决定 Ready 和合并，实施子对话不得合并。
2. PR #75 合并后运行 `git fetch origin`，记录其 GitHub merge commit，并确认本地 `main` 与
   `origin/main` 都指向该提交；不得复用 PR #75 的 base `958d2e1af58d3d6816f136f75e529a0d53357554`。
3. 检查是否已有开放的 Runtime Step activation composition 实现 Issue；若尚未发布，主对话以
   PR #75 的 GitHub merge commit 为唯一 `baseCommit` 创建该 Issue，并冻结目标、非目标、
   owned paths、契约、依赖、验收、必跑检查、停止条件和合并顺序。
4. 只有 Issue 冻结后，实施子对话才能从该 `baseCommit` 创建独立 worktree 和 `codex/` 分支，
   实现、测试、提交、推送并创建 Draft PR；子对话不得合并。

## 下一能力与严格顺序

下一实现 Issue 只能是 **Runtime Step activation composition**：组合 committed-H0 candidate、
lease/fence/current context/Persistence authority 和 replay/stale rejection，但不执行 Step
effect，也不实现 Agent Loop。它尚未创建，必须先冻结 Issue 边界再实现。

后续能力按以下顺序逐个 Issue/PR 推进，前一个冻结并合并前不实现依赖它的下游：

1. provider-neutral effect identity/replayability 与 durable intent。
2. fenced effect result/progress 与 Receipt ownership。
3. completion/revision。
4. pause/fail/cancel。
5. fenced replan。
6. atomic Step Recovery inspection。
7. Runtime Step Recovery composition。
8. single-turn Step kernel。
9. bounded Step Agent Loop。
10. bounded repair/replan。

V2 API、Web UI、用户接受/拒绝和新 ProjectVersion 属于 Wave 4。Recovery 的 Runtime
composition 属于 Wave 3，其用户入口/展示属于 Wave 4。V1 Project、版本、Provider、UI 的
选择性迁移和真实用户矩阵属于 Wave 5。

## 尚未实现，禁止误报

- 没有完整 Step Agent Loop、effect 执行、completion 或 repair/replan。
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

- 当前在哪里：Wave 3 进行中；本次交接刷新前 `main` baseline 为 `958d2e1…`，PR #72 已合并、
  Issue #70 已关闭、PR #73 已合并，PR #75 负责刷新交接文档。
- 先做什么：重新核对 Git/GitHub；若 PR #75 尚未合并，先完成其审查和门禁，不能直接开始
  下游实现。
- 下一功能是什么：PR #75 合并后，以其 GitHub merge commit 为唯一 `baseCommit` 发布并
  冻结 Runtime Step activation composition Issue。
- 不能做什么：不能复制 V1、读取 `.env`、跳过 Issue/worktree/Draft PR、让子对话合并，或
  声称 API/UI/Agent Loop 已完成。
- 如何继续：严格使用 `DEVELOPMENT_PROCESS.md` 的汇报、轮询、顺序审查和合并门禁。
