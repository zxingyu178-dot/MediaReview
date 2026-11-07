# 生图审核大字体工具栏修复

用户已确认：序号单行，工具项空间不足时自动换行，普通字号保持紧凑，不改变审核逻辑。

1. 根因与RED证据复用Alpha32在Android35/200%字体的实际截图：底部Row固定一行，序号weight(1f)拿到很小宽度，1/65逐字符换行并抬高工具栏。证据见LARGE_FONT_CHECK_20261107.md及review_meta/alpha32-a11y/review.png。
2. 仅NsfwReviewScreen.kt：底部工具栏改FlowRow，移除序号weight，显式单行且禁止软换行，保留上一张/图片信息/下一张/更多的原顺序、条件、回调与菜单。
3. 增量assembleDebug确认编译；临时AVD上安装调试包，对照同宽度/200%字体与普通字体。检查序号不竖排、工具可见/可点、导航和信息入口仍工作；不提交审核/评分。无新测试框架或新服务。
4. 记录真实验证边界、commit/push。正式指针仍Alpha32；未取得最终APK证据前不称已发版修复，后续与下一批统一发布。


## 2026-11-07 实施与验证

只修改NsfwReviewScreen.kt底部Row为FlowRow、序号禁止软换行/单行并去掉weight；原按钮与菜单回调保持。assembleDebug一次成功（10m43s、42任务/8执行34复用），日志review_meta/nsfw-toolbar-debug.log。调试APK SHA256 c6c31ff46e5b6e33bc35af66411bc43c24e7f4c369ba5ffe87d3bdf250cb66db，仅安装临时Android35/Pixel2，未覆盖手机或正式包。

200%字体截图font200.png已查看：1/65横排，工具栏自然两行，图区比RED截图扩大；翻页到2/65、回1/65、图片信息弹窗及关闭通过。更多按钮已定位并执行点击，但菜单展开断言与底部同名收藏冲突，不能据此宣称菜单内容已独立验收；菜单回调未修改。100%字体截图font100.png已查看，全部工具项一行；该截图图片仍在加载，只证明工具栏布局。一次1秒早断言失败后重读已显示2/65，改有界状态等待；重开后继续上次早点击未离开图库，等待就绪后点当前缩略图进入，未算继续入口本轮通过。

证据review_meta/nsfw-toolbar-ui/{font200.png,font100.png,result.json}；本轮只有截图/实际操作及编译，不虚报JVM/connected测试数或全量验收。临时夹具审核/评分POST累计0。uiautomator与会话退出，按身份停止夹具和模拟器并用SDK删除临时AVD，原AVD/真实媒体未动。正式Alpha32仍不含修复，CURRENT_RELEASE未变；源码待下一批统一发布，手机/HomeLAN未测。

## Alpha33独立正式产物

构建基线1b09e22c4451f49aa55a76bdc929cfdd530fdb80已push并核对两分支一致；版本Alpha33/code40。17项ReleaseNotesContractTest通过，3项版本/签名入口pytest通过（21排除、1既有warning）；一次Release构建12m59s（86任务、38执行/48复用）。原发布证书592c…3564、406项源码输入无漂移、演示媒体0；工具栏Git blob与54527c5一致，明确复用上文调试UI证据，没有重跑同组UI或虚报正式APK/手机验收。

独立APK20,862,776字节，SHA256 311450c6903a6cf383cf6b606a531e93160a83783df829601e41017b531b4bcf；复制哈希匹配，CURRENT_RELEASE已备份并更新Alpha33，原Alpha32与Server1.6.5原ZIP未改。证据review_meta/alpha33-{release.log,build-inputs.json,build-baseline.json,artifact-proof.json}。正式Release UI未执行，phone/HomeLAN未测，不称PRODUCTION_READY。NSFW元数据修复已单独部署5153cfe，MediaReview电脑Server本次只读health为1.6.3/ok/windows_service，无升级或EXE构建。
