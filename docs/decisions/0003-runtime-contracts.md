# ADR 0003：Runtime Contracts

状态：`ACCEPTED`

日期：2026-07-24

## 背景

Wave 0 已冻结 PaperAgent V2 的产品范围和模块边界。Wave 2 的持久化、Workspace、Sandbox 与 Provider 实现需要共同依赖一个稳定、框架无关的 Runtime 契约层；如果各实现 PR 分别定义共享模型，会产生反向依赖、循环依赖和行为漂移。

## 决策

### 构建与模块

- 使用 Java 17 和 Maven reactor。
- 根 group/package 为 `io.paperagent.v2`。
- 建立 `agent-contracts`、`agent-runtime`、`agent-persistence`、`agent-workspace`、`agent-sandbox`、`agent-providers`、`app-api`、`app-web` 和 `e2e-tests` 九个模块。
- Wave 1 只有 `agent-contracts` 含生产代码；其余模块是可编译的空边界。
- `agent-contracts` 的生产类只依赖 JDK；JUnit 5 仅用于测试。

### 契约形状

- 所有 ID 使用不可互换的显式值类型。
- 顶层 Route 只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
- `TaskFrame` 固定目标、对象、交付物、约束、可选源 `ProjectVersionRef`、`ExecutionProfile` 快照和创建时间。
- `Plan` 绑定一个 `TaskFrame`；`PlanRevision` 形成严格递增的单亲版本链。
- `PlanStep` 只描述意图、结果、依赖、完成条件和执行上限，不描述固定工具调用序列。
- `CompletionFact` 是追加式权威事实。后续 revision 必须逐值保留已完成 step 和对应 fact。
- `Checkpoint` 只保存恢复所需的不可变 ID、revision、事件序号、状态和 receipt 引用。
- Plan 与 Step 状态分别区分未开始、活动、暂停、成功、失败和取消；执行成功不代表用户接受 diff。
- `WorkspaceRef` 只标识隔离 Workspace 和不可变源版本，不提供应用 diff 到源版本的操作。
- `ProjectPath` 是规范化的 Project 相对 POSIX 路径。
- Tool、Event、Receipt 和 Diff 都是事实契约，不是 Evidence gate 或预先固定的执行脚本。
- `ExecutionProfile` 是权限快照；Wave 1 唯一活动 tier 是 `SANDBOX_STANDARD`。能力缺失即拒绝，秘密只允许逻辑引用而不允许值。

### 校验

- 构造期校验拒绝无效的单对象状态。
- `PlanValidators` 与 `CheckpointValidators` 处理跨对象和历史一致性。
- 校验错误携带稳定的 `ViolationCode`、字段路径和人类可读消息；调用方不解析异常文本。
- 校验必须确定性执行，不进行 I/O、网络、时钟、环境或随机 ID 查询。
- 所有 public collection 都进行防御性复制并以不可变集合暴露。

## 后果

- Wave 2 adapter 可以依赖纯契约，但不得把数据库 Entity、Provider SDK、Spring、文件系统对象或 live process 反向带入契约层。
- 线协议和 V1 数据兼容尚未承诺；未来序列化由外围 adapter 负责。
- `READ_ONLY`、`SANDBOX_EXTENDED` 和 `FULL_ACCESS` 仅是未来候选名称，不构成已实现授权。
- 对本 ADR 语义的修改必须由后续独立 Issue 明确批准。
