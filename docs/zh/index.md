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
    details: MFA、RBAC 和完整审计日志
---

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/cuihairu/oddsmaker.git

# 进入项目目录
cd oddsmaker

# 可选：按需改端口 / 密码 / Token（中文注释见 .env.example）
cp .env.example .env

# 启动快速本地编排（推荐，使用非占用端口 + 中文注释）
docker compose -f docker-compose.quickstart.yml up -d

# 或者使用 Docker 单服务启动
# docker pull ghcr.io/cuihairu/oddsmaker:nightly
# docker run -d --name oddsmaker-control -p 38085:8085 -e SERVICE=control ...

# 访问 API
curl http://localhost:38085/actuator/health
```

## 核心功能

### 多游戏架构
支持多个游戏，每个游戏可配置独立的环境（dev/staging/prod）和 API Key。

### 实时分析
基于 Kafka + Flink + ClickHouse 的实时事件处理管道（单节点设计目标 1万–5万 事件/秒，见运维 perf-tuning）。

### 风险控制
完整的风控规则引擎，支持实时评估、自动封禁和人工审核。

### A/B 测试
内置实验平台，支持变体分流、转化率分析、统计显著性检验。

### 机器学习
ML 模型管理，支持训练、部署、A/B 测试和漂移检测。

### 企业安全
MFA（两步验证）、RBAC 权限控制（8 角色 × global/game/environment 三级范围）和完整审计日志。

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
