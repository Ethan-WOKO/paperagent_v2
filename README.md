# PaperAgent V2

面向科研 Project 的通用 Agent 新实现。

当前状态：`WAVE_2 / PLANNING`。Wave 0 和 Wave 1 已完成；Wave 2 尚未实现，仓库也尚未迁移任何 V1 业务代码。

V2 顶层只保留两种执行模式：

- `DIRECT`：不读取 Project、不调用工具、没有外部副作用的直接回答。
- `PERSISTENT_PLAN_EXECUTE`：所有涉及 Project、工具、执行、联网、修改或恢复的任务。

开始前请阅读：

1. [docs/START_HERE.md](docs/START_HERE.md)
2. [docs/PRODUCT_SCOPE.md](docs/PRODUCT_SCOPE.md)
3. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
4. [docs/MIGRATION_MAP.md](docs/MIGRATION_MAP.md)
5. [docs/DEVELOPMENT_PROCESS.md](docs/DEVELOPMENT_PROCESS.md)
6. [docs/ROADMAP.md](docs/ROADMAP.md)

V1 只作为行为、测试和成熟外围能力的参考来源。任何旧代码在完成迁移审查前都不得复制到本仓库。

## 构建

需要 Java 17 和 Maven 3.9+：

```text
mvn -B -ntp clean verify
```

Maven reactor 已建立九个架构模块。Wave 1 已在 `agent-contracts` 中冻结纯 JDK 契约和确定性校验；其余模块仍只是可编译边界，不包含 Runtime 行为。Wave 1 验收记录见 [docs/acceptance/WAVE_1.md](docs/acceptance/WAVE_1.md)。
