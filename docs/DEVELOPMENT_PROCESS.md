# 开发流程

## 角色

### 主对话

- 冻结架构和接口。
- 发布一个功能一个 Issue。
- 指定 base commit、依赖、验收条件和文件所有权。
- 审查 Draft PR、CI、风险和合并顺序。
- 负责阶段验收，不承担普通功能实现。

### 实施子对话

- 使用独立 worktree。
- 创建 `codex/<issue>` 分支。
- 只实现当前 Issue。
- 编写和运行测试。
- commit、push 并创建 Draft PR。
- 根据 Review 修改；不得自行合并。

## Issue 必填

```text
baseCommit
objective
nonGoals
ownedPaths
frozenContracts
dependencies
acceptanceTests
requiredChecks
demoEvidence
stopConditions
mergeOrder
```

## PR 门

- 变更与 Issue 一致。
- 没有复制未审查的 V1 代码。
- 单元测试覆盖成功和失败。
- 跨模块能力有集成或 E2E 测试。
- 报告精确测试命令、跳过项、原因和风险。
- 主对话完成静态审查。

## 并行规则

- 契约未冻结前不并行实现依赖它的功能。
- 并行 PR 不应修改同一核心文件。
- 同时维护的实现 PR 初期不超过 4 个。
- 共享接口只能由指定 Owner PR 修改。
- 合并后，下游分支及时更新 base。

## 用户验收

用户验收用于确认真实体验，不应承担第一轮发现基础集成错误。进入用户验收前必须已有自动化端到端旅程。
