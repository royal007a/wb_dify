# Hify 前端地基与一键启动交付

基线日期：2026-09-12。本文件记录“前端工程 → 前后端联通 → 启停脚本”的已实现事实与验收证据。

## 1. 交付范围

| 层次 | 已实现 | 关键路径 |
|---|---|---|
| Vue 工程 | Vue 3、TypeScript、Vite、Element Plus | `frontend/package.json`、`frontend/vite.config.ts` |
| 请求层 | axios 实例；统一识别 `Result<T>`；成功自动解包 `data`；失败统一提示并 reject | `frontend/src/utils/request.ts` |
| 健康 API | `getHealth()` 调用 `/api/v1/health`（axios 的 `/api` baseURL + `/v1/health`） | `frontend/src/api/health.ts` |
| 路由与页面 | 模型管理、Agent 管理、对话；根路径重定向到模型管理 | `frontend/src/router/`、`frontend/src/views/` |
| 布局 | Element Plus 左侧菜单 + 右侧 `router-view` | `frontend/src/App.vue` |
| 联通状态 | 模型管理页加载时请求健康接口，成功显示绿色“后端已连接：Hify is running” | `frontend/src/views/ProviderList.vue` |
| Query Loop 页面 | 保留会话创建、Run、SSE 事件、工具提示、取消能力 | `frontend/src/views/ChatView.vue` |
| 设计系统与组件 | 浅色后台令牌、响应式深色导航、表格/表单/确认/请求/通知公共组件 | `frontend/src/styles/tokens.css`、`frontend/src/components/`、`docs/FRONTEND_DESIGN_SYSTEM.md` |
| 开发代理 | `localhost:5173/api/**` 转发到 `localhost:8080` | `frontend/vite.config.ts` |

## 2. 统一接口契约

后端健康接口：

```http
GET /api/v1/health
HTTP/1.1 200
Content-Type: application/json

{"code":200,"message":"success","data":"Hify is running"}
```

前端响应拦截器仅将 `code === 200` 视为业务成功，并直接返回 `data`。HTTP 失败或业务失败都会显示错误提示并 reject，页面无需重复解包。

## 3. 启动与停止

```bash
./start.sh
./stop.sh
```

`start.sh` 的顺序和失败边界：

1. 检查 Java、Maven、npm、curl、lsof 和端口。
2. 未显式配置 `HIFY_DB_URL` 时，准备名为 `hify-dev-postgres` 的 PostgreSQL 16 容器，并等待 `pg_isready`。
3. Redis 默认关闭；只有 `HIFY_REDIS_ENABLED=true` 时才检查 Redis。
4. 构建后端并以 `java -jar` 启动，将 PID/日志写到 `.hify/`。
5. 轮询 `/api/v1/health`，60 秒内未得到 200 就输出日志并回收本次进程。
6. 准备前端依赖、启动 Vite，并轮询 5173 端口。
7. macOS 默认打开浏览器；自动化场景可设 `HIFY_NO_OPEN=1`。

`stop.sh` 只依据 `.hify/*.pid` 停止本项目应用进程，先 SIGTERM、等待 10 秒、必要时 SIGKILL。PostgreSQL 数据容器默认保留；仅在 `HIFY_STOP_DATABASE=true` 时停止容器。

Make targets：`start`、`stop`、`restart`、`build`、`clean`、`package`。`make package` 产出包含可执行后端 JAR、前端静态资源和 Nginx 配置的 `dist-packages/hify-<version>.tar.gz`。

## 4. 本轮验证证据

- `npm run typecheck`：通过。
- `npm run build`：通过；Vite 生产构建完成（大 chunk 警告不阻塞本阶段）。
- 当时的前端阶段 checkpoint：`mvn test` 有 12 个普通测试通过；它是历史快照。基础组件阶段的当前测试数字见 `FOUNDATION_COMPONENTS.md`。
- `./start.sh`：成功准备 PostgreSQL，构建并启动两端。
- 后端直连 `/api/v1/health`：HTTP 200，响应契约正确。
- 经 Vite 代理访问 `/api/v1/health`：HTTP 200，响应与直连一致。
- `/providers`、`/agents`、`/chat`：均返回 HTTP 200。
- 无头 Chrome 渲染 `/providers`：左侧三项菜单、模型提供商页面、绿色连接状态均可见。
- `./stop.sh`：前后端均退出，8080/5173 端口关闭。

## 5. 当前边界

- 模型管理页当前是完整交互的 mock 验收页，尚未接真实 Provider CRUD；Agent 管理仍是空壳；对话页保留初版 Query Loop Playground。
- 当前开发态脚本依赖本机 Docker 来准备 PostgreSQL；如使用外部数据库，应显式提供 `HIFY_DB_URL`、`HIFY_DB_USERNAME`、`HIFY_DB_PASSWORD`。
- Element Plus 当前整包导入，生产包有大 chunk 警告；按需引入和路由懒加载进入后续前端性能任务，不阻塞工程初始化。
