# 项目说明（Claude 工作上下文）

Spring 学习仓库：`docs/springlearn/` 下按主题组织学习文档（00 学习路径 → 01 IOC → 02 AOP → 03 MVC/事务/数据 → 04 SpringBoot → 05 Boot 工程化 → 06 Spring Cloud），`src/main/java/com/sw/yang/springlea/`（ioc/aop 包）与 `src/test/` 配套存放实验代码。本文件随 git 同步，任何机器打开本仓库都会加载。

## 用户学习方式（重要）
- **追问型学习者**：不接受二手解释，习惯把结论推回第一性原理；讲解必须先给因果链/设计动机，再给结论
- 反感"为解释而解释"的文档；希望主动指出文档中论证不完整处
- 学习法：两遍法——先通读建全景地图（疑问记入清单不深究），再统一用代码验证疑问清单
- 学习文档与验证代码配套推进（docs 每章对应 src/test 实验）

## 文档导航
- 入口：`docs/springlearn/00-Spring学习路径.md`（全景地图）
- 章节：01-Spring-IOC（5 篇，含 Ex-01）→ 02-Spring-AOP（7 篇）→ 03-Spring-MVC/Transaction/Data（6 篇）→ 04-SpringBoot（4 篇）→ 05-SpringBoot工程化（4 篇）→ 06-SpringCloud（1 篇，进行中）
- 实验记录：`docs/springlearn/experiment-01-call-graph.md`
- 源码阅读基准：Spring Framework 6.x（对应 Boot 3.3.9）；Spring Cloud 2023.0.x（对应 Boot 3.3.x）

## 构建注意
- Spring Boot 3.3.9 + Java 17（与并发学习项目同环境）
- 若默认 `java` 是 JRE 而非 JDK，编译报 "No compiler provided" 时需设 `JAVA_HOME` 指向真实 JDK（本机具体路径见本地记忆 build-env）
