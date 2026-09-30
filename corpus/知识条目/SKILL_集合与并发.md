---
kp: 集合框架
category: SKILL
source: 《Java 并发与集合实战》
---
## 集合框架
集合分两大体系：Collection（List/Queue/Set）与 Map。选型口诀：随机读取多选 ArrayList（数组，O(1) 读）、头尾增删多选 LinkedList（双向链表）、去重选 HashSet、键值映射选 HashMap、需要排序选 TreeMap。JDK 8 中 ArrayList 初始容量 0，首次 add 扩到 10，之后 1.5 倍扩容。遍历中直接调 list.remove 会抛 ConcurrentModificationException，应使用迭代器 remove 或 removeIf。

## 泛型
泛型提供编译期类型检查，避免强制转换与 ClassCastException。核心机制是类型擦除：编译后类型参数被替换为边界（默认 Object），运行时 List<String> 与 List<Integer> 是同一个 Class。泛型不支持协变：List<String> 不能赋给 List<Object>，需用通配符。PECS 口诀：生产者（读取）用 extends 上界，消费者（写入）用 super 下界。

## 多线程
线程创建两种方式：继承 Thread、实现 Runnable/Callable。count++ 是"读-改-写"非原子操作，多线程交错会丢失更新，volatile 只保证可见性与有序性、不保证原子性，原子场景用 AtomicInteger 或 synchronized。线程池七参数：核心线程数、最大线程数、空闲存活时间、时间单位、任务队列、线程工厂、拒绝策略；扩容顺序为核心满先入队，队列满才建非核心线程。HashMap 并发不安全，并发容器选 ConcurrentHashMap（CAS + synchronized 锁桶头节点）。

## 异常处理
异常体系：Throwable 下分 Error（不捕获处理）与 Exception。Exception 分 checked（IOException 等，编译器强制捕获或声明）与 unchecked（RuntimeException 及子类）。最佳实践：不要吞异常（空 catch）、不要用异常控制业务流程、资源关闭用 try-with-resources。

## IO 流
字节流（InputStream/OutputStream）以字节为单位适合二进制文件，字符流（Reader/Writer）处理编码适合文本。缓冲流把多次小块 IO 合并为大块，显著减少系统调用。复制图片等二进制文件必须用字节流，字符流的编解码会损坏文件。读写文件统一使用 try-with-resources 自动关闭。
