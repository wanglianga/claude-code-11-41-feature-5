# 软件依赖清单数据质量与供应链元数据服务

面向软件供应链的**元数据事实层**：回答“某次构建实际包含哪些组件、这些组件在构建发生时可由哪些 SBOM / 包仓元数据 / 锁文件 / 源码扫描 / 签名证据支持、哪些依赖只能作为候选而不能当作已证实事实”。

系统**不裁决许可证，不构建、签名、发布或分发制品**，只交付可追溯的软件供应链元数据事实。

本版本新增**时点数据解释服务**：审计人员查看历史供应链数据时，既能追问“某个组件为何被列入”，也能追问“另一组件为何没有出现”。解释以**业务时点**（观察哪次构建在何时的事实）与**资料截止时间**（当时已收到哪些依据）两个时点为输入，逐项给出出现 / 缺席 / 未知 / 冲突的结论与完整推导。

## 架构

```
┌─────────────┐   ┌──────────────────────────────────────────────────┐
│  Vue 3 + TS │   │              Spring Boot (Java 25)                │
│  Element +  │──▶│  来源保全册 → 身份工作区 → 依赖编译器 → 事实签发器 │
│  Vite       │   │       ↓ 消费目录 ← 变动监测器 ← 复审事件          │
│             │   │  时点复演引擎 → 解释服务（推导图 / 精简组合 /     │
│             │   │  双时点对比 / 导出重算）                          │
└─────────────┘   └──────────────────────┬───────────────────────────┘
                                          ▼
                                    PostgreSQL 18
```

- **前端**：`frontend/` — Vue 3 + TypeScript + Vite + Element Plus + Pinia + ECharts
- **后端**：`backend/` — Java 25 + Spring Boot 4 + Spring Data JPA
- **数据库**：PostgreSQL（容器内已预装 `ccdev/ccdev@127.0.0.1:5432`）

## 核心子系统

| 子系统 | 职责 |
| --- | --- |
| 来源保全册 | 保存 SBOM、锁文件、构建日志、包仓响应、源码扫描、签名索引、组件目录、漏洞通告的原始载荷、采集/事件时间、来源地址、SHA-256、解析器版本、接收状态；追加式不可改写 |
| 来源时间账 | 在保全册上记录修订链（`revisesId`）、同一原始记录标识（`originId`，转抄去重）、清单完整范围（`completeness`）与业务有效区间（`validFrom`/`validTo`） |
| 组件身份工作区 | 按生态、命名空间、发布者、坐标、版本、哈希与构建上下文归并组件声明，维护候选 / 已确认 / 来源冲突 / 证据不足身份 |
| 依赖事实编译器 | 区分被实际解析证据支持的已证实边（PROVEN）与仅由包仓/扫描推断的候选边（CANDIDATE）；冲突或证据不足的边不可证实（REJECTED） |
| 构建事实签发器 | 在截止点冻结关系、规则版本与来源对象哈希，签发不可变事实；截止点后到达的来源登记为后续差异来源 |
| 消费目录 | 记录下游风险视图、资产清单、审计视图对事实版本的引用与当时身份版本快照 |
| 变动监测器 | 目录别名/发布者修订或签名撤销时定位当前引用、暂停下游视图；历史分析保留当时身份版本 |
| 时点复演引擎 | 固定业务时点、资料截止时间、规则与查询条件：先选出两个时点下的可用事实（修订链按生效与接收条件适用、转抄只算一份独立支持），再完成关联（依赖边与身份有效区间必须有交集），输出出现/缺席/未知/冲突条目及完整推导图 |
| 解释服务 | 在完整推导上求不可再删减的充分证据组合（默认精简展示，可展开全部分支；预算截断时如实报告覆盖范围）；双时点对比并排归因到补证/修订；导出可用相同输入重新计算 |

### 复演与解释语义（rules-1.0 之上）

