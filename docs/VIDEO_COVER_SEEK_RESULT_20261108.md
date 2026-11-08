# 本地视频封面尾部索引修复（2026-11-08）

真机Alpha33显示本地视频封面422；生产任务表只读计数失败281、成功132。不能据此断言所有失败同因，也不能据00:00断言视频不可播放。

使用隔离合成MOV（320×240、15fps、3秒、248186字节，索引在尾部），同一只读句柄输入：pipe:0提取失败且输出0字节；-fd 0 -i fd:成功输出13146字节JPEG。FFmpeg官方协议文档确认fd支持普通文件seek、pipe不保留该能力：https://ffmpeg.org/ffmpeg-protocols.html#fd 。

生产修改仅native_images.py输入协议：仍继承经过路径/身份验证的只读stdin句柄，不按路径重开；保留15秒超时、单张/尺寸/输出大小限制。

既有test_native_images.py视频样本参数化faststart/tail-index；通过MEDIAREVIEW_TEST_FFMPEG_DIR指定已登记部署工具，未安装重复工具。生成较大合成视频，避免小样本被缓冲完整掩盖seek问题。

验证命令：在server目录，设置测试FFmpeg目录后执行 python -m pytest tests/test_native_images.py 。先单跑video_cover：1失败（tail-index）/1通过；修复后整个文件6通过，140.26秒，1既有Starlette弃用警告。测试均使用临时来源，无真实媒体操作。

源码修复尚未打包或部署，生产Server仍1.6.3。未称281项失败全部解决，未重复全量测试、未构建Android。下一步核对打包FFmpeg协议能力及部署后真机封面恢复，仍需区分不支持编码、损坏文件、超时等其他错误。
