# 前端适配与回归验证

首页模板：`src/main/resources/templates/index.html`。
聊天与历史会话：`src/main/resources/static/js/index.js`。
认领与归还：`src/main/resources/static/js/claims.js`。

## 本地回归

无需启动应用、数据库、Redis 或外部模型：

```powershell
mvn -o "-Dtest=*Tests,!LostFoundApplicationTests" test
node scripts/verify-frontend-auth.cjs
```

Maven 离线运行要求本机已经缓存项目依赖。浏览器脚本使用 Node.js 24 和无界面 Edge；可以通过 `EDGE_PATH` 指定浏览器。截图输出到 `target/frontend-auth/`。后端测试报告输出到 `target/surefire-reports/`。

`LostFoundApplicationTests` 会加载完整 Spring 环境，因此不包含在上述隔离回归中。这里的认领服务测试使用 Mapper 模拟对象，不代表真实数据库事务回滚、行锁竞争和并发请求已经验证。

## 页面操作

1. 登录后，空白对话在首条消息发送时才创建。创建请求提交 JSON 模式；聊天使用 POST JSON `{sessionId,message}`，响应按 UTF-8 流式读取。
2. 点击“历史对话”，选择服务端保存的会话；“加载更早消息”读取上一页。失败显示重试，不会当作空记录。旧会话需要先阅读记录并保存模式，保存后才能发送。模式冲突会重新读取服务端模式。
3. 点击“认领与归还”，在“申请认领”填写拾物编号、本人丢失登记编号和认领依据。目前后端没有提供可直接选择本人登记的 HTTP 列表接口，编号来自查询结果；页面不从模型自然语言中自动推断编号。
4. 拾得人在“我收到的”审核申请；同意后双方可按需读取对方联系方式。拾得人确认交接，申请人确认收到后完成归还。取消仅在待审核或已同意但未交接时显示。
5. 认领操作需要确认；请求失败或冲突时读取最新状态，不自动重发。联系方式不会写入浏览器存储，关闭弹窗时清除显示。

## 仍需真实环境验收

使用两个不同账号验证跨用户查询、申请、审核、交接、收货，以及第三个账号无权访问。验证数据库中两类物品状态与索引同步结果，并测试并发审核和事务失败回滚。

历史记录恢复属于页面恢复；当前模型记忆仍依赖内存存储，服务重启后恢复模型记忆需要单独实现。
