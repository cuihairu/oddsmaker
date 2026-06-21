---
layout: home

hero:
  name: Oddsmaker
  text: 游戏分析平台
  tagline: 专业游戏分析平台，支持单公司多游戏运营
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/getting-started/e2e
    - theme: alt
      text: API 文档
      link: /zh/reference/

features:
  - icon:
      src: /icons/architecture.svg
    title: 多游戏架构
    details: 支持多个游戏，环境隔离，配置独立
  - icon:
      src: /icons/analytics.svg
    title: 实时分析
    details: Kafka + Flink + ClickHouse 实时事件处理
  - icon:
      src: /icons/shield.svg
    title: 风险控制
    details: 全面的风险管理，实时评估和封禁
  - icon:
      src: /icons/experiment.svg
    title: A/B 测试
    details: 内置实验平台，支持统计分析
  - icon:
      src: /icons/ml.svg
    title: 预测模型
    details: 流失预测、作弊检测、LTV 预测
  - icon:
      src: /icons/security.svg
    title: 企业安全
    details: MFA、SSO、RBAC 和完整审计日志
---

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/cuihairu/oddsmaker.git

# 启动本地基础设施
cd oddsmaker
docker-compose -f infra/docker-compose.yml up -d

# 访问 API
curl http://localhost:8085/actuator/health
```

## 核心功能

### 多游戏架构
支持多个游戏，每个游戏可配置独立的环境（dev/staging/prod）和 API Key。

### 实时分析
基于 Kafka + Flink + ClickHouse 的实时事件处理管道，支持百万级事件/秒。

### 风险控制
完整的风控规则引擎，支持实时评估、自动封禁和人工审核。

### A/B 测试
内置实验平台，支持变体分流、转化率分析、统计显著性检验。

### 机器学习
ML 模型管理，支持训练、部署、A/B 测试和漂移检测。

### 企业安全
MFA、SSO、RBAC 权限控制和完整审计日志。

## 文档

- [API 参考](/zh/reference/) - 完整 API 文档
- [系统架构](/zh/reference/architecture) - 系统架构总览
- [K8s 部署](/zh/operations/deploy.k8s) - 部署到生产
- [分析场景](/zh/reference/gaming-scenarios) - 游戏分析覆盖度

## 社区

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues) - 报告问题
- [讨论区](https://github.com/cuihairu/oddsmaker/discussions) - 提问和分享

## 许可证

Oddsmaker 使用 [MIT 许可证](https://opensource.org/licenses/MIT) 发布。
