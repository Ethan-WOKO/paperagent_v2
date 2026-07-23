# Wave 1 阶段验收

## 验收结论

`Wave 1 / Runtime Contracts` 已完成并通过主对话阶段验收。

- 验收对象：[PR #3](https://github.com/Ethan-WOKO/paperagent_v2/pull/3)
- PR 标题：`[Wave 1] Freeze Runtime Contracts and module skeleton`
- 合并提交：`cc118b84e795b1152ac4b9bd67b6c825d6a45dc1`
- 被验收的最新分支提交：`63ee7ed75082735c7d5b3953c1ca9a8b58749284`
- 下游基线：Wave 2 Issue 先以本验收 PR 的合并提交作为精确 `baseCommit`，不得直接使用 PR #3 的分支提交。

Wave 1 建立了 Java 17 / Maven 九模块 reactor，在 `agent-contracts` 中冻结了纯 JDK 契约和确定性校验，其余模块保持为可编译边界。该验收不表示任何 Wave 2 能力已经实现。

## 验收证据

- 全量构建通过：10-module reactor `SUCCESS`。
- 自动化测试：58 tests，0 failures，0 errors，0 skipped。
- 最新分支提交 `63ee7ed75082735c7d5b3953c1ca9a8b58749284` 的两个 `ci / verify` 检查均为 `SUCCESS`。
- 三轮主对话审查已经完成：初审、修复复审和最终验收。
- 所有规定检查均已执行，没有跳过项。
- 变更路径符合 Issue #2 的 `ownedPaths`，没有提交生成物或 `.env` 文件。
- `agent-contracts` 生产代码保持 JDK-only，没有 Spring、Persistence、Jackson、Lombok、E2B 或 OpenAI SDK 依赖。
- 没有复制、改写或迁移 V1 源代码。

## 审查中发现并修复的问题

第一轮审查要求修复以下契约校验缺口：

- `SUCCEEDED` step 必须具有对应的 `CompletionFact`。
- 已终止的 Plan 或 Step 不得重新打开或更换为另一种终态。
- checkpoint revision、同号 revision identity 和创建时间不得回退。
- 公共 validator 对缺失 revision、空集合元素等预期非法输入必须返回稳定的机器可读 violation code，不得泄漏 `NullPointerException`。

第二轮复审继续要求：

- 单个 checkpoint 的 receipt reference 不得重复，历史 receipt reference 必须只追加、不可删除。
- 已开始的 Plan 或 Step 不得回退到 `NOT_STARTED`。
- 合法的 `ACTIVE` / `PAUSED` 暂停与恢复转换仍须保留。

第三轮验收核对上述修复、回归测试、路径边界和 CI；没有遗留阻断问题。

## 已执行检查

PR #3 报告并通过：

```text
mvn -B -ntp -pl agent-contracts -am test
mvn -B -ntp clean verify
git diff --check 407bba222e85197741a3a4510e26d7d282584c3a...HEAD
```

同时完成了 Issue #2 路径 allowlist、生成物、`.env`、框架/SDK import、V1 package/import 和常见 secret signature 扫描；最后执行 `mvn -B -ntp clean` 清理生成输出。

## 未实现范围与剩余风险

- Wave 2 的 persistence、Workspace、Sandbox、Provider 和 E2E Harness 尚未实现。
- Runtime 主链、API、UI、数据库 schema、wire serialization、adapter 和 V1 compatibility 尚未实现或承诺。
- 空模块当前会产生预期的 Maven empty-JAR warning；这反映其仅为边界骨架，不是功能实现。
- Wave 2 必须通过独立 Issue、互斥 `ownedPaths` 和新的验收测试推进；共享契约如需变更，必须停止并交回主对话决策。
