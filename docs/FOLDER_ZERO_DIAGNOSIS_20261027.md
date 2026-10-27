# 文件夹数量为0：接替诊断与最小修复方案（2026-10-27）

## 当前事实

- 本地分支 `feature/mediareview-local-only`，调查基线 `1247ee4`，调查前工作树干净。`git fetch origin` 因 GitHub 443 连接超时失败；不能以本地 origin 缓存声称远端已核对。
- Alpha17/code24 源码已完成 UI P1–P4；APK 实存43,655,762字节。用户确认手机已安装 Alpha17，本次 ADB 无设备，未独立核验手机。
- 源码 Server1.6.0/Contract2/迁移0017；实际生产仍为1.5.2/Contract2/0016，`AIHome.MediaReview` Running，health 为 `windows_service / AIHome.MediaReview`，数据库正常。现有1.6.0包未部署。
- Jellyfin实际10.11.11，24个当前库，扫描任务Idle，最近结果Completed，各登记根存在。本次只读取API元数据及MediaReview数据库，没有扫描或打开媒体文件内容。

## 已定位的两层问题

**一、Jellyfin库级递归查询漏项。** 使用生产绑定的同一用户、同一API认证、同样可播放类型集合，库级 `Recursive=true` 与逐层查询结果不一致：

| 样本 | 用户库级递归 | 同用户直接条目+子文件夹递归 | 不带UserId的管理员计数（仅诊断） |
| --- | ---: | ---: | ---: |
| A | 0 | 132 | 132 |
| B | 0 | 288 | 288 |
| C | 52 | 101 | 101 |
| D | 1664 | 1866 | 1866 |

样本A同用户直接返回的媒体，按其实际ParentId递归同样返回132条，抽样首条ID一致。以明确ID读取该条目也成功。没有发现当前用户的分级、未分级、库选择或标签屏蔽。

因此，历史§19/§22以“1个文件夹条目”推断“大视频未入库/探测超时导致缺失”的结论不足：这些视频至少在API内存在，同用户也能从直接父级枚举。Jellyfin内部究竟是祖先/顶级归属残留、分组还是缓存缺陷，尚未通过其内部数据证明；不直接修改Jellyfin数据库，不把推断写成已确认根因。

**二、MediaReview缓存未跟随整理刷新。** 最近media_refresh完成于10月22日，早于最新库整理。多个上游已非空的库在MediaReview缓存仍为0；本地26条库选择包含2条不在当前24库中的历史库，upsert只添加/更新、不处理缺席库。不能直接执行现有全库刷新就声称修好：上游漏项未解决，旧任务仍可能把漏项结果当作成功。

## 建议的最小修复范围（待用户确认）

优先修复现有Jellyfin链路：在后台同步中保留相同用户上下文，对当前库做分页的直接子级枚举，按真实子文件夹继续查询；条目按稳定ID去重，保留原选择库归属。需处理多页、循环/重复文件夹、取消、上游失败，枚举未完成不得执行“未见条目失效”。不能只在递归结果为0时兜底，因为样本C/D属于非零漏项；也不能取消UserId用管理员全库结果替代用户结果。

改动目标为Jellyfin adapter与media_index同步入口及少量关联回归；不改播放器、删除、安全鉴权、Android UI或数据库结构。先用隔离库回归分页、非零漏项、跨库归属、失败保留，再对四个已定位样本做只读对照。处理历史已不存在库时保留媒体与用户状态，不做清库或无条件自动取消选择。

备选：直接使用已开发的原生本地源，可避免Jellyfin目录关系，但会改变当前来源配置和展示身份；本轮不擅自切换。单纯重扫Jellyfin可能重建关系，但当前Completed扫描已经不能保证正确，不把反复重扫当作修复。

生产部署须在修复包形成、验证后单独确认；此前1.5.2授权不延伸到1.6.x。当前只读调查未改生产配置、索引、用户权限、库选择或服务生命周期。

## 验证与后续

- 已执行：实际health/SCM、ADB、SQLite只读查询；Jellyfin用户策略、Views、VirtualFolders、ScheduledTasks及四库多种查询对照。原始本机证据：`review_meta/ops-20261027/folder-query-comparison.json`，不包含API密钥或媒体路径。
- 未执行：代码测试、APK/EXE构建、生产刷新/升级、手机/LAN验证。本轮尚未改生产代码，不重复运行历史全量测试。
- 参考上游源码：[ItemsController](https://github.com/jellyfin/jellyfin/blob/v10.11.11/Jellyfin.Api/Controllers/ItemsController.cs)、[Folder](https://github.com/jellyfin/jellyfin/blob/v10.11.11/MediaBrowser.Controller/Entities/Folder.cs)。用户上下文与递归/直接查询是不同执行分支；上游源码只能解释调查入口，不能替代本机故障证据。