1. **两个时点分别指定**：业务时点限定观察哪次构建在何时的事实；资料截止时间限定当时已收到哪些依据。同一业务时点采用不同资料截止时间可得到不同结论，对比时变化归因到具体补证来源或修订。
2. **先选事实再关联**：复演先在两个时点下选出可用事实（来源已接收、修订版已生效），再完成关联；依赖边与组件身份的有效区间没有交集时，不能拼出一条从未成立过的链路。
3. **修订链适用**：同一来源的更正版按生效（`validFrom<=B`，空为始终生效）与接收（`collectedAt<=K`）条件确定适用范围；被取代版本保留说明。复制到多个文件的同一原始记录（共享 `originId`）只算一份独立支持。
4. **互斥身份**：存在互斥身份候选时分别保留成立条件，不同候选下的边不得合并证明共同可达。
5. **共同必需与可相互替代**：确认依赖同时需要构建记录、包身份与关系原文（AND，缺一不可）；另一份独立清单可单独提供完整的替代依据（OR）。推导图可展开到原始位置（来源地址 + 载荷路径）。
6. **缺席分类**：来源未收到 / 文件尚未解析完整 / 身份未决 / 被具体条件排除 / 完整来源确实未包含。只有资料覆盖范围足以支持否定时才认定该范围内不存在，不能把未知解释成没有；依赖空值、否定筛选或来源完整性的排除结论保留相应判断条件。
7. **精简组合**：默认提供经过核实的不可再删减充分证据组合（只裁剪展示依据，不改变计算输入，不隐藏反证与互斥条件）；不可再删减不代表全局最短或唯一解释。搜索达到预算时返回已核实组合与尚未检查的分支，不声称穷尽；没有找到组合也不否定已算出的记录。

### 身份确认规则（rules-1.0，签发时随事实冻结）

1. 声明按 `生态|命名空间|名称|版本` 分组，再按 `发布者|哈希` 归并为身份；生态缺失按 `unknown` 处理，不跨生态合并。
2. 实际解析证据（SBOM/锁文件/构建日志）哈希一致 → `CONFIRMED`。
3. 解析证据间哈希冲突且构建日志不能证明任一哈希 → 全部 `CONFLICT`（来源冲突），编译器不得签发已证实依赖。
4. 构建日志确认某一哈希 → 该哈希身份 `CONFIRMED`。
5. 仅包仓元数据/源码扫描推断 → `CANDIDATE`；同名异发布者且缺生态上下文 → 多个候选并存，不误合并。
6. 签名作为唯一依据可确认；签名撤销后降为 `INSUFFICIENT_EVIDENCE`，新事实不能继续显示已证实。

## 本地启动

### 前置条件

- Java 25（`/opt/jdk-25`）、Maven 3.9（`/opt/maven`）、Node 22（`/opt/node-22`）
- PostgreSQL 运行于 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`

### 后端

```bash
cd backend
/opt/maven/bin/mvn package -DskipTests
/opt/jdk-25/bin/java -jar target/scf-metadata-service-1.0.0.jar
# 监听 http://127.0.0.1:8080
```

数据库表结构由 JPA 自动创建（`ddl-auto=update`），无需手工建表。

### 前端

```bash
cd frontend
/opt/node-22/bin/npm install --registry=https://registry.npmmirror.com
/opt/node-22/bin/npm run dev        # 开发模式 http://127.0.0.1:5173（/api 代理到 8080）
# 或生产构建 + 预览
/opt/node-22/bin/npm run build
/opt/node-22/bin/npm run preview    # http://127.0.0.1:4173
```

### 测试账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `admin123` | ADMIN（可写：摄入、编译、签发、修订、撤销） |
| `viewer` | `viewer123` | VIEWER（只读） |

> 演示账号仅用于本隔离开发环境，非生产配置。

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

- 前端：http://127.0.0.1:8088
- 后端 API：http://127.0.0.1:8080
- PostgreSQL：127.0.0.1:5432（`ccdev/ccdev`）

Compose 编排 `db`（postgres:18，含健康检查）→ `backend`（多阶段构建）→ `frontend`（nginx，代理 `/api` 到后端）。

## 验证方式

### 1. 后端规则测试（JUnit + H2）

```bash
cd backend
/opt/maven/bin/mvn test
```

`RuleEngineTests` 覆盖六大验收规则：同名异发布者保留多候选、哈希冲突阻断已证实依赖、私有镜像留待核项、构建后补传登记为后续差异、目录别名修订暂停下游视图、签名撤销降级身份。

`ReplayExplanationTests` 覆盖复演与解释规则：双资料截止结论不同且变化归因、追溯修订按生效/接收适用、有效区间错开不能拼出链路、转抄去重、互斥身份不合并、缺席分类、精简组合不可再删减、预算提前停止如实报告、相同输入重算结论一致、导出含可重算输入。

### 2. 验收场景（API 一键运行）

服务启动后，前端「验收场景」页可逐个运行；或用命令行：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')
for s in same-name-diff-publisher hash-conflict private-mirror late-lockfile catalog-alias-revision signature-revocation \
         replay-late-source replay-revision replay-validity-copies replay-exclusive-absence replay-minimal-evidence; do
  echo "== $s =="; curl -s -X POST http://127.0.0.1:8080/api/scenarios/$s/run -H "Authorization: Bearer $TOKEN"
done
```

