# Alpha39实装准备与配套后端部署

## 手机重连后实际结果（覆盖下方安装失败阶段）

手机重连后对原同一APK重新核对SHA256一致，adb install -r成功，实际包版本Alpha39/code46，原配对和数据保留。未新建包或改业务代码，正式Alpha34指针不提升。

定向只读真机验证：

- 筛选菜单显示只看未审核、隐藏已淘汰、已保留、已淘汰等选项。
- 只看未审核实际请求status=NEW并200；首图ID与桌面API按NEW/最新查询首图一致，可见12个缩略图状态均待审核，无已保留/已淘汰。
- 隐藏已淘汰实际请求status=NOT_REJECTED并200，可见项无淘汰。加载更多发出的下一页仍含NOT_REJECTED和page_cursor并200；不据可见子集宣称逐张全库检查。
- 停止并重开本App后，页面稳定后显示隐藏已淘汰，实际恢复相同查询，原配对可用。初次20秒等待及紧接点击的读取曾得到false，随后稳定页面/查询均证实恢复；启动就绪慢的原因未定，不声称启动性能通过。
- 最后清除筛选，实际请求不带status且200；重新打开面板确认全部状态，退出面板，恢复测试前偏好。

控件原生click在本次设备有效；ADB坐标点按及部分未等待的控件读取未生效/失配，均没有计作通过。未做真实审核、撤销、评分、收藏、删除写入，审核后成员变更及撤销仍只有此前隔离14项证据，不外推组合实机验收。未跑无关全套测试。

结束时停止uiautomator并关闭会话，forward最终核对见本轮工具回执。后端继续运行；该结论只覆盖上述只读筛选/分页/恢复范围。

## 当前结论

用户已明确授权提交、合入PR39和部署配套后端。后端已实际部署健康；Alpha39已构建校验并保存，但执行adb install时手机断开，安装失败（device not found），没有升级成功。最终ADB列表/forward均为空，临时uiautomator会话已退出。未真实审核、评分、收藏或删除。

## 手机候选

- 2.0.0-alpha39/code46，源码cc2fd65，已push；Release构建10m16s成功，版本合同17项通过，288项输入前后相同。
- APK SHA256：`013f383e5546b93e87cfbb6fea6a908d65d66146a3a9c590b7715afe1d5ff309`。
- 原证书SHA256：`592c25955929ca7fa968f4450452a45919b9d8da6d91e8ca7274c5dd65973564`，实际签名与版本核验通过。
- 两个MediaReview工作区deploy_handoff/MediaReview_2.0.0-alpha39_stage9.apk均复制后重新比对一致；旧包与正式Alpha34指针保留。
- 日志temp/alpha39-build-20261112.log，收据temp/alpha39-proof-20261112.json。安装前手机实际为Alpha38/code45；后续须重新核验，不据此永久推定。

## 后端发布

本轮只提交手机图库4个源/测试文件和结果文档，99fcc07，保留开发工作树中未提交的电脑前端改版。PR39经只读代码复查无Critical/Important，提交CI43194034065成功后合入develop为26eed560b9f158f9741390936e41a25efdffdf8d。develop CI43194741090成功后同提交快进main，main CI43195362694成功，两主分支一致。

主线前端重新构建成功158模块，既有deploy_app_dist入口部署4文件，check_app_dist只读4项通过。日用HEAD26eed56。官方ControlHub stop首次返回adapter_failed，随后残留桥正常退出，官方status确认offline且8000/8877空闲后才部署；未强杀或绕过入口。官方start成功online，Studio健康，桥0.13.0实际声明mobile_gallery_filters_v1和mobile_gallery_review_filter_v1。未启动ComfyUI。

部署前queue前后均running0/queued0/paused0。既有SQLite backup API快照quick_check=ok，SHA256 `97bc7418439df7bc2120b155723e4a4635605145ccce02bf7062a3d87420fb2c`。Studio主工作区temp/mobile-filter-preflight-20261112-174653/manifest.json保存清单，本次实际保护6文件（指定3个加v3全部3个），复制后/部署前/部署后/启动后哈希均一致。备份不与旧WAL/SHM混用；恢复须先停止日用实例。

GitHub插件修改PR返回403，改用本机已登记GitHub CLI完成已获授权的操作；不是审批拒绝。日用实例新出现的Civitai文档/脚本未触碰。

## 下一步

手机重新连接后，核验设备及包版本，对上述同一APK执行install -r，保留配对与数据。只读验证筛选入口、NEW和NOT_REJECTED请求/结果、总数分页及恢复，最后恢复用户偏好。不用真实图片验证审核/撤销写入；这些行为此前仅隔离定向测试通过。手机筛选、布局和长期稳定性尚未验收。
