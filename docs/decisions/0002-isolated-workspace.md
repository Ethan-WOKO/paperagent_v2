# ADR 0002：隔离 Workspace 内高自治

状态：Accepted

## 决策

Agent 在隔离 Workspace 中可以：

- 读取、新建、修改和删除文件。
- 执行非特权命令。
- 构建、测试和安装依赖。
- 按 ExecutionProfile 使用网络。
- 根据真实错误有界修复和重试。

任务执行期间不得直接修改原 ProjectVersion。最终修改通过 Workspace diff 交给用户审查，接受后才创建新版本。

## 治理位置

治理集中在：

- Workspace 隔离。
- Project/用户所有权。
- 网络出口。
- 密钥注入。
- 资源和成本。
- 事件与 receipt。
- 版本冲突。
- 用户接受和外部副作用。

后端不预先规定模型每一次工具调用。
