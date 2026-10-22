# 用户注册接口

`POST /api/auth/register`，无需 Authorization，Content-Type 为 application/json。

```json
{
  "username": "zhangsan",
  "password": "Example123!",
  "realName": "张三",
  "phone": "13800000000"
}
```

成功返回 HTTP 201：

```json
{
  "userId": 7,
  "username": "zhangsan",
  "realName": "张三",
  "phone": "13800000000",
  "role": "USER"
}
```

注册不自动登录。随后调用 `POST /api/auth/login` 获取 JWT，沿用现有登录链路。

- username：3–32 位，只能包含英文字母、数字、`.`、`_` 和 `-`。
- password：至少 8 个字符，UTF-8 编码最多 72 字节；前后空格保留，使用 BCrypt 哈希保存。
- realName：必填，最多 50 个字符，去除前后空白。
- phone：必填，允许常见空格、括号和连字符；保存前去除格式字符，最终为可选 `+` 与 7–15 位数字。
- role 固定 USER，status 固定 ACTIVE，客户端不能自选角色、状态或主键。
- 注册和登录共用现有 Redis IP 请求限流。Redis 不可用时返回 503。
- 400：请求字段不合法；409：账号或已有唯一约束字段冲突；429：请求过于频繁。

## 数据库前提

并发同名注册必须由 `sys_user.username` 唯一索引兜底。先检查实际数据库；若已有唯一索引无需添加。
检查和新增索引的参考语句见 `sql/registration-username-unique.sql`，该文件不会自动执行。
索引新增前先处理已有重复用户名，并核对实际字段长度能够容纳上述请求上限及 BCrypt 哈希。

联系人姓名和电话是用户自填资料，本接口不执行短信验证。前端注册页面尚未接入。

## 验证范围

`AuthRegistrationTests` 验证密码哈希、普通用户角色、重复冲突、字节长度限制、限流和注册后登录。
`AuthRegistrationControllerTests` 使用实际 MVC 拦截器配置验证匿名注册、请求校验与受保护路由。
测试使用模拟数据库和 Redis，不证明实际数据库已经建立唯一索引。
