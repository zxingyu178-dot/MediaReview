# 双端一致性：源码链路核对

2026-11-10。只读检查现有写入和投影代码，不代表手机/生产联通验证，没有对真实图或提示词写入。

|链路|当前依据|结论与后续|
|---|---|---|
|桌面图片审核/收藏→手机|Studio image_service.set_review/set_favorite/set_image_status在commit前record_review_change；mobile_v2_service.feed按图片事件返回当前summary与revision|源码存在统一记录和事件路径，需隔离两端请求验证，不以静态阅读外推现场通过|
|手机整页审核→桌面|batch-review复用atomic_write，图片状态/事件/回执同事务；桌面图库从同一Image投影|不存在第二套手机审核库；尚需桌面读取与当前手机界面的组合验证|
|提示词评分记录|prompt_scoring_service.create_evaluation向PromptEvaluation写入，并同事务record_prompt_evaluated；移动端复用评分服务|共用评分记录，版本评分与整体评分是不同查询范围，不能把范围不同误判数据不一致|
|评分实时刷新|PROMPT_EVALUATED为image_id=None；mobile_v2_service.feed只选择图片事件且要求image_id非空，VM仅打开/操作面板时refreshPromptPanel|当前图片feed不能自动通知已打开面板有桌面新评分。后续对比页须做可见期/返回时评分校验或复用通用events，保留未提交打分草稿，明确远端已更新；不可声称已具备实时双向评分同步|

桌面旧batch_set_status逐项调用单图事务，可能部分完成；它不等同新mobile整页原子接口。这是既有桌面合同，当前不擅自改写；新手机回执只能表示自己的整页事务，不把桌面旧批次包装成原子成功。

继续按既定顺序：手机批量HTTP合同→9图状态/界面→同版本6/10图评分及上述刷新→分类/查询与两端隔离回归。新图不插入冻结页，图片被桌面修改时手机以revision冲突返回，不静默覆盖。

## 评分与搜索补齐的源码边界

手机评分面板现复用前台退避轮询核对promptSummary，也提供手动核对；只更新汇总，不改变草稿requestId、冻结载荷、图片页。面板重开/连接变化隔离旧回包，界面明确提示记录更新，未提交分数与备注由原requestId保持。2项针对用例已通过，但尚未验证真实桌面更新到手机及UI草稿保留，不能声称实机双端通过。

搜索/当前版本文字标签接口已在NSFW f12a202完成，使用SQL过滤后分页并绑定scope；手机参数/回显/能力门禁已接。图片feed不含Prompt正文/标签，不能在手机推断搜索归属；遇到未知时提示重新查询并使序号待校验。Prompt元数据变化本身是否触发图片feed尚未证明，当前以用户重新查询获取权威结果，不声称标签变化会即时自动重排冻结范围。
