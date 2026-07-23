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

状态：`PLANNING`（尚未实现）

- 二元 Router。
- TaskFrame 构建和冻结。
- 持久化 Plan 执行。
- Step Agent Loop。
- bounded repair 和 bounded replan。
- 暂停、取消、恢复和幂等。

## Wave 4：产品闭环

- Final Synthesis。
- Plan、事件、receipt 和 diff API。
- 用户确认、拒绝和新 ProjectVersion。
- 前端主流程。

## Wave 5：选择性迁移与真实验收

- 按 `MIGRATION_MAP.md` 逐项审查 V1 能力。
- 迁移有价值的 Project、版本、Provider 和 UI 能力。
- 完成固定真实用户矩阵。
- 决定 V1 数据导入和旧仓库归档时间。
