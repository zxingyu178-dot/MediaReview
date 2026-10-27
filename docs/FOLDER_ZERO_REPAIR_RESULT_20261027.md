# 文件夹漏项修复与模拟器验收（2026-10-27）

## 修改与边界

Server目标1.6.1，Contract2/迁移0017保持不变；Alpha17/code24代码及APK不改。Jellyfin客户端新增同用户、所选库范围内的逐层分页迭代器，避免库级递归为0或部分漏项。当前用户Views校验库根；每页限制数量，目录与请求总量有上限，目录环路和跨目录条目按ID去重。单目录分页ID重叠、截断、总数变化、鉴权/上游失败立即失败；不能用管理员无用户查询替代。每页可检查取消，完整枚举后才复用原CAS事务提交旧项失效。

主要文件：`server/app/adapters/jellyfin/client.py`、`models.py`、`server/app/services/media_index.py`；回归复用`test_jellyfin_client.py`、`test_media_index_11.py`。版本声明同步至Server包、pyproject、部署version和既有builder，交付说明纠正旧版本口径。没有改Android UI、播放器、删除或数据库结构。

性能代价：由库级递归分页变成每目录至少一次串行HTTP请求；没有并行扫描或新增长期服务。真实库内部根因仍未证明，不擅自修改Jellyfin数据库/配置或真实媒体。

## 已执行验证

| 层次 | 结果与证据 |
| --- | --- |
| RED | 新增遍历用例7失败；聚焦复核发现“分页部分重叠”后补用例，先确认DID NOT RAISE，再修复 |
| 客户端/索引 | 最终45通过、0失败/错误/跳过，33.53s；`review_meta/ops-20261027/sync-final.xml` |
| 目录/API | 52通过、0失败/错误/跳过，356.16s；`api.xml`，这组包含既有10万行响应门禁，未重复执行 |
| 真实上游只读 | 四个已定位样本分别132/288/101/1866条，与对照一致；原用户、原选择库归属、ID不重复；未写生产索引 |
| 模拟器 | 现有MediaReview_Test/API35，独立临时数据盘；已交付Alpha17/code24实际安装。手动`10.0.2.2:18766`连接隔离1.6.1、配对成功；界面全局5项、原递归0库3项、实际目录直接1项及子目录2项，子目录视频/图片存在；图片实际渲染。`emulator-proof.json`及`shots/04*`、`05*`、`07*`、`08*`、`09*` |
| 视频限制 | 合成H.264请求/时长推进至3秒，但goldfish解码器出现Surface输出错误，截图未确认视频出帧；不计播放通过，不推断真实手机正常 |
| 静态检查 | 改动客户端/索引/两测试文件F,I检查通过，版本SSOT核对通过。models.py底部兼容再导出已有I001在HEAD也存在，本轮未顺手格式化；保留既有Starlette弃用提示 |

最终定向命令：

```text
python -m pytest tests/test_jellyfin_client.py tests/test_media_index_11.py -q --tb=short -o addopts= --basetemp=../review_meta/ops-20261027/pytest-sync-final --junitxml=../review_meta/ops-20261027/sync-final.xml
python -m pytest tests/test_folder_tree.py tests/test_media_api.py tests/test_jellyfin_api.py -q --tb=short -o addopts= --basetemp=../review_meta/ops-20261027/pytest-api --junitxml=../review_meta/ops-20261027/api.xml
python -m ruff check app/adapters/jellyfin/client.py app/services/media_index.py tests/test_jellyfin_client.py tests/test_media_index_11.py --select F,I
```

测试子进程TEMP/TMP使用项目可写目录。隔离服务首次未设置该临时目录，SQLite多行UPSERT报unable to open database file；仅修正测试进程环境后重新同步成功，无系统权限/环境变更。初次空配置返回422，按正常后台流程完成选库后继续。未运行全量Server/JVM/178 connected，未重建APK；本轮串行完成主要pytest集合后做模拟器操作，没有扩大回归范围。模拟器准备曾短暂与pytest重叠，已停止该模拟器，待测试完成后使用独立数据盘启动，不称全程无重叠。

临时服务只监听loopback，数据、媒体和配对均为可丢弃夹具；本轮未测试真实删除。uiautomator已停止、未残留ADB forward，模拟器及夹具进程已关闭，证据/可复用测试数据保留在ignored review_meta。

## 交付与下一步

源码修复提交`6a748765838b5331518c94ac53dbe906fac32677`。仅按现有PyInstaller spec增量构建Server到独立dist（未清历史输出），耗时约80秒；复用原Alpha17 APK，经既有builder的实际EXE health/签名/manifest与打包核验入口交付。实际EXE1.6.1/Contract2/14能力通过，ZIP的182项SHA256通过。包路径：`deploy_handoff/folder-fix-1.6.1/MediaReview_Server_1.6.1_stage9.zip`，134,336,115字节，SHA256 `b644616f20535b4a80fb432ecfd206866e3e8681c5d3c210e280c2b0966618c6`；EXE SHA256 `882d9b3653a597fa70ddf21d764826b1863b722a937d8f1acd9b0246a10a5784`。本机`delivery-proof.json`、`package.log`与包内manifest保留构建提交和输入指纹，后续文档提交不代表重建产物。原Alpha17哈希未变，未重复构建APK或发送邮件。

生产仍是1.5.2/windows_service，未升级或刷新，真实缺失目录尚未在手机闭环。升级后需要处理当前24库与2条历史库选择，再进行同步；不得为避免失败直接清库或丢弃收藏/批阅。手机不在场时，优先采用“相关逻辑回归→真实Jellyfin只读计数→隔离服务+模拟器UI”三层验证，Wi-Fi发现、硬解/seek及真实文件完整性留作实机验收。

Git网络：首次修复提交push出现GitHub443超时及RPC失败（虽然末尾有Everything up-to-date，退出码为1，不能视为成功）；最终状态须以实际push/remote核对为准。

模拟器使用虚拟网络，能经宿主机联网并用10.0.2.2访问宿主loopback，不能将其当作真实手机接入家庭Wi-Fi的等效验收：[Android官方网络地址说明](https://developer.android.com/studio/run/emulator-networking-address)。
