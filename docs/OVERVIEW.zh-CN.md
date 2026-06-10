# Arxael — 系统总览（概念地图）

> 各部分如何组合在一起的简化地图（自动生成）。关于*为什么*这样做，请以
> [ARCHITECTURE.md](ARCHITECTURE.md) 为准；本文只讲结构。
>
> 🌐 English version: [OVERVIEW.md](OVERVIEW.md)

**一句话：** 让许多受信任的本地 AI 智能体（agent）在**同一个项目**上、通过运行在你自己机器上的**同一个常驻、
有限流、共享的执行器**协作——它们各自开分支 → 测试 → 提交 PR → **合并进 main**，又快又不冲突，而且机器会
**自动调整规模**。优势在于**密度**（单台机器在崩溃前能撑住多少智能体），而非单次构建的速度。

> **颜色图例**（下方每张图通用）：
> 🔵 智能体/调用方 · 🟡 守门员/管控 · 🟣 构建服务 / 核心 · 🟢 成功 · 🔴 失败 · ⬜ 配置/辅助 · 🟦 产物

---

## 0. 通俗版

一共三个朴素的想法，其余都是细节。

### (a) 一个共享的构建服务，而不是每人一个

**场景：** 很多 AI 编码智能体在同时干活。每当一个智能体改了代码，它都要**构建并测试**这段代码，
确认改动没问题。构建/测试是又慢又重的环节——它需要一个启动很慢、占用大量内存的程序。

**别人怎么做：** 给每个智能体一台**自己的独立机器**，每次都从冷状态启动自己的构建程序。
一两个智能体还好。但同时跑很多个时，所有这些构建程序一起启动 → 机器内存耗尽，**整个系统崩溃**。

**我们怎么做：** 运行**一个共享的构建服务**，它已经启动并保持就绪（所以没有缓慢的冷启动）。
所有智能体把构建/测试请求都发给它。一个**守门员**只放行机器真正能处理的请求数量，
所以系统始终很快、绝不崩溃。

> 真正重要的问题不是*“一次构建有多快？”*（两种方式都一样）——
> 而是*“一台机器在崩溃前能让多少个智能体共享？”*

```mermaid
flowchart LR
    subgraph them["❌ 别人的做法：每个智能体一台独立机器"]
        c1["🤖 智能体"]:::agent --> o1["冷启动的构建程序"]:::bad
        c2["🤖 智能体"]:::agent --> o2["冷启动的构建程序"]:::bad
        c3["🤖 智能体"]:::agent --> o3["冷启动的构建程序"]:::bad
        o1 & o2 & o3 --> boom["💥 内存耗尽<br/>机器崩溃"]:::boom
    end
    style them fill:#fef2f2,stroke:#dc2626,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
    classDef boom fill:#dc2626,stroke:#991b1b,color:#ffffff,font-weight:bold
```

```mermaid
flowchart LR
    subgraph us["✅ 我们的做法：一个共享构建服务，保持就绪"]
        k1["🤖 智能体"]:::agent --> gate
        k2["🤖 智能体"]:::agent --> gate
        k3["🤖 智能体"]:::agent --> gate
        k4["🤖 智能体"]:::agent --> gate
        gate["🚦 守门员<br/>（只放行容得下的数量）"]:::gate --> svc["⚡ 共享构建服务<br/>已启动，保持就绪"]:::service
        svc --> happy["😀 始终很快，<br/>不会崩溃"]:::good
    end
    style us fill:#f0fdf4,stroke:#16a34a,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
```

**一次构建请求，从头到尾：**

```mermaid
flowchart TB
    a["智能体改动了代码"]:::agent --> b["发出请求：<br/>'构建并测试这个'"]:::agent
    b --> c{"守门员：<br/>还有空位吗？"}:::gate
    c -->|有| d["共享服务构建并测试<br/>（快——因为已在运行）"]:::service
    c -->|"没有，太忙了"| e["被告知稍候<br/>稍后重试"]:::bad
    d --> f["返回结果：<br/>👍 通过 / 👎 发现问题"]:::good
    e --> b
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### (b) 把每个人的工作安全地汇入同一个项目

所有智能体都在**同一个项目**上干活，各自有一份自己的副本（一个“分支”）。当一个智能体的改动通过测试后，
它会请求把这个改动**汇入主共享副本**。一个**协调器**会先检查这个改动*与其他人的改动合在一起*是否仍然能正常工作，
然后才保留它——如果它弄坏了什么，就会被悄悄撤销，所以**主副本永远不会停止工作**。

```mermaid
flowchart TB
    a["智能体在自己的副本<br/>（分支）上完成改动"]:::agent --> b["测试它——通过 ✓"]:::good
    b --> c["请求汇入<br/>主共享项目"]:::agent
    c --> d{"协调器：与其他人的改动<br/>合在一起还能正常工作吗？"}:::gate
    d -->|能| e["汇入主项目 🎉"]:::good
    d -->|"不能——它弄坏了东西"| f["悄悄撤销；<br/>主项目保持正常"]:::bad
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### (c) 服务会自动调整规模

