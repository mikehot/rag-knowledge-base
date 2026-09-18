# Observability and Alert Guardrails

本文件定义企业知识库 V0.1 的第一版运营指标和告警边界。指标通过受保护的
`/actuator/metrics` 与 `/actuator/prometheus` 暴露；应用默认不记录问题、Prompt、文档正文或用户标识。

## 指标

| 指标 | 含义 | 低基数标签 |
|---|---|---|
| `rag_ask_requests_total` | 已完成问答请求数 | `result`、`failure` |
| `rag_ask_tokens_total` | Provider 返回的 Token 总数 | 无 |
| `rag_ask_estimated_cost_total` | 按配置单价估算的模型成本累计值 | 无 |
| `rag_ask_feedback_total` | 用户提交的反馈次数 | `rating` |
| `rag_acl_denied_total` | 已成功写入审计的 ACL 拒绝次数 | `action`、`resource` |

`AI_ESTIMATED_COST_PER_1K_TOKENS` 是配置化的混合估算单价，单位由部署方定义；默认值为 `0`。
值为 `0` 时仍保留 Token 指标，但成本指标不会产生非零估算，不能当作 Provider 账单。

## 运营查询

反馈率（过去 24 小时，至少 100 次问答后才有足够样本）：

```promql
sum(increase(rag_ask_feedback_total[24h]))
/
clamp_min(sum(increase(rag_ask_requests_total[24h])), 1)
```

正向反馈率：

```promql
sum(increase(rag_ask_feedback_total{rating="helpful"}[24h]))
/
clamp_min(sum(increase(rag_ask_feedback_total[24h])), 1)
```

## 首版告警阈值

这些是上线初期的可执行 guardrail，不是质量承诺；收集至少 7 天真实基线后再调整。

```yaml
groups:
  - name: rag-knowledge-base
    rules:
      - alert: RagFeedbackRateLow
        expr: |
          sum(increase(rag_ask_feedback_total[24h]))
          /
          clamp_min(sum(increase(rag_ask_requests_total[24h])), 1) < 0.05
          and sum(increase(rag_ask_requests_total[24h])) >= 100
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "RAG 反馈率低于 5%"
          description: "过去 24 小时问答样本达到 100 后，反馈提交率持续低于 5%。"

      - alert: RagAclDeniedSpike
        expr: sum(increase(rag_acl_denied_total[15m])) >= 20
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "ACL 拒绝出现突增"
          description: "15 分钟内 ACL 拒绝达到 20 次，需要检查权限配置、客户端版本或异常访问。"

      - alert: RagEstimatedCostDailyWarning
        expr: sum(increase(rag_ask_estimated_cost_total[24h])) >= 80
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "RAG 估算成本达到日预算预警线"
          description: "过去 24 小时估算成本达到 80 个配置货币单位；请结合部署预算确认。"

      - alert: RagEstimatedCostDailyCritical
        expr: sum(increase(rag_ask_estimated_cost_total[24h])) >= 100
        for: 15m
        labels:
          severity: critical
        annotations:
          summary: "RAG 估算成本达到日预算线"
          description: "过去 24 小时估算成本达到 100 个配置货币单位；应限制流量或切换模型。"
```

## 证据边界

- `rag.ask.estimated.cost` 是基于 Token 总量和部署配置单价的估算，不等同于 Provider 账单。
- ACL 指标只在审计写入成功后增加；匿名请求被 Security Filter 拦截时不计入业务 ACL 拒绝。
- 反馈率统计提交次数；用户修改同一问答反馈也算一次提交，便于观察操作负载。
- 告警规则需要部署方将 Prometheus 规则加载到实际监控系统；仓库当前只提供应用指标和规则基线。
