---
kp: MySQL 索引
category: SKILL
source: 《MySQL 实战 45 讲（精编）》
---
## SQL 基础
SQL 三类语句：DML（SELECT/INSERT/UPDATE/DELETE）、DDL（建库表）、DCL（权限）。WHERE 过滤行、HAVING 过滤分组；LEFT JOIN 保留左表全部行、INNER JOIN 只保留匹配行。COUNT(*) 统计行数、COUNT(列名) 不计 NULL。聚合查询结构：SELECT 聚合列 FROM 表 WHERE 行过滤 GROUP BY 分列 HAVING 组过滤 ORDER BY 排序。

## MySQL 索引
InnoDB 索引用 B+树：叶子节点存数据（聚簇索引）或主键（二级索引），树高 3~4 层支撑千万数据。最左前缀原则：联合索引 (a,b,c) 必须从 a 连续命中。索引失效三大场景：索引列上使用函数或运算、隐式类型转换（字符串列传数字）、LIKE 前导 %。回表：二级索引查完拿主键再回聚簇索引取整行；覆盖索引：查询列全部在索引中，EXPLAIN 显示 Using index，无需回表。索引不是越多越好：每个索引都是一棵树，写操作同步维护、占存储、低区分度索引误导优化器。

## 事务与锁
ACID：原子性（全做或全不做，undo log 回滚）、一致性（数据约束不被破坏）、隔离性（MVCC + 锁）、持久性（redo log 刷盘）。并发三问题：脏读（读到未提交）、不可重复读（两次读同行结果不同）、幻读（范围查询行数变化）。InnoDB 默认 REPEATABLE READ，MVCC 快照读避免脏读与不可重复读，间隙锁辅助防幻读。死锁处理：统一资源访问顺序、缩小事务、更新条件列建索引。

## 数据库设计
三大范式核心：字段原子性、非主属性完全依赖主键、消除传递依赖。多对一冗余信息拆独立表外键关联。类型选择：金额用 DECIMAL(10,2) 禁用 FLOAT、手机号用 VARCHAR、状态用 TINYINT 枚举、主键用 BIGINT 自增或雪花 ID。索引设计：只为高频查询条件建索引，联合索引把等值列放前、范围列放后，如 idx_user_time(user_id, create_time)。

## 查询优化
慢 SQL 治理流程：慢查询日志定位 → EXPLAIN 分析（type 为 ALL 即全表扫描、key 看实际索引、rows 看扫描行数）→ 优化改写。典型优化：让索引列裸露（YEAR(create_time)=2026 改写为范围条件）、只查需要的列避免 SELECT *、深分页用游标定位替代大 offset。ORDER BY 列尽量与索引顺序一致避免 filesort。