构建服务在运行时会一直盯着机器的内存。如果内存吃紧，它就**一次少做一点**（这样永远不会崩溃）；
如果还有富余，它就**多做一些**（这样不浪费机器）。你不用手动去调它，而且随着项目变大它也照样能撑住。

```mermaid
flowchart LR
    tight["内存吃紧"]:::bad -->|"少做一点"| svc["⚖️ 构建服务<br/>自我调整"]:::service
    spare["还有富余"]:::good -->|"多做一些"| svc
    svc --> safe["永不崩溃，<br/>也不浪费机器"]:::good
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

---

## 0b. 执行器之上：工作流层 + 自适应层（建于其上）

常驻执行器是地基。两层建于其上，让产品成为完整形态——*许多智能体、一个项目，开分支 → 测试 → PR →
**合并进 main**，又快又不冲突，且机器自动调整规模*。深入阅读：[ARCHITECTURE.md](ARCHITECTURE.md)、[SETUP.md](SETUP.md)。

- 🟣 **合并编排器（Merge orchestrator）**（`dev.arxael.merge`，接口 `/merge/{register,submit,status}`）。
  智能体提交经过分支测试的 PR；编排器把它们无冲突地落到共享的 `main` 上。它按每个 PR 的**依赖闭包大小**
  （从项目的 Gradle 图自动发现）**自动选路**：闭包小 → **乐观落地 + 模块级异步门禁**，一旦出错就自动回滚
  （即时、不连锁）；闭包大 → **批量先门禁后落地**，永不弄坏 main，并在某批变红时归因到罪魁 PR。
  门禁测试跑在执行器**预留的高优先级通道**上，因此落地永远不会被分支测试饿死。

- 🟡 **自适应自动调整规模（Adaptive auto-sizing）**（`dev.arxael.autosize`）。由机器静态推导的上限只是个起点；
  一个调速器（governor）在硬性上下界 `[floor, ceiling]` 内，根据**实测内存压力**调整在线并发上限 + 构建工作线程数
  （二者耦合 `C·W ≈ 核数`），学习并**持久化**真实的单次构建内存足迹，并按观测到的构建时长缩放过载超时。
  于是密度既跟得上机器的真实极限，也跟得上项目的增长——内存吃紧前先收缩，有富余时再增长，双向自适应。

- 🟦 **共享但无锁的依赖缓存。** 每工作树独立的 Gradle home 现在是**默认**——它去掉了跨进程缓存锁
  （把构建数卡在约 8 个的并发天花板），但会导致重复下载依赖（Maven 429）。于是守护进程以**只读**方式
  （`GRADLE_RO_DEP_CACHE`）把依赖供给每个每工作树构建，并由一个后台**整合器（consolidator）**把新下载的
  依赖折叠进该共享缓存——重复下载收敛到约零：依赖共享、无锁、不重复下载。

- 🟢 **变更感知的测试范围。** 合并门禁会看 PR *实际改了什么*（它的 diff）：只改文档（README、docs、图片）
  的 PR **完全跳过门禁**（不可能弄坏测试），而代码改动只针对它真正触及的模块来测——不会为一个小改动或文档
  改动去重测整个项目。

---

## 1. 仓库 / 构建拓扑

```mermaid
flowchart TB
    subgraph repo["arxael (Gradle, JVM 21 / Kotlin 1.9.24)"]
        settings["settings.gradle.kts<br/>包含 :core"]:::cfg
        rootbuild["build.gradle.kts (根)<br/>仅声明插件版本"]:::cfg
        subgraph core[":core （守护进程：执行器 + 合并 + 自动调规模）"]
            corebuild["core/build.gradle.kts<br/>插件: kotlin · serialization<br/>application · jacoco · pitest<br/>依赖: gradle-tooling-api 8.10.2"]:::cfg
        end
        wrapper["gradlew<br/>（锁定 Gradle 8.10.2）"]:::cfg
    end

    settings --> core
    rootbuild -.应用插件.-> core
    wrapper --> settings

    corebuild ==>|installDist| daemon([core 守护进程二进制]):::service
    corebuild ==>|test + jacocoTestReport| cov([覆盖率 XML]):::artifact
    corebuild ==>|pitest| mut([变异测试 XML]):::artifact

    style core fill:#ede9fe,stroke:#7c3aed,stroke-width:2px
    classDef cfg fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef artifact fill:#cffafe,stroke:#0891b2,color:#164e63
