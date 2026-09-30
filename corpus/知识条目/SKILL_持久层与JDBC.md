---
kp: MyBatis
category: SKILL
source: 《持久层框架实战》
---
## JDBC
JDBC 是 Java 访问数据库的标准接口。流程：加载驱动 → DriverManager.getConnection → prepareStatement → execute → 处理 ResultSet → 逆序关闭。防 SQL 注入核心是 PreparedStatement 预编译：SQL 模板先编译成执行计划，参数以纯数据绑定，永远不会被解析为语法。批量插入用 addBatch/executeBatch，网络往返从 N 次降为个位数，配合 rewriteBatchedStatements=true 提升数十倍。

## 连接池
建立数据库连接的 TCP+认证开销在毫秒级，连接池预建连接复用并限制最大连接数保护数据库。核心参数：最小空闲、最大连接、获取超时、空闲回收。maxPoolSize 并非越大越好：过多连接导致数据库端上下文切换与锁竞争，吞吐反而下降。Druid 是国内主流：内置 SQL 监控、慢 SQL 统计、连接泄露检测与 WallFilter 防注入。

## MyBatis
MyBatis 半自动 ORM：SQL 写在 XML/注解中，Mapper 接口无实现类，依靠 JDK 动态代理关联执行。#{} 预编译占位防注入，${} 字符串拼接仅限白名单表名列名。列名与属性名不一致三种解法：列别名、开启 mapUnderscoreToCamelCase 驼峰映射、resultMap 显式映射。一级缓存 SqlSession 级默认开启，二级缓存 namespace 级分布式下易脏数据生产慎用。

## 动态 SQL
动态 SQL 四件套：<if> 条件拼接 + <where> 自动处理 AND 前缀；<foreach> 遍历集合拼 IN 查询（open/separator/close）；<choose> 多分支；<sql> 片段复用。防注入原则：值一律 #{}，动态表名列名只能 ${} + 白名单校验。多条件查询模板：<where> 中每个条件包一个 <if test="xxx != null and xxx != ''">。

## 关联映射与分页
association 一对一（订单→用户），collection 一对多（用户→订单列表）。N+1 问题：查 N 条主记录各发一次子查询，网络往返放大；解法 JOIN 一次查出或延迟加载按需触发。分页一律物理分页（LIMIT 或 PageHelper 插件），逻辑分页 RowBounds 全量查出内存截取，大数据量 OOM 风险；深分页用游标或子查询定位优化。
