# Runtime V2 架构

## 顶层流程

```text
User Request
  -> Binary Router
     -> DIRECT
     -> PERSISTENT_PLAN_EXECUTE
        -> TaskFrame
        -> Versioned Plan
        -> Checkpoint
        -> Plan Step Agent Loop
        -> Workspace / Tools / Sandbox
        -> Bounded Repair or Replan
        -> Workspace Diff + Receipts
        -> Final Synthesis
        -> User Accept / Reject
```

## 计划模块

```text
agent-contracts
agent-runtime
agent-persistence
agent-workspace
agent-sandbox
agent-providers
app-api
app-web
e2e-tests
```

Wave 1 使用 Java 17 / Maven reactor 建立以上九个模块。共享契约的 package root 为
`io.paperagent.v2.contracts`；`agent-contracts` 的生产代码只依赖 JDK。其余模块在 Wave 1
只声明可编译边界，不包含运行时实现。

## 职责

- `agent-contracts`：TaskFrame、Plan、Step、Event、Receipt、Diff 等纯契约。
- `agent-runtime`：二元路由、Plan 执行、Step Agent Loop、repair/replan 和完成判定。
- `agent-persistence`：Plan、revision、checkpoint、lease、幂等和恢复。
- `agent-workspace`：ProjectVersion 物化、文件操作、Workspace diff 和清理。
- `agent-sandbox`：命令、资源、网络、依赖、进程和输出。
- `agent-providers`：LLM、E2B、模型工具调用等外部适配。
- `app-api`：认证、Project、Session、Runtime 和用户操作 API。
- `app-web`：Plan、进度、diff、receipt 和确认界面。
- `e2e-tests`：真实用户旅程和跨模块验收。

## 依赖方向

```text
app-api/app-web
      -> agent-runtime
      -> agent-contracts

agent-runtime
      -> persistence/workspace/sandbox/provider ports

adapters
      -> implement ports
```

核心 Runtime 不得依赖 Spring Controller、数据库 Entity、具体 Provider SDK 或 V1 Service。

## 不可变边界

- TaskFrame 的目标、对象、交付物、ProjectVersion 和权限不能被 replan 修改。
- 已完成的权威事实不能被后续 Plan revision 重写。
- 原 ProjectVersion 不可在任务执行中修改。
- 外部副作用必须经过明确的 ExecutionProfile 和用户授权。

## 可扩展边界

- Tool Registry
- Workspace Provider
- Sandbox Provider
- Model Provider
- Persistence Adapter
- ExecutionProfile
- Plan Step Executor
- Result Renderer

新增能力通过这些边界接入，不修改整个 Runtime 控制流。

## 契约校验边界

- 值对象在构造时拒绝空 ID、空必填文本、非法时间、非法路径和不一致的单对象组合。
- `PlanValidators` 校验 revision 单亲递增、TaskFrame 绑定、依赖图及已完成事实保留。
- `CheckpointValidators` 校验 Plan/revision/step 引用、终态一致性和事件序号不回退。
- 失败返回稳定的机器可读 violation code、字段路径和说明，不要求解析异常文本。
- 校验不进行 I/O、联网、时钟读取、环境读取或随机 ID 生成。

详细冻结结论见 [ADR 0003](decisions/0003-runtime-contracts.md)。