每个场景返回 `checks` 列表，`passed=true` 表示该验收点通过：

| 场景 | 验收点 |
| --- | --- |
| `same-name-diff-publisher` | 同名包在 npm 与内部包仓发布者/哈希不同且缺生态上下文 → 保留多个候选，不合并为已确认 |
| `hash-conflict` | SBOM 与锁文件坐标相同哈希不同、构建日志不能证明 → 标为来源冲突，编译器与签发事实均无已证实边 |
| `private-mirror` | 私有镜像字段变体单独映射并留待核项，声明保持候选 |
| `late-lockfile` | 锁文件在截止点后补传且版本不同 → 原事实按截止点证据签发且不被改写，补传件登记为后续差异来源 |
| `catalog-alias-revision` | 目录修订别名/发布者 → 监测器暂停下游视图，历史视图保留当时身份版本快照 |
| `signature-revocation` | 唯一依据的签名撤销 → 组件降为证据不足，新事实不能继续显示已证实 |
| `replay-late-source` | 晚到资料：同一业务时点两个资料截止结论不同（来源未收到→已证实），变化归因到补传锁文件；原复演结果保持可回查；导出含相同输入可重算 |
| `replay-revision` | 追溯修订：更正版按生效与接收条件适用（旧版被取代留说明，未来生效版不提前适用）；后来确认的别名不用于解释此前的清单 |
| `replay-validity-copies` | 有效区间错开：关系与身份区间无交集 → 未知且保留区间条件；同一原始记录转抄到多文件只算一份独立支持 |
| `replay-exclusive-absence` | 互斥身份不合并证明；完整清单未包含 → 缺席，残缺清单不足 → 未知；未解析文件、否定筛选、空值判断逐项分类并保留条件 |
| `replay-minimal-evidence` | 多组替代支持：9 组精简组合均核实不可再删减；预算提前停止返回已核实组合与未检查分支且不声称穷尽；预算为零也不否定已算出记录 |

### 3. 全链路追溯

任一构建事实可回查：来源对象（含后续差异来源）、身份关系（冻结快照 vs 当前状态 + 支撑声明）、依赖边、冻结版本、复审事件、下游引用。

```bash
curl -s http://127.0.0.1:8080/api/build-facts -H "Authorization: Bearer $TOKEN"   # 取 fact id
curl -s http://127.0.0.1:8080/api/build-facts/<factId>/trace -H "Authorization: Bearer $TOKEN"
```

前端「构建事实签发」页点击「追溯」可视化查看。

### 4. 时点复演与解释

从历史结果的一行（「构建事实签发」页「解释」按钮）或「时点复演与解释」页进入：

```bash
# 创建复演：固定业务时点、资料截止时间与查询条件
curl -s -X POST http://127.0.0.1:8080/api/explain/replay -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "buildId": "<buildId>",
    "businessTime": "2026-11-14T06:00:00Z",
    "knowledgeCutoff": "2026-11-14T12:00:00Z",
    "queries": [{"ecosystem":"npm","name":"late-lib","version":"2.0.0"}],
    "excludeScopes": ["test"], "requireFields": ["publisher"]}'

# 逐项解释（含精简组合；budget 控制搜索预算）
curl -s "http://127.0.0.1:8080/api/explain/items/<itemId>/explanation?budget=10000" -H "Authorization: Bearer $TOKEN"

# 双时点对比：同一业务时点切换资料截止时间
curl -s -X POST http://127.0.0.1:8080/api/explain/replays/<taskId>/compare \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"otherCutoff":"2026-11-17T00:00:00Z"}'

# 导出：内容含相同输入，可重新计算
curl -s http://127.0.0.1:8080/api/explain/replays/<taskId>/export -H "Authorization: Bearer $TOKEN"
```

