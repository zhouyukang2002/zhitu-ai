# 智途 AI · 基础设施容器部署说明 (Docker Infrastructure)

本项目使用 `docker-compose.yml` 统一编排并一键拉起系统所需的全部中间件与数据库。

---

## 🚀 一键启动

在当前目录下执行：
```bash
docker-compose up -d
```

### 包含的服务与端口映射

| 容器服务 | 基础镜像 | 映射端口 | 核心特性与说明 |
| :--- | :--- | :--- | :--- |
| **Elasticsearch** | `elasticsearch:8.15.0` | `9200`, `9300` | 单节点模式，**容器启动时自动拉取并安装 IK 中文分词插件**，挂载 `es_plugins` 数据卷 |
| **MySQL** | `mysql:8.0` | `3306` | UTF-8MB4，**挂载 `mysql/init/` 启动时自动建库、建表并灌入种子数据** |
| **Redis** | `redis:7.0-alpine` | `6379` | 轻量级高频缓存与会话状态存储 |

### 自定义端口与密码（环境变量）

`docker-compose.yml` 支持通过环境变量自定义端口与密码，避免与宿主机现有服务冲突：

```bash
# 示例：通过环境变量指定端口启动
MYSQL_PORT=3307 REDIS_PORT=6380 ES_PORT=9201 docker-compose up -d
```
或在项目根目录复制 `.env.example` 为 `.env` 进行统一管理。

---

## 🛠️ 常见问题排查

1. **Elasticsearch 内存不足**：
   - 默认配置为 `-Xms512m -Xmx512m`；
   - 若机器内存受限，可在 `docker-compose.yml` 中调整为 `-Xms256m -Xmx256m`。
2. **重置数据库**：
   - 若想完全清空数据库并重新执行初始化脚本：
     ```bash
     docker-compose down -v    # 删除容器并清除关联数据卷
     docker-compose up -d      # 重新启动并重新初始化
     ```
3. **检查 IK 分词插件是否安装成功**：
   ```bash
   curl http://localhost:9200/_cat/plugins
   # 返回包含 analysis-ik 即表示安装成功
   ```
