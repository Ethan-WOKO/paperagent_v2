# PaperAgent V2 开发约定

## 开始工作

先阅读 `docs/START_HERE.md`、`docs/ARCHITECTURE.md`、`docs/MIGRATION_MAP.md` 和当前 Issue。

## 固定产品决策

- 顶层执行模式只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
- 所有 Project 文件、工具、执行、联网和修改请求都进入持久化 Plan。
- TaskFrame 固定目标、对象、交付物、约束、ProjectVersion 和权限档位。
- Plan 可以修订；已完成的权威事实不可改写。
- Plan Step 内允许模型自主读写、执行、测试和修复。
- Agent 只能修改隔离 Workspace；用户接受前不得修改原 ProjectVersion。
- Workspace diff、执行 receipt 和事件日志是结果事实来源。
- Evidence 支持最终结论，不得再次变成逐步骤的固定工具调用脚本。

## 迁移约束

- V1 代码默认状态是 `UNASSESSED`，不是默认可复用。
- 迁移前必须在 `docs/MIGRATION_MAP.md` 记录结论。
- 每次只迁移一个能力，并用独立 PR 完成。
- 不得复制旧 `PlanAgentService`、旧 Planner、旧 CompletionVerifier 或固定 Candidate 执行链。
- 不得为了兼容旧 Plan 数据污染 V2 核心模型。

## 工程约束

- 新模块只能依赖更内层的稳定契约；禁止循环依赖。
- Provider、Workspace、Persistence、Sandbox 通过接口接入 Runtime。
- 不把多个模块的职责集中到单个大 Service。
- 新能力必须有自动化行为测试和失败测试。
- 不读取、提交或输出密钥、`.env`、本地验收数据和用户文件。

## Git 与 PR

- 一个 Issue 对应一个独立 worktree、branch 和 Draft PR。
- 分支使用 `codex/` 前缀。
- 子对话负责实现、测试、提交、推送和 Draft PR；不得自行合并。
- 主对话负责 Issue、接口冻结、审查、合并顺序和阶段验收。
- PR 必须列出执行的命令、跳过项、原因和剩余风险。
