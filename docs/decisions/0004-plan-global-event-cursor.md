# ADR 0004：Plan-global Event Sequence 与 Checkpoint Cursor

状态：`ACCEPTED`

日期：2026-07-24

## 背景

Wave 2 的内存持久化参考实现按 `(PlanId, correlationId)` 分隔事件流，但 `Checkpoint`
只携带一个标量 `lastEventSequence`。如果 correlation 拥有独立序列，同一个标量无法无歧义地
表示整个 Plan 已纳入 checkpoint 的事件边界，也无法为恢复提供单一确定性游标。

V2 当前没有生产事件数据、耐久数据库 schema 或已冻结线协议，因此可以在 Runtime 执行与恢复
实现之前进行一次明确的硬切换。

## 决策

- `EventEnvelope.sequence` 在一个 `PlanId` 内属于同一严格递增的全局序列；不同 Plan 的序列
  相互独立。
- 真实事件序列从 1 开始。序列 0 只作为 `Checkpoint` 尚未纳入任何真实事件的 sentinel。
- 序列允许跳号。一旦较高序列提交，较低序列不得回填。
- `correlationId` 只用于 tracing 与 causation 元数据，不是持久化分区或恢复边界。
- `Checkpoint.lastEventSequence` 是 aggregate checkpoint 已纳入的最后一个真实 Plan-global
  事件序列。
- 恢复使用 `readAfter(planId, exclusiveSequence)`，读取严格大于游标的全部 Plan 事件。
- 删除按 correlation 读取的旧 API，不保留 overload、default、deprecated 或其他兼容层。

## Checkpoint Linkage

- checkpoint cursor 0 始终有效。
- 非零 cursor 必须精确指向同一 Plan 已持久化的真实事件。
- 因为序列允许跳号，仅校验 cursor 不大于 high-water 不足以建立引用完整性。
- checkpoint 可以停留在 0，也可以落后于 high-water，但非零 cursor 不得指向 gap、未来序列
  或只属于另一个 Plan 的事件。

## 后果

- Event 持久化与恢复读取拥有一个 Plan-global 权威顺序。
- correlation 可以在相邻事件之间变化，不影响顺序或恢复。
- 内存参考适配器使用同一 monitor 串行化 event append、event read 与 checkpoint cursor
  linkage 校验。
- 本决策不提供 event 与 checkpoint 的复合原子写入。

## 明确后置

- lease fencing 与 execution ownership；
- event + checkpoint 的原子状态迁移；
- sequence allocator 与多 writer 分配策略；
- event taxonomy 与 receipt 关联设计；
- Runtime execution/recovery composition；
- Step Agent Loop。