```

## 2. 配套基础设施（围绕构建）

```mermaid
flowchart LR
    subgraph scripts["scripts/ （运维生命周期）"]
        arxael["arxael<br/>up · verify · 命令行"]:::script
        install["install.sh<br/>JDK + Gradle + installDist"]:::script
        smoke["smoke.sh<br/>端到端验收"]:::script
        quality["quality.sh<br/>覆盖率 · 变异 · trivy"]:::script
    end

    subgraph bench["bench/ （基准测试 + 真实世界验证）"]
        benchpy["bench.py / run_sweep.sh<br/>密度扫描 (实验组×智能体×核数)"]:::bench
        mergesim["merge_sim.py<br/>合并策略原型"]:::bench
        realworld["realworld_oss / caffeine / chaos<br/>真实 OSS + 崩溃恢复验证"]:::bench
        analyze["analyze.py / sampler.py<br/>崩溃点 + 资源采样"]:::bench
    end

    subgraph fixtures["fixtures/"]
        hello["gradle-hello<br/>冒烟测试夹具 (ARXAEL_SMOKE_OK)"]:::fixture
    end

    subgraph docs["docs/"]
        d1["OVERVIEW · ARCHITECTURE"]:::cfg
        d2["SETUP · LIMITATIONS"]:::cfg
    end

    arxael ==>|installDist + register| daemon([core 守护进程]):::service
    smoke -->|/invoke| daemon
    smoke --> hello
    quality -->|gradlew test/jacoco/pitest| repo[(:core 构建)]:::service
    benchpy -->|常驻组: /invoke| daemon
    benchpy -->|容器组: docker gradle| ctr([每智能体一个容器]):::bad
    realworld -->|/invoke + /merge| daemon

    classDef script fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef bench fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef fixture fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef cfg fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef service fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

## 3. 运行时——整个守护进程（单一进程）

> **规则：** 并发来自 **N 个有限流的常驻服务器，每个工作树（worktree）一个**，
> 每个都由锁串行化——*绝不*靠让单个进程同时服务多个并发调用方来实现并发。
> 下面的一切都活在同一个常驻进程里，藏在回环 HTTP 接口之后。

```mermaid
flowchart TB
    fleet["AI 智能体群"]:::agent

    subgraph daemon["core 守护进程 (dev.arxael, 回环 127.0.0.1:8723)"]
        subgraph api["HTTP 接口 (InvokeServer)"]
            rbuild["/invoke · /warmup"]:::entry
            rmerge["/merge/register · submit · status"]:::entry
            robs["/health · /metrics · / (API 名片)"]:::entry
        end
        allow["ArgAllowlist<br/>失败即关闭的校验"]:::gate

        subgraph execlayer["常驻执行器（地基）"]
            exec["WarmExecutor<br/>AdjustableSemaphore 上限<br/>工作树→服务器 · LRU 淘汰"]:::core
            subgraph servers["N 个常驻服务器（每工作树一个，锁串行化）"]
                ws1["WorktreeServer A"]:::server
                ws2["WorktreeServer B"]:::server
            end
            adapters["Adapter SPI<br/>gradle · gradlew · command · noop"]:::core
        end

        gov["AdaptiveGovernor<br/>AIMD: 按内存压力调整上限<br/>学习足迹 · 耦合工作线程"]:::core
        depcache["共享只读依赖缓存<br/>Warmer + Consolidator"]:::artifact

        subgraph mergelayer["合并编排器 (dev.arxael.merge)"]
            router["MergeRouter<br/>按闭包大小自动选路"]:::core
            orch["MergeOrchestrator<br/>乐观落地+异步回滚 / 批量门禁"]:::core
            journal["PrJournal<br/>崩溃恢复"]:::aux
        end

        watchdog["Watchdog（探测 + 恢复）"]:::aux
        eventlog["EventLog（仅追加 JSONL）"]:::aux
    end

    fleet -->|构建/测试| rbuild
    fleet -->|落地一个 PR| rmerge
    rbuild --> allow --> exec
    exec --> ws1 & ws2 --> adapters
    adapters -->|"常驻连接 · 每工作树 home"| build([gradle / pytest / go / …]):::artifact
    depcache -.只读依赖.-> adapters
    gov -.在线调整.-> exec
    rmerge --> router --> orch
    orch -->|在预留高优通道上门禁| exec
    orch -.记日志.-> journal
    orch -->|"落地 / 自动回滚"| main([共享 main]):::artifact
    watchdog -.健康检查.-> servers
    exec -.事件.-> eventlog
    orch -.事件.-> eventlog

    style daemon fill:#faf5ff,stroke:#7c3aed,stroke-width:2px
    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef entry fill:#bfdbfe,stroke:#2563eb,color:#1e3a8a,font-weight:bold
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef core fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef server fill:#f3e8ff,stroke:#9333ea,color:#581c87
    classDef aux fill:#f1f5f9,stroke:#475569,color:#0f172a
    classDef artifact fill:#cffafe,stroke:#0891b2,color:#164e63
```

