---
kp: JavaSE 基础
category: SKILL
source: 《Java 核心技术手册》
---
## JavaSE 基础
JavaSE 是 Java 标准版核心语法，包括变量与数据类型、运算符、流程控制、方法、数组与面向对象三大特性。JVM 负责解释执行字节码实现跨平台，JDK = JRE + 编译工具。初学者常见误区：int 相除舍弃小数、== 比较引用地址而非内容（String 比较内容必须用 equals）。掌握标准：能独立完成控制台版学生管理系统，理解封装、继承、多态的实际用途。

## 开发环境
Java 开发环境三件套：JDK（开发工具包，推荐 JDK 17 LTS）、IDEA（社区版免费足够入门）、Maven（依赖管理与构建）。JDK 8 之后的长期支持版本为 11/17/21，企业新项目主流为 17。环境问题占新手报错的 60%：JAVA_HOME 未配置、IDEA 未指定 JDK 版本、Maven 镜像源不通是最常见的三类故障。

## 面向对象
面向对象三大特性：封装（private 属性 + public getter/setter，隐藏细节控安全）、继承（extends 复用父类，Java 单继承）、多态（父类引用指向子类对象，编译看左边运行看右边）。抽象类与接口的选择：有公共状态和模板方法用抽象类，只定义行为契约用接口，JDK 8 后接口可以有 default 方法。构造方法无返回值类型，未显式定义时编译器提供无参构造。

## 流程控制
Java 流程控制包括 if/else 分支、switch（支持 byte/short/char/int、枚举、String，不支持 long）、for/while/do-while 循环。case 穿透是高频坑：不写 break 会继续执行下一个 case。循环选择原则：次数已知用 for，条件驱动用 while，至少执行一次用 do-while。

## 方法与数组
方法重载看方法名与参数列表，与返回值无关。数组定长、类型单一，int[] 默认元素为 0，越界访问抛 ArrayIndexOutOfBoundsException。数组长度的固定性是集合框架诞生的原因之一：实际开发中 ArrayList 等集合可动态扩容、提供丰富 API，用得远多于裸数组。
