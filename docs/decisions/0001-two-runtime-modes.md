# ADR 0001：顶层只保留两种执行模式

状态：Accepted

## 决策

顶层只有：

- `DIRECT`
- `PERSISTENT_PLAN_EXECUTE`

凡是涉及 Project 文件、工具、执行、联网、修改、确认、恢复或外部观察的请求，都进入持久化 Plan。

Plan Step 内的模型工具循环只是执行机制，不是第三种顶层范式。

## 原因

- 降低 Router 和 Runtime 开发复杂度。
- 统一 Project 任务的生命周期、checkpoint、取消和恢复。
- 避免多种范式产生状态和结果语义分裂。
- 回答速度可以让位于可靠性和一致性。
