# 从零开发全流程

## 阶段 0：仓库初始化

目标：

- 初始化 Git
- 建立 server / android / docs / deployment
- 配置 lint / test
- 建立 `.gitignore`
- 建立基础 CI（如可用）

验收：

- Server 空项目可启动
- Android 空项目可构建
- 文档路径完整

## 阶段 1：Server 基础框架

完成：

- FastAPI
- config
- logging
- request_id
- health
- SQLite
- Alembic
- OpenAPI
- 统一错误结构

验收：

- `/api/v1/system/health`
- 数据库自动初始化
- 错误结构统一

## 阶段 2：Jellyfin Adapter

完成：

- Jellyfin 配置
- 测试连接
- 获取用户
- 获取 Libraries
- 获取 Media
- 获取元信息
- 生成直接播放信息
- 进度上报

禁止：

- Android 直接依赖 Jellyfin 原始 JSON 结构

验收：

- Server 可以稳定返回统一 Media DTO

## 阶段 3：Library Selection + Media Index

完成：

- 多库勾选
- 保存 selection
- 媒体分页
- 排序
- 筛选
- 图片/视频类型
- media_id 映射

## 阶段 4：缓存与雪碧图

完成：

- cache path manager
- ffprobe
- FFmpeg sprite
- manifest
- 后台任务
- 缓存命中
- 缓存失效
- 缓存清理

重点：

- 不污染源目录
- 不阻塞媒体列表接口

## 阶段 5：Review Engine

完成：

- review session
- session queue
- session_seen
- resume
- filter snapshot
- sort snapshot
- 当前 index

## 阶段 6：Favorites + Delete Queue

完成：

- 喜欢
- 取消喜欢
- 待删除
- 撤销
- 待删除空间统计
- commit
- audit

## 阶段 7：Duplicate Detection

完成：

- size/duration candidate
- quick hash
- exact groups
- optional full hash confirmation
- suspected model placeholder
- task progress

## 阶段 8：Android 基础

完成：

- Compose
- Hilt
- Retrofit
- DataStore
- Navigation
- server profile
- 配对
- 自动发现
- 手动 IP

## 阶段 9：Android 媒体墙

完成：

- 首页
- 多库
- media grid
- 2/3/4/5 列
- 排序
- 筛选
- 图片
- 视频
- 搜索
- 加载状态
- 错误状态
- 空状态

## 阶段 10：雪碧图交互

完成：

- 长按
- 横向 scrub
- sprite manifest 解析
- 高效裁剪
- 松手复位
- 缓存

## 阶段 11：普通播放器

完成：

- Media3
- 直接 Jellyfin stream
- 播放控制
- seek
- 字幕
- 音轨
- 倍速
- 比例
- 音量
- 亮度
- 锁定
- 横竖屏
- 进度同步

## 阶段 12：批阅模式

完成：

- 垂直 Pager
- 视频/图片混合
- P0/P1 预加载
- 页面吸附后播放
- 横屏视频居中
- 点赞动画
- 删除按钮
- 撤销 snackbar
- session resume
- 统计

## 阶段 13：喜欢 / 待删除 / 重复页面

完成：

- 喜欢媒体墙
- 待删除列表
- 总空间
- 恢复
- commit
- exact duplicate
- suspected duplicate UI

## 阶段 14：Web 管理后台

页面：

- Dashboard
- Jellyfin 状态
- 已选媒体库
- 媒体数量
- 缓存占用
- 雪碧图任务
- 重复扫描
- 后台任务
- 日志
- 重扫
- 清缓存
- 诊断导出

管理后台使用同一 API，不复制业务逻辑。

## 阶段 15：稳定性

必须覆盖：

- Jellyfin 不在线
- Wi-Fi 切换
- Server 重启
- App 前后台切换
- FFmpeg 失败
- 文件在扫描后被删除
- Jellyfin Item 失效
- 删除失败
- 无写权限
- 大媒体库
- 4K 高码率
- 图片超大尺寸
- 缓存满
- SQLite 迁移失败
- 配置损坏

## 阶段 16：部署

Server 最终交付：

```text
MediaReview-Server-x.y.z.zip
  MediaReviewServer.exe
  ffmpeg/
  web/
  config/
  scripts/
    install.ps1
    uninstall.ps1
    repair.ps1
    diagnose.ps1
  HANDOVER.md
  VERSION
```

部署脚本自动：

- 检测管理员权限
- 创建 `%ProgramData%\MediaReview`
- 初始化配置
- 检测 Jellyfin 8096
- 检测端口 8765
- 配置防火墙
- 注册开机自启/Windows 服务
- 启动 Server
- 打开 admin
- 输出部署结果

## 阶段 17：Release

产物：

- Android APK
- Server ZIP
- 完整 CHANGELOG
- HANDOVER
- 配置备份说明
- 回滚说明
- 验收报告
