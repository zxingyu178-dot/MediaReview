# Stage 8D.3 播放链路与验证边界

## 变更目的

保持手机直连 Jellyfin：MediaReview Server 只返回播放地址与设备认证头，不代理视频。
Server 更新为 1.2.2，API Contract 保持 2；Android 更新为 code 14 / 2.0.0-alpha7。
本阶段不部署生产、不交付用户 APK、不进入 Stage 8E。

## 根因与证据

原实现永久信任数据库缓存的 Jellyfin 设备 Key。上游删除或轮换后，旧 Key 仍被返回。
现在每次播放按 installation_id 的固定名称向 Jellyfin 校准，缺失才创建，变更才更新本地。
管理凭据不作为设备凭据回退；上游失败关闭播放签发。同一进程中的共享客户端锁避免并发重复创建。

旧 HLS 地址把 item ID 当作 MediaSourceId。现在读取 Jellyfin PlaybackInfo 返回的真实
MediaSources[].Id；取不到真实源时不提供 HLS，Direct Stream 可独立使用。

本机生产端口 8766 的公开 health 只读检查返回 Server 1.1.0，未提供 api_contract。
它与本阶段要求的 1.2.2 / Contract 2 不一致。未访问其生产配置、设备凭据或媒体；
这证明生产版本落后，不能单独证明用户手机失败的全部原因。

隔离 Jellyfin 10.11.11 使用仓库小 MP4 验证：Direct GET Range 返回 206，1024 bytes，
Content-Range 为 bytes 0-1023/250790；真实 PlaybackInfo 确认媒体源 ID，HLS 返回有效清单；
撤销设备 Key 后能够重建。该实例使用独立数据目录和 127.0.0.1:18096，已停止。
这些证据不等于用户 LAN 或生产部署验收。

## 地址合同

- jellyfin.url：Server 到 Jellyfin 的地址。
- jellyfin.client_url：手机直连地址的权威配置，公开模板提供此字段及 client_host_allowlist。
- 未配置 client_url：沿用请求主机回退，保留 Jellyfin 端口和路径，明确标记非权威来源。
- client_url 含认证信息、无效 scheme/host 等会被拒绝；认证不放在 URL。
- Direct / HLS 都使用同一设备认证头，不携带 MediaReview Bearer。

部署说明提供局域网地址示例及只读端口检查；本阶段不修改防火墙、代理或服务端口。

## 认证 readiness

新增 GET /api/v1/system/playback-readiness，必须经过现有设备认证，包括回环请求。
可指定 media_id；否则从索引选择一个可用视频。字段包括 jellyfin_server_reachable、
client_stream_base/source/authoritative、client_address_status、device_playback_key_status、
media_id/media_valid、direct_stream 和 phone_reachability。最后一项固定为 not_tested。

Server 使用设备 Key 请求 Jellyfin Direct，Range bytes=0-1023，禁用管理 Authorization，
不跟随重定向，最多读取 1024 bytes。200 必须有合法长度；206 必须是从 0 开始、
不超过 1023、结束位置小于总长度的 Content-Range，长度与实际读取一致。
内容类型采用白名单，不反射上游任意 header 或异常文本。readable 同时证明媒体存在与设备 Key 有效。

## 手机诊断

SERVER 数据源设置页增加独立诊断区域。先恢复连接并检查公开 health / Contract / 配对门禁，
再请求 readiness、VIDEO playback，使用生产 V2PlaybackResolver 得到同一 URL 与 headers。
独立 OkHttp 执行有限 Range GET，不跟随重定向、不携带业务 Bearer，验证类型、Range、长度及字节数。
区分未配置、未配对、不兼容、Server 不可达、401/403、404、上游错误、网络错误、非视频及可读流。

Server 检查与手机检查使用不同阶段标签，禁止把 Server 可达写成手机可达。
切换连接或退出设置页取消旧任务，OkHttp call 同时取消；旧结果不覆盖新会话。
显示受控中文标签与 host:port，不显示 URL 路径、认证值或异常原文。

## 实际播放验收方式

androidTest 的 MockWebServer 提供真实 MP4 字节与 Range 响应，认证值随机生成且不打印。
生产 Repository / Resolver / ViewModel / GSY 页面发起视频请求。断言 duration > 0、isPlaying、
position > 500ms 并继续前进，避免把 Source Ready 当作真正起播。
批阅测试通过生产 VerticalPager 上滑至第二项，核对内核 source 与新的 endpoint，再验证持续播放。
Demo 完整播放器与 Demo 批阅另作起播回归，Demo 实现和 GSY 公共播放器没有修改。

## 运维与剩余边界

沿用已有 Registry Service 身份和 ControlHub binding。没有新增服务或生命周期入口；
已安装生产对象未升级，本阶段候选源码不能冒充已部署版本。
用户真实 LAN、用户手机、生产新版本与防火墙条件均未验收。
Jellyfin 命名 API Key 不是细粒度媒体权限隔离，本阶段沿用现有设备凭据协议；
不能把设备名独立误称为 Jellyfin 服务端权限隔离。
测试日志记录所有初次失败与修复后结果；以最终交接包的实际测试报告作为 Gate 依据。
