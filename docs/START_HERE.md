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

`Wave 0 / Bootstrap`、`Wave 1 / Runtime Contracts` 和 `Wave 2 / 独立基础能力` 已完成。
当前为 `Wave 3 / Runtime 主链` 的规划阶段：

- Java 17 / Maven 多模块骨架和纯契约已建立。
- TaskFrame、Plan、Workspace、Event、Receipt 和 Diff 边界已冻结。
- Wave 2 已实现内存持久化参考适配器、本地隔离 Workspace、Model Provider 和 Sandbox
  端口及确定性脚本适配器。
- 测试专用 E2E Harness 已证明这些公开边界可以组合、失败隔离且不会擅自生成 Runtime
  执行事实。
- 尚未迁移 V1 代码。
- 尚未实现 Runtime 主链、真实模型或执行后端、产品 API、UI 和用户旅程。
- 当前持久化实现不承诺进程重启后的耐久性；Sandbox 不执行真实进程，也不提供真实网络、
  资源或密钥隔离。
- 尚未确定数据库 schema 或线协议。

## 下一步

1. 先合并 Wave 2 阶段验收 PR；该 PR 的 GitHub 合并提交是所有 Wave 3 Issue
   唯一有效的 `baseCommit`。
2. 由主对话按 Runtime 主链的依赖顺序发布 Wave 3 Issue，冻结接口、文件所有权、验收条件和
   合并顺序。
3. 为每个 Issue 创建独立 worktree、`codex/` 分支和 Draft PR。
4. 阶段验收合并前不得开始 Wave 3 实现；Wave 3 不得把已冻结的公开边界重新集中到单个大
   Service。

## 新主对话建议指令

> 读取 `AGENTS.md` 和 `docs/START_HERE.md`。你是 PaperAgent V2 主对话，负责架构、Issue、PR 审查、合并顺序和阶段验收，不直接承担普通功能实现。先核对当前阶段、未决事项和下一步，不要立即复制 V1 代码。
