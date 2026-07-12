# 实验一：@Import 三/四分支 — 完整调用链

基于 `ImportRootConfig` + `@Import(PlainImportedConfig, TraceImportSelector, TraceRegistrar)`。

方框=方法/入口，菱形=分支判断，圆角=模型/BeanDefinition。

```mermaid
graph TD
    subgraph "启动 & refresh"
        A["AnnotationConfigApplicationContext(ImportRootConfig.class)"]
        B["refresh()"]
        C["invokeBeanFactoryPostProcessors()"]
        D["postProcessBeanDefinitionRegistry()"]
        E["processConfigBeanDefinitions(registry)"]
    end

    A --> B --> C --> D --> E

    subgraph "Parser 阶段 (建模)"
        F["ConfigurationClassParser.parse()"]
        G["processConfigurationClass(ImportRootConfig)"]
        H["doProcessConfigurationClass()"]
        I{"processImports()"}
    end

    E --> F --> G --> H --> I

    subgraph "四个分支 (类名)"
        BR1["分支1: PlainImportedConfig"]
        BR2["分支2: TraceImportSelector"]
        BR3["分支3: TraceRegistrar"]
        BR4["分支4: DeferredImportSelector"]
    end

    I --> BR1
    I --> BR2
    I --> BR3
    BR4 -.-x I

    subgraph "分支1: 普通类"
        C1A["asConfigClass()"]
        C1B["processConfigurationClass() 递归"]
        C1C["doProcessConfigurationClass()"]
        C1D["收集 @Bean plainBean()"]
    end

    BR1 --> C1A --> C1B --> C1C --> C1D

    subgraph "分支2: ImportSelector"
        C2A["instantiateClass()"]
        C2B["selectImports()"]
        C2C["返回 [SelectorImportConfig]"]
        C2D["递归 processImports(SelectorImportConfig)"]
        C2E["→ 走分支1普通类"]
        C2F["收集 @Bean selectorBean()"]
    end

    BR2 --> C2A --> C2B --> C2C --> C2D --> C2E --> C2F

    subgraph "分支3: ImportBeanDefinitionRegistrar"
        C3A["instantiateClass()"]
        C3B["configClass.addImportBeanDefinitionRegistrar()"]
        C3C["⚠️ 暂存！不执行 registerBeanDefinitions()"]
    end

    BR3 --> C3A --> C3B --> C3C

    subgraph "Reader 阶段 (落地)"
        R1["ConfigurationClassBeanDefinitionReader.loadBeanDefinitions()"]
        R2{"遍历每个 ConfigurationClass"}
        R3["处理 @Bean: loadBeanDefinitionsForBeanMethod()"]
        R4["处理 Registrar: loadBeanDefinitionsFromRegistrars()"]
        R5["调用 TraceRegistrar.registerBeanDefinitions()"]
        R6["registry.registerBeanDefinition('registrarBean')"]
    end

    C1D --> R1
    C2F --> R1
    C3C --> R1
    R1 --> R2
    R2 --> R3
    R2 --> R4
    R4 --> R5 --> R6

    subgraph "最终结果"
        RES1["BeanDefinition: plainBean"]
        RES2["BeanDefinition: selectorBean"]
        RES3["BeanDefinition: registrarBean"]
    end

    R3 --> RES1
    R3 --> RES2
    R6 --> RES3
```

---

## 关键时间线

```text
Parser（只建模，不注册）
│
├── ImportRootConfig 解析开始
│   ├── processImports() 遍历三个导入候选
│   │
│   ├── [1] PlainImportedConfig → 普通类
│   │   └── 递归 processConfigurationClass()
│   │       └── doProcessConfigurationClass()
│   │           └── 发现 @Bean plainBean
│   │               └── configClass.addBeanMethod(plainBean) ← 仅建模
│   │
│   ├── [2] TraceImportSelector → ImportSelector
│   │   └── 立即执行 selectImports()
│   │       └── 返回 "SelectorImportConfig"
│   │           └── 递归 processConfigurationClass()
│   │               └── 发现 @Bean selectorBean
│   │                   └── configClass.addBeanMethod(selectorBean) ← 仅建模
│   │
│   └── [3] TraceRegistrar → Registrar
│       └── configClass.addImportBeanDefinitionRegistrar(traceRegistrar) ← 暂存！
│
├── DeferredImportSelector 处理 (本实验无)
│
└── validate()

Reader（统一落地到注册表）
│
├── loadBeanDefinitions()
│   ├── 遍历 ImportRootConfig 模型 → 注册其 BeanDefinition
│   ├── 遍历 PlainImportedConfig 模型 → 注册 plainBean BeanDefinition
│   ├── 遍历 SelectorImportConfig 模型 → 注册 selectorBean BeanDefinition
│   │
│   └── loadBeanDefinitionsFromRegistrars()
│       └── TraceRegistrar.registerBeanDefinitions() → 注册 registrarBean ← 此时才执行！
```

## 分支对比总结

| | 普通类 | ImportSelector | Registrar | DeferredImportSelector |
|---|---|---|---|---|
| Parser 阶段做什么 | 递归解析建模 | 执行 selectImports() 后递归解析返回的类 | **只暂存，不执行** | 暂存到 Handler |
| Reader 阶段做什么 | 注册 @Bean | 注册 @Bean | **执行 registerBeanDefinitions()** | 排序分组后走 Selector 流程 |
| 会立即改注册表吗 | 不会 | 不会 | 不会，Reader 才改 | 不会 |
| 本实验涉及吗 | ✅ | ✅ | ✅ | ❌ (实验二) |
