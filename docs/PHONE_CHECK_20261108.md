# 2026-11-08 真机定向排查

- USB 手机已核对。原安装 Alpha30/code37；核验交付 APK SHA256 后 install -r 覆盖 Alpha33/code40，安装成功；没有清数据、卸载或重设配对。
- MediaReview Server 健康返回1.6.3/windows_service。手机媒体库多个视频封面显示HTTP422与00:00；只读数据库确认local_thumbnail任务失败281项（错误为“视频封面无法提取，仍可尝试直接播放”）、成功132项。源码处理器将提取失败包装为422，尚未确定具体FFmpeg失败原因；不把00:00单独判为视频不可播放。
- Studio/8877开始离线。通过官方ControlHub start启动Studio成功。手机重新检查已保存连接后，无需配对码进入生图图库（初显500且还有更多），点第2项进入2/1000审核页；信息页存在提示词、尺寸、时间、大小字段。此处仅证明导航和字段存在，尚未核验图片实际像素、字段值准确性或加载速度。
- 无审核/评分/删除决定；未改媒体、索引或生产Server。批阅播放/进度拖动、相册滚动、图库跨进程恢复本轮未完成。
- UI辅助会话结束，未新增ADB forward。后续优先针对FFmpeg封面提取错误做受控复现，保留文件路径/身份安全边界；其次补真机播放与相册验收。

## 批阅补验（2026-11-08）

- 手机Alpha33恢复到253/3987时显示HTTP500。只读确认JellyfinServer停止；经官方ControlHub start jellyfin返回succeeded/online后，同一项重试HTTP500消失、播放进度持续前进。MediaReview健康仍1.6.3，未升级Server。
- 进度条第一次右拖结束即时读数约0.7000；其后手机日志14:48:20出现Media3 Source error → UnexpectedLoaderException → IllegalArgumentException，栈定位DataSpec构造/ProgressiveMediaPeriod.ExtractingLoadable.buildDataSpec。画面后来黑屏，UI无失败提示，进度控件退为不可拖ProgressBar(0.0)。这不算拖动通过；时间先后尚不足以证明唯一触发原因，下一轮需要可重复seek样本及错误状态传播调查。
- 上滑从253到254，下一项进度有更新。按浏览规则已批252→253；未按保留/待删除/撤销、未提交评分/删除。未确认下一项实际像素出帧或全部视频稳定性。
- 当前真实封面请求日志显示local_path_rejected；故此前任务表281条历史“提取失败”不能解释此次所有422。FFmpeg尾索引修复是受控确认缺陷，但本地路径失效/安全拒绝仍需分别诊断，不能称一版修复覆盖全部失败。
- 本轮仅恢复既有Jellyfin后台，不改数据或来源配置。结束返回退出批阅并停止uiautomator，未添加ADB forward。私有截图仅供确认黑屏，不作为公开素材。
