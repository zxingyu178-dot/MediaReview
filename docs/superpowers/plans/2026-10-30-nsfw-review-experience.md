# 生图审核体验实施计划

用户已批准连续审核方案。串行执行，不增加服务或测试框架。

- [ ] 状态流转：在NsfwReviewViewModelTest先补成功前进、失败停留、迟到回包不抢焦点；观察失败后修改NsfwReviewViewModel。复用当前幂等和revision检查。
- [ ] 撤销及新图：为最近审核记录原状态和回执水位，冲突不覆盖；新图提示点击后清零，缩略图跟随选择。补最小对应用例。
- [ ] 页面：NsfwReviewScreen分开主要/次要动作，详情加载与结果反馈明确，不加入审核手势。
- [ ] 验证和交付：只跑相关JVM与必要Release编译，更新DEV_LOG/PROJECT_STATUS/交付指针，commit/push。手机不可用，如实标记。
