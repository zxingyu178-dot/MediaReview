# 文件夹分类与排序实施计划

目标：实现用户已确认的直接目录分类及独立排序/类型筛选。架构沿用FolderPagingController、SortFilterSheet、Server1.6.3分页接口。技术栈Kotlin/Compose/JVM/instrumentation。不增加依赖、迁移或服务，不清手机数据；串行执行。

- [ ] 在既有FolderPagingControllerTest调整默认直接范围期望并验证RED；在既有FolderBrowserTouchTest加入混合媒体排序筛选用例，检查UI入口和实际查询。
- [ ] 修改FolderBrowserScreen/FolderPagingController/V2HomeViewModel：独立目录sortSpec、直接范围默认、单库根修正、分页/缓存隔离和正确媒体上下文。SortFilterSheet复用并允许隐藏来源选项，FolderTile澄清计数。
- [ ] 跑相关JVM与必要debug/test编译；隔离模拟器定向验证点击/排序/类型/递归与返回，不全量connected。
- [ ] 发布Alpha20/code27独立签名APK，验证同证书和SHA256；commit/push、更新CURRENT_RELEASE与状态/结果文档。可用手机只读抽样，不重建Server。

复核：每项对应用户目标，没有占位接口或新增架构；首页全部媒体墙仍保留，避免以改目录布局为由隐藏根内图片。若测试暴露目录链路其他缺陷则在同一范围修复，并记录限制。
