# Alpha8 真实手机 LAN 验收

当前 Real User LAN Playback: NOT TESTED。模拟器HTTP视频通过不等于真实Wi-Fi通过。
安装Alpha8与升级电脑端后，手机连家庭Wi-Fi，记录手机型号、Android版本、网络及时间。

1. 首次启动更新说明只弹一个Sheet，跨版本升级包含所有未读Alpha说明。
2. 设置→数据源→Server信息→检查手机播放链路：Contract2，配置的客户端地址，
   电脑Jellyfin可达、设备认证有效；手机有限Range GET为200/206且视频流可读。
   记录host:port、状态、字节数；不要记录Token、完整URL或原始异常。
3. 首页打开真实视频，记录tap_to_first_frame_ms（从点击到首帧）；检查暂停、恢复、拖动、
   +10/-10、全屏/退出、上一条/下一条和返回。连续切3条视频。
4. 批阅首条自动播放，连续上滑5条；检查首帧、无限Loading、串视频、残留音频与Poster。
   每条记录settle_to_first_frame_ms（滑动稳定到首帧）。
5. 收藏一条；加入待删除再撤销。允许Prepare，禁止Commit Permanent Delete。

普通首帧建议<2500ms，已预取批阅下一条建议<1500ms，这不是硬性SLA。
任一>5000ms记录并分层分析：手机/playback请求、设备Key校准、PlaybackInfo、resume、
手机/Jellyfin连接、GSY prepare和first frame。不要凭慢就重构播放器或永久缓存旧Key。

电脑readiness通过、手机直连失败时分别记录；检查实际LAN地址、Wi-Fi隔离与端口，
不要自动修改防火墙、不要把Server健康等同于手机能播。
只有真实手机两条播放路径通过，才填写真实LAN PASS；未测项保持NOT TESTED。
