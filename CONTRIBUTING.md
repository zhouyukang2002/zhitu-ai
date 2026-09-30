# 智途 AI (Zhitu-AI) 贡献指南

感谢你对 **智途 AI** 开源项目的关注与支持！无论是修复缺陷、改进文档、优化模型提示词还是贡献新的学科语料，我们都热烈欢迎。

---

## 一、 开发环境要求

- **JDK**: 17 或更高版本
- **Maven**: 3.8+
- **Node.js**: 18+ 与 npm
- **Docker & Docker Compose**: 用于一键拉起 Elasticsearch 8.15、MySQL 8.0、Redis 7.0

---

## 二、 分支与提交流程

1. **Fork 本仓库** 到你自己的 GitHub 账号；
2. **基于 `main` 分支拉取开发分支**：
   ```bash
   git checkout -b feat/your-feature-name
   # 或者修复 Bug
   git checkout -b fix/issue-description
   ```
3. **本地验证**：
   - 提交前请确保运行所有单元测试并通过：
     ```bash
     mvn test -Dtest=SkillDictionaryDynamicTest,SlotsTest,IntentRouterFixTest
     cd frontend && npm run build
     ```
4. **Git Commit 规范**：
   采用语义化提交规范（Angular / Conventional Commits）：
   - `feat`: 新增特性或智能体能力
   - `fix`: 修复缺陷或路由消歧误判
   - `docs`: 文档变更
   - `refactor`: 重构或性能优化
   - `test`: 增加或更新测试用例
5. **提交 PR (Pull Request)**：
   提交至官方仓库的 `main` 分支，CI 自动化测试跑通后，维护者将进行 Code Review 并合入。

---

## 三、 如何贡献新学科语料（零代码扩展规范）

智途 AI 支持通过纯 Markdown 语料无代码新增学科：
1. 在 `corpus/课程/` 目录下添加 `cXXX_课程名称.md`，并在头部 Frontmatter 声明别名：
   ```yaml
   ---
   courseId: c099
   name: Go语言高并发实战
   track: 后端开发
   aliases: [golang, go并发, go协程]
   price: 299
   ---
   ```
2. 在 `corpus/题库/` 目录下添加配套题库 `cXXX_题库.md`；
3. 执行一键热同步后，系统将自动支持新学科的推荐、讲解、出题与预下单。
