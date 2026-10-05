# Dify / Claude 文档后的三小时设计裁剪

日期：2026-10-05；原版源码基线 7ddff72；CC设计基线 2f4f22d。本文是设计和 review，不是实现、测试或部署证据；任务状态由 Harness 唯一维护。

## 官方能力与本仓差距

| 官方依据 | 本仓代码事实 | 取舍与验收 |
|---|---|---|
| [Dify 第5课](https://docs.dify.ai/en/learn/tutorials/workflow-101/lesson-05)：互斥分支通过变量聚合汇合 | WorkflowGraphValidator 仅允许必经上游变量，Engine为单路径解释器；没有聚合节点 | 优先 WORKFLOW-AGGREGATION-001：静态保证每条到达路径恰一个候选，运行再核对；不做并行barrier |
| [Dify 第6课](https://docs.dify.ai/en/learn/tutorials/workflow-101/lesson-06)：类型化数组与逐项子过程，串行/并行和错误模式 | 原版没有迭代子图，输入为有界标量；CC提议固定的每项检索+LLM | CC简化能力可以独立交付但不能称子图等价；原版本轮不重写引擎 |
| [Claude structured outputs](https://code.claude.com/docs/en/agent-sdk/structured-outputs)：按schema校验并限制修复失败 | 原版LLM节点只验证有界非空文本，没有输出schema | 下一候选为独立结构化输出切片；需要类型、深度/字节限额、修复预算共享、失败不走END，不能只让模型承诺JSON |
| [Claude skills](https://code.claude.com/docs/en/agent-sdk/skills)：元数据发现、按需加载指令和资源 | 原版没有技能资源；CC M21是绑定指令包 | 内容快照冻结只是第一步，不等于渐进发现或完整SDK Skill；本窗口不引入脚本执行 |
| [Claude hosting](https://code.claude.com/docs/en/agent-sdk/hosting)：无顶层session总时限属于明确限制 | 原版CircuitBreakerService已有共享localDeadline和父ExecutionControl，每次retry不重置deadline | 不照搬其他产品限制而删除本仓预算。按模型策略需独立冻结/运行期收紧决策，不能只提高HTTP readTimeout |
| [Claude cost tracking](https://code.claude.com/docs/en/agent-sdk/cost-tracking)：结果累计成本与逐步usage粒度不同，重复消息需去重 | 原版无精确价格表/计费闭环，Provider协议异构 | 后续按实际operation/attempt身份建用量口径；不照搬Claude messageId到其他供应商，不把估算当账单 |

来源是2026-10-05可见官方文档，不是对应开源tag的源码验收，也不因此宣称Hify与Dify/SDK功能对等。详细既有Dify逐篇映射见2026-10-04报告。

## 原版顺序调整

H1先做只读链路核查：已有45秒共享模型期限、Run控制、取消与分类重试，CC的179秒三次调用不是原版已复现故障。按模型策略必须覆盖同步/流式/工作流并保护旧快照，不能在三小时里盲改共享运行时。

本轮先交付H2中的确定性聚合闭环，计划见 `exec-plans/active/WORKFLOW-AGGREGATION-001.md`。结构化输出紧随其后，只有前片门禁通过且时间足够才实施；Skills、成本和MCP写审批保留设计候选，不承诺全部完成。MCP不删除READ守卫，不把管理员token权限等同于产品已支持写工具审批。

## 对 CC 2f4f22d 的设计 review

- M22-1可继续，范围仅调用策略：默认0须明确代表继承；生产10–600秒校验与测试1/5秒注入区分；同步callTimeout和流式readTimeout不混为总期限。NETWORK重试仍可能在上游已处理后重复计费，不能说关闭timeout重试即恰好调用一次。取消与首delta后失败不得重试；429和熔断应有正向对照。
- M22-2限定串行检索+LLM可接受；新增严格元素类型、空数组、累计字节限额、原index审计、错误模式与取消分离。最多20项不代表运行总时长有界，读超时也不是总时间限制。REMOVE仅去结果项，不删除失败审计。
- M22-3要把开场白和问题进入快照/回滚，不能只递增版本号。
- 时间以T+表示，实际窗口约14:14–17:14 CST；每片独立提交/测试/复核，部署前资源重检、来源身份和远端证据。旧磁盘95%不是本轮检查结果。

未运行CC构建或部署；只读了设计与相关代码，工作树已出现的M22实现不包含在设计基线批准内。
