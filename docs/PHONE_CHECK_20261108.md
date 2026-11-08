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

## 批阅错误被释放内核掩盖：源码修复（2026-11-08）

手机Media3日志确认运行1.10.1。核对该版本原始DataSpec.java第472行，为uriPositionOffset+position非负断言；ProgressiveMediaPeriod.buildDataSpec仅设置position，故实际失败为负字节位置，不能据此直接归咎缓存或通过强制截断位置修复。负位置的上游起因仍未确认。参考官方源码：https://github.com/androidx/media/tree/1.10.1 。

已从本机实际依赖gsyvideoplayer13.2.1字节码确认：GSYVideoControlView在Error(7)执行releaseMediaPlayer；IjkExo2MediaPlayer.reset清空mDataSource。Review原来的错误观察要求currentOwnedSourceHost非空，该函数强制kernel.dataSource==loadedUrl，于是当前源错误在释放后也被拒绝。另确认controller.snapshot是Compose State，因此不是snapshotFlow观察了普通StateFlow的错误。

修复只改批阅错误入口，不放宽播放/seek/暂停时的归属校验：保留当前媒体/URL/stage、attached owned host、manager listener；仅错误状态允许内核URL为null。不同内核URL/旧源/非当前listener仍拒绝。复用既有onPlaybackFailed一次回退状态机。

回归：ReviewErrorOwnershipTest原严格判定2项中1失败（当前源释放）、1通过；修复后该2项和V2PlaybackSourceControllerTest10项共12项通过，Gradle BUILD SUCCESSFUL（2m23s）。命令testDebugUnitTest --tests '*ReviewErrorOwnershipTest' --tests '*V2PlaybackSourceControllerTest'。首次wrapper锁/原生库加载失败不算RED；使用已登记Gradle8.9绝对入口和已有temp/gradle-home2/TEMP后取得有效RED（6m51s）。未改系统权限或环境，未跑全量或connected。

此修复仅源码，尚未构建新Release或安装手机；正式Alpha33未包含，未声称真实异常/黑屏已消失。手机保留数据，测试未安装会冲突的debug同包名版本。后续需在正式新包复核当前错误可提示/回退，并继续追查该样本seek负字节位置。

## 第253项负位置样本定位（2026-11-08）

只读生产索引与Jellyfin当前用户Item元数据一致：容器AVI、51644ms、883830字节。真实HTTP请求Range0-63返回206、video/x-msvideo、RIFF/AVI签名成立，排除把它按MOV/MP4原因处理。

进一步只读两个有界Range（头8192字节及尾131072字节），只在内存解析、未保存媒体：strh视频轨type=vids、scale125/rate2997、length=0；音频轨type=auds、length=1977。idx1声明/实际31632字节完整匹配，全部1977条都是01wb音频，无视频索引。没有下载整文件、转码、移动或改写媒体。

Media3 1.10.1官方ChunkReader.java：firstIndexChunkOffset初始化INDEX_UNSET(-1)，appendIndexChunk才赋实际位置；无索引时getSeekPoints返回firstIndexChunkOffset。AviExtractor.AviSeekMap选择各track更小的位置。因此视频轨无索引可将-1送入ProgressiveMediaPeriod，再触发手机实际DataSpec.java:472非负断言。此链与已观测数据相符，原视频轨零帧是已确认事实；未把结论推广到其他文件。

官方源码入口：https://github.com/androidx/media/blob/1.10.1/libraries/extractor/src/main/java/androidx/media3/extractor/avi/ChunkReader.java ，同目录AviExtractor.java。

据此修正验收边界：第253项此前进度前进只证明时间轴/音频推进，不能称视频出帧通过。它不能作为有效视频的seek验收样本。已修复的当前错误回传仍有必要，避免此类源异常静默黑屏；不修改用户文件、不强行钳制字节位置、不自动全量转码或升级播放器依赖。接下来以有效含视频帧的样本验证拖动，并在正式新APK复核错误可见/回退。