## 4. 合并工作流（自动选路）

> 两种策略，按每个 PR 的依赖闭包大小自动挑选。乐观落地带来**低延迟**；
> 分支门禁 + 模块级验证带来**正确性**（main 永不破）。

```mermaid
flowchart TB
    pr["提交 PR<br/>/merge/submit (分支, 模块)"]:::agent --> router{"MergeRouter：<br/>依赖闭包多大？"}:::gate

    router -->|"小 / 独立"| opt["乐观：立刻落地，<br/>异步验证"]:::core
    router -->|"大 / 深链"| batch["批量：先门禁后落地<br/>（每批一次测试）"]:::core

    opt --> ascan{"模块级<br/>异步门禁"}:::gate
    ascan -->|绿| done1["保持落地 ✓<br/>约 0.1s 落地时间"]:::good
    ascan -->|红| revert["只回滚这一个 PR<br/>（不连锁）"]:::bad

    batch --> bgate{"对整批门禁"}:::gate
    bgate -->|绿| done2["整批落地 ✓<br/>main 永不破"]:::good
    bgate -->|红| culprit["CulpritAttribution 指出<br/>罪魁 PR，再门禁其余"]:::bad

    classDef agent fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    classDef gate fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef core fill:#ede9fe,stroke:#7c3aed,color:#4c1d95,font-weight:bold
    classDef good fill:#dcfce7,stroke:#16a34a,color:#14532d
    classDef bad fill:#fee2e2,stroke:#dc2626,color:#7f1d1d
```

### 烙进运行时的关键不变量
- **每工作树独立的 Gradle home 现为默认**——去掉了把并发卡在 ~8 的共享 home 跨进程缓存锁；
  机器从“被锁限制”变为“被 CPU 限制”。
- **共享*只读*依赖缓存**（`GRADLE_RO_DEP_CACHE`）——每工作树 home 不重复下载；自填充的 consolidator
  让重复下载收敛到 ~0（关闭 Maven-429 阻塞点）。
- **并发上限是自适应的**——AIMD 调速器在 `[floor, ceiling]` 内按实测内存压力调整它，OOM 前先收缩、有富余时再增长。
- **合并自动选路**——乐观落地 + 模块级异步回滚（小闭包，快） vs 批量先门禁后落地 + 罪魁归因（大闭包，稳）；
  门禁跑在预留的**高优**通道上，落地永不被分支测试饿死。
- **分支门禁 = 正确性，乐观落地 = 低延迟**——二者合一：即时落地，main 永不破。
- **变更感知门禁**——只改文档的 PR 跳过门禁（不可能弄坏测试）；代码 PR 只针对其 diff 触及的模块来测，
  而非整个项目。
- **PrJournal 能扛重启**——重新入队“已提交但未完成”和“已落地但未验证”的 PR。
- **常驻连接绝不在每次调用后关闭**；**Watchdog 探测并在热路径之外恢复**（隔离 → 丢弃卡死连接 → 重建全新）；
  **EventLog 仅追加**——可重放的事实来源（同时投射到 Prometheus `/metrics`）。

---

*作为高层地图生成；组件名对应 `core/src/main/kotlin/dev/arxael/…`。*
