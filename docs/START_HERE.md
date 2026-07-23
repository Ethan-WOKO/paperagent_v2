# 从这里开始

## 为什么建立 V2

V1 已经具备 Project 上传、版本、Candidate、沙箱、持久化 Plan 和结果展示，但通用 Agent 核心逐渐形成以下问题：

- 后端规定过细的工具调用和 Plan 形状。
- Router、Planner、Evidence、Candidate、Sandbox 和 Final Synthesis 高度耦合。
- 中间 Evidence 格式可能提前终止本可恢复的任务。
- Agent 在隔离环境中的读写、执行、依赖和联网能力不足。
- 大量逻辑集中在少数超大 Service，导致修改和并行开发困难。
- 自动测试偏向固定内部路径，真实模型换一种表达就可能暴露新断层。

V2 不继续修补旧 Runtime。它重新实现 Agent 核心，同时选择性迁移经过审查的成熟外围能力。

## 已冻结的方向

1. 顶层只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
2. 需要 Project、工具或副作用的请求统一进入持久化 Plan。
3. TaskFrame 固定“完成什么”，Plan 和 Step 决定“怎样完成”。
4. Step 内允许模型自主读写、执行、测试、安装依赖和有界修复。
5. Plan 支持 checkpoint、暂停、取消、恢复和 bounded replan。
6. Agent 在隔离 Workspace 内拥有完整修改能力。
7. 原 ProjectVersion 保持不可变；用户接受 diff 后才生成新版本。
8. 网络和密钥通过 ExecutionProfile 分级提供。
9. 命令、文件、网络、stdout、stderr、diff 和 receipt 自动进入事件日志。
10. 不再要求后端预先预测模型每一次工具调用。

## 当前阶段

`Wave 0 / Bootstrap` 已完成。当前为 `Wave 1 / Runtime Contracts`：

- 正在建立 Java 17 / Maven 多模块骨架和纯契约。
- 正在冻结 TaskFrame、Plan、Workspace、Event、Receipt 和 Diff 边界。
- 尚未迁移 V1 代码。
- 尚未实现 Runtime、持久化、Workspace、Sandbox、Provider、API 或 UI。
- 尚未确定数据库 schema 或线协议。

## 下一步

1. 完成并审查 Runtime Contracts Draft PR。
2. 通过纯契约的成功、失败和模块边界测试。
3. 主对话完成 Wave 1 阶段验收并冻结新的 base commit。
4. 再发布可并行的 Wave 2 实现 Issue。

## 新主对话建议指令

> 读取 `AGENTS.md` 和 `docs/START_HERE.md`。你是 PaperAgent V2 主对话，负责架构、Issue、PR 审查、合并顺序和阶段验收，不直接承担普通功能实现。先核对当前阶段、未决事项和下一步，不要立即复制 V1 代码。
