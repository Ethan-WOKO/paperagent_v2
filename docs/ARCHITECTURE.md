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
