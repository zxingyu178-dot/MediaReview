# Android

Kotlin + Jetpack Compose + Media3。

用户可见界面全部中文。

## 构建状态(0.5.0)

本机已具备 Android SDK(`D:\Android\Sdk`)+ JDK 21 + Gradle 8.9,**已在本机编译通过**:

```bash
./gradlew :app:assembleDebug      # BUILD SUCCESSFUL,产出 app-debug.apk ≈17.9MB
./gradlew :app:testDebugUnitTest  # BUILD SUCCESSFUL(ApiModelsTest 4 + UrlNormalizeTest 4,0 失败)
```

## 阶段 9:Server Profile / 首页 / 媒体库 / 媒体墙

- **Server Profile + 首页**(`feature/home`):显示服务器地址、设备编号(UUID)、配对态,提供进入媒体库选择与媒体墙的入口。
- **Library Selection**(`feature/library`):媒体库勾选、保存、返回;走 `/api/v1/libraries` 与 `/api/v1/libraries/selection`。
- **Media Wall**(`feature/mediawall`):LazyVerticalGrid 封面网格 + 列数(封面大小)滑杆(2~5 列)+ 排序(名称/添加时间/大小/时长,再点切换升降序)+ 类型筛选(全部/视频/图片)+ 搜索(服务端 search)+ 加载更多分页;封面经 `MediaUrlResolver` 解析 MediaReview 认证代理地址后交给 Coil。
- 导航:connect → home → library / media_wall。
- 手动 IP 无端口自动补 `:8766`;installation ID 用稳定 UUID 而非服务器 IP。

## 阶段 8:工程骨架 + Server Discovery + Pairing

- **工程骨架**:`app/` 单模块,Compose + Material3 + Hilt + Retrofit + kotlinx-serialization + DataStore + Navigation Compose;依赖集中在 `gradle/libs.versions.toml`。
- **Server Discovery**:客户端 UDP 组播(`feature/connect/discovery/`)+ 服务器应答端(`server/app/services/discovery.py`,Stage 09 补齐)。
- **Pairing**:健康检查 → 配对码 → `/pairing/verify` 签发 token → `TokenProvider` + `AuthInterceptor` 附加 Bearer → DataStore 持久化并恢复;手机端不调用 `/pairing/code`。

## 目录约定(对齐 docs/ARCHITECTURE.md)

- `core/model` — API DTO / 统一响应包
- `core/network` — Retrofit API / AuthInterceptor / TokenProvider / ApiFactory
- `core/datastore` — 本地设置持久化(DataStore)
- `feature/{connect,home,library,mediawall}` — 连接配对、首页、媒体库、媒体墙

后续阶段按架构在 `feature/{player,review,favorites,deletequeue,duplicates}` 扩充,并加入 Media3、雪碧图、批阅等。
