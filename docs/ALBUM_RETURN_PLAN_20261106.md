# 相册返回缓存优化

用户已确认短时间返回复用窗口与位置，过期后台校验，保留显式失效及续页恢复。沿用当前隔离工作树，不修改Server或NSFW。

1. 在AlbumPagingControllerTest增加短时返回不查询且能继续下一页的回归，先确认失败。
2. AlbumPagingController使用30秒缓存有效期，按窗口最近完整校验时刻计算；滚动保存与关闭不续期，追加单页也不能替旧页续期。过期仍按原策略原子校验全部已加载页，失败保留窗口。
3. AlbumScreen的续页观察纳入refreshing，后台校验结束即重新判断可见尾项。保留失败重试保护、来源代次与取消保护。
4. 补时间边界/滚动不续期回归；已有要求立即SWR的测试显式配置有效期0。运行AlbumPagingControllerTest、WindowCachePolicyTest和AlbumGridPolicyTest，记录结果并commit/push。本批不为内部逻辑单独重建APK，随下一批统一交付；真机与大相册性能仍需验收。


## 2026-11-07 验证与交付结果

复用69caff7的Alpha32/code39签名APK，未重建。临时Android35模拟器+独立Server1.6.5夹具、120张纯色合成照片验证：进入相册首请求page1，滚至首个可见照片4；退出再进仍照片4，分页请求保持1次。缓存过期后夹具每页延迟8秒，在后台重查page1期间滚到尾部；不再操作屏幕，page1完成后自动请求page2，随后可见49、50、51及之后条目。请求序列为[1,1,2]，支持这次返回和校验后续页链路；不是全库性能或真实网络验收。截图已查看，纯色合成图片显示正常。

候选安装成功，更新说明版本正确；一次控件服务异常中断导航，重新读取相册列表后继续，未作为App失败或成功依据。第一次健康探测误用/api/v1/health得到404，更正为/api/v1/system/health后1.6.5/ok；仅隔离实例，不涉及生产。证据review_meta/alpha32-ui/result.json、album-paged.png及夹具日志。uiautomator已停止、会话退出，核对身份后停止临时夹具及模拟器；SDK官方删除临时AVD成功，原AVD未动。

唯一交付入口已更新独立Alpha32，20,862,780字节，SHA256 a3ef7ae64e5ff445f574c4ed40e9f421d11ec376eaca8c60240ee89570d10ccf；旧指针已备份。17项版本合同与3项入口、先前24项相册/81项生图回归按各自范围复用，不称单轮全部通过。手机/HomeLAN未测，生产Server未升级，冻结Server1.6.5 ZIP仍内置Alpha27。未发邮件、未改NSFW源码、未操作真实媒体。