页面显示适用资料、关联路径（AND 共同必需 / OR 可相互替代，可展开到原始位置）、条件判断与未决原因；默认展示经过核实的精简组合，可切换展开所有分支；反证与互斥条件不隐藏。

## 已执行的验证结论

- `mvn test`：13/13 通过（H2 内存库），含 `RuleEngineTests` 6 项规则测试与 `ReplayExplanationTests` 7 项复演解释测试。
- 后端连接本地 PostgreSQL 18 启动成功，JPA 自动建表（含来源时间账新列与复演任务/条目表）。
- 通过 API 运行全部 11 个验收场景（6 个规则场景 + 5 个复演场景，共 55 项检查），全部通过，且重复运行结果一致。
- 复演解释 API 实测：精简组合默认穷尽核实（9/9 组）、`budget=5` 时返回 3 组已核实组合与 2 条未检查分支且不声称穷尽、双时点对比把 late-lib 的变化归因到补传锁文件、导出含相同输入可重算。
- 前端 `npm run build`（含 vue-tsc 类型检查）通过；`vite preview` 下联调登录、复演创建、解释抽屉、对比与导出均正常。
- 权限验证：未认证 401；viewer 只读（GET 200 / POST 403）。
- Docker Compose 配置已静态核对（服务编排、健康检查、多阶段构建），本容器内未实际执行 `docker compose up`。

## 主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录获取令牌 |
| POST | `/api/sources` | 摄入来源（保全原始载荷；支持修订链 `revisesId`、原始记录 `originId`、清单完整范围 `completeness`、业务有效区间 `validFrom`/`validTo`） |
| GET | `/api/sources` `/api/sources/{id}` | 来源列表/详情（含提取的声明） |
| GET | `/api/identities` `/api/identities/{id}` | 身份列表/详情（含支撑声明） |
| POST | `/api/compile/{buildId}` | 运行依赖事实编译器 |
| GET | `/api/edges?buildId=` | 依赖边列表 |
| POST | `/api/build-facts/issue` | 签发构建事实（可指定截止点） |
| GET | `/api/build-facts/{id}/trace` | 全链路追溯 |
| POST | `/api/catalog/entries` `/api/catalog/entries/{id}/revise` | 目录登记/修订（修订可指定业务生效时间 `effectiveFrom`） |
| POST | `/api/catalog/views` | 创建下游视图 |
| POST | `/api/signatures/{id}/revoke` | 撤销签名 |
| GET | `/api/monitor/events` | 变动监测事件 |
| GET | `/api/review-events` | 复审事件 |
| POST | `/api/explain/replay` | 创建并执行复演任务（固定业务时点、资料截止时间、查询条件） |
| GET | `/api/explain/replays` `/api/explain/replays/{id}` | 复演任务列表/详情（含输出条目） |
| GET | `/api/explain/items/{itemId}/explanation?budget=` | 逐项解释（适用资料、推导图、精简组合、反证与互斥） |
| POST | `/api/explain/replays/{id}/compare` | 双时点对比（同一业务时点切换资料截止，变化归因） |
| GET | `/api/explain/replays/{id}/export` | 导出（含相同输入，可重新计算） |
| POST | `/api/scenarios/{name}/run` | 运行验收场景 |

## 边界

- 不裁决许可证；漏洞通告仅保全原始载荷，不做处置。
- 不执行构建、签名、发布、分发。
- 已签发事实不可改写；修正通过新版本事实表达。
- 复演与解释只读已有事实账并另行保存复演任务，不改写来源、身份与已签发事实；不重新采集外部漏洞信息，不处理制品发布。
- 缺席结论只在资料覆盖范围足以支持否定时给出；未知不被解释成没有。
