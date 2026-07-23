# V1 能力迁移清单

## 规则

V1 所有代码默认是 `UNASSESSED`。只有完成以下检查后才能迁移：

1. 行为是否仍符合 V2 产品目标。
2. 是否依赖旧 Runtime、旧 Candidate 或逐步骤 Evidence gate。
3. 是否有可靠自动测试。
4. 是否可以通过 V2 接口隔离。
5. 是否携带旧数据库、配置、密钥或临时验收依赖。
6. 迁移是否比重新实现更简单。

每个能力只能标记为：

- `REUSE`：可以基本保留实现。
- `REWRITE`：保留产品目标，重新实现。
- `DROP`：与 V2 目标冲突。
- `DEFER`：当前版本不做。
- `UNASSESSED`：尚未审查，禁止复制。

## 初始判断

| V1 能力 | 初始状态 | 说明 |
|---|---|---|
| 用户认证和 Project 所有权 | UNASSESSED | 可能复用，需先解除旧 API/Entity 耦合 |
| Project 上传与对象存储 | UNASSESSED | 核对完整性、测试和配置边界 |
| ProjectVersion、manifest、hash | UNASSESSED | 高价值复用候选 |
| revision、rollback、export | UNASSESSED | 高价值复用候选 |
| Session/消息持久化 | REWRITE | 只保留必要语义 |
| E2B Provider | UNASSESSED | 核对错误、网络和资源模型 |
| Broker lease/receipt | UNASSESSED | 可参考协议，不默认复制实现 |
| ContextPackage | UNASSESSED | Worker 24 完成后单独审查 |
| Plan checkpoint/recovery | REWRITE | 使用新的 TaskFrame/Plan/Event 契约 |
| `PlanAgentService` | DROP | 职责过多且与 V2 控制流冲突 |
| `PlanningAgentPlanner` | DROP | 不迁移固定 DAG 和修复提示 |
| `CompletionVerifier` | DROP | 不迁移逐步骤 Evidence 阻断 |
| 顶层 AUTO/SINGLE_STEP_REACT | DROP | V2 只有两个顶层模式 |
| 旧 Candidate Runtime | DROP | V2 使用隔离 Workspace 和 diff |
| 旧 Candidate UI | UNASSESSED | 只考虑复用可独立的 diff 展示 |
| 旧 Worker/阶段过程文档 | DROP | 仅保留本表中的沉淀结论 |
| V1 Plan/Candidate 数据兼容 | DEFER | 初版使用新 schema |

## V1 参考来源

- Repository：`Ethan-WOKO/paperagent_redo`
- 当前已知稳定基线：`b648c5f`
- Worker 24 最终 tag/commit：待 V1 收口后补充

迁移 PR 必须补充精确来源 commit、迁移文件、删除的旧依赖和验证命令。
