# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# 编译整个平台
mvn clean compile

# 安装到本地仓库（供模块间引用）
mvn install -DskipTests

# 运行（需要 MySQL localhost:3306/mydb）
mvn spring-boot:run -pl techfin-controller

# 仅编译特定模块
mvn compile -pl techfin-service -am
```

## Tech Stack

| 技术 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.11 |
| MyBatis-Plus | 3.5.16 (取代 JPA) |
| MySQL | 8.0+ (InnoDB, utf8mb4) |
| Lombok | 项目标配 |
| Jackson | 默认 camelCase（仅 queryData 响应例外用 @JsonNaming(SnakeCaseStrategy.class)） |

## Module Dependency Chain

```
techfin-controller ──> techfin-service ──> techfin-dao ──> techfin-model
         │                                 │
         └──> techfin-common               └──> techfin-common (via service)
```

五个 Maven 模块，均为 com.ccb 的子模块，聚合在父 POM `techfin-platform-ai` 下。

## Package Map

| 模块 | 基包 | 职责 |
|------|------|------|
| `techfin-common` | `com.ccb.techfin.common` | `result/Result`、`exception/BusinessException`、`RestExceptionHandler` |
| | `com.ccb.techfin.common.enums` | 通用枚举（`RoleEnum`） |
| `techfin-model` | `com.ccb.techfin.model.sxd` | SXD 模块 Entity、DTO、Enum |
| | `com.ccb.techfin.model.entity` | 跨模块共享 Entity（`MspDept`、`MspRole`、`MspUser`） |
| `techfin-dao` | `com.ccb.techfin.dao.sxd` | MyBatis-Plus Mapper（`extends BaseMapper<T>`） |
| `techfin-service` | `com.ccb.techfin.service.sxd` | Service 接口+实现、Config、Validator |
| `techfin-controller` | `com.ccb.techfin` | REST Controller + `CcbServerApplication` 启动类 |

## Key Conventions

### 1. 统一响应 — `Result<T>`

```java
Result.success(data);                    // code=0, msg="成功"
Result.success("操作成功", data);         // code=0
Result.fail(-1, "错误信息");             // 业务异常
```

- `code=0` 成功，`code=-1` 错误（业务异常统一返回 -1，前端按 code 判断）

### 2. 异常体系

- **`BusinessException(code, message)`** — 业务层抛出，`RestExceptionHandler` 捕获返回 `400`。`code` 是 String 类型业务码（如 `PARAM_MISSING`、`ATTACH_NOT_FOUND`）
- **`FileValidationException`** — 继承自 `BusinessException`，文件校验专用
- 全局异常处理统一返回 `Result.fail(-1, e.getMessage())`

### 3. Jackson 命名策略

全局未配置 `spring.jackson.property-naming-strategy`，采用 Jackson 默认策略，**Java 字段名（camelCase）即 JSON 字段名**：

- **前端请求 / 对外请求 / 响应**：均使用 camelCase（如 `pendingDocNames`、`creditCode`、`docId`），无需额外标注
- **外部 queryData 响应例外**：`POST /api/extract/open/doc/queryData` 返回的 `data` 字段为 snake_case（如 `company_profile_text`、`current_amount`、`item_standard`）。接收该响应的 DTO（`BpExtractRecord`、`FinanceRecord`、`AuditReportItem`）必须显式标注 `@JsonNaming(SnakeCaseStrategy.class)` 以匹配
- **MyBatis-Plus 映射不受影响**：实体类 DB 字段映射走 `@TableField` 注解，与 Jackson 命名策略独立

### 4. MyBatis-Plus 模式

- 实体类用 `@TableName`、`@TableId`、`@TableField` 注解
- `@TableId(type = IdType.AUTO)` 自增主键，`@TableId(type = IdType.INPUT)` 手工赋值主键
- `@TableField(fill = FieldFill.INSERT)` / `FieldFill.INSERT_UPDATE` 配合 `MyMetaObjectHandler` 实现 `createdAt` / `updatedAt` 自动填充，无需在业务代码中手动 set 时间
- 枚举实现 `IEnum<String>`，`getValue()` 返回 `name()`，数据库存枚举常量名
- Mapper 接口 `@Mapper` + `extends BaseMapper<T>`，无自定义方法时为空接口
- 动态查询用 `LambdaQueryWrapper<T>`（如 `new LambdaQueryWrapper<SxdAtt>().eq(...)`）
- 删除用 `mapper.delete(new LambdaQueryWrapper<>()...eq(...))`
- 无需 `@EntityScan`/`@MapperScan`，`@SpringBootApplication(scanBasePackages = "com.ccb.techfin")` 扫描所有模块
- **MSP 表（`msp_user`、`msp_role`、`msp_dept`）查询时须加 `is_deleted = 0` 条件**：自定义 `@Select` 方法显式加过滤，或使用 `LambdaQueryWrapper.eq(MspDept::getIsDeleted, 0)`，不可直接调 BaseMapper 的 `selectById()`

### 5. 事务管理

所有写操作 Service 方法加 `@Transactional(rollbackFor = Exception.class)`：
- `uploadFile()` — 上传文件 + 写入 kjjr_ai_sxd_att
- `submitMaterials()` — 创建申请记录 + 批量新增 + 写入 kjjr_ai_sxd_doc + 清理 kjjr_ai_sxd_att
- `confirmControllerName()` — 更新 kjjr_ai_sxd_record
- `deleteAttachment()` — 删除 kjjr_ai_sxd_att 记录
- `getCustOwnership()` — 更新 kjjr_ai_sxd_record.has_ownership

### 6. 外部 API 调用模式

通过 `RestTemplate` 调用外部接口，响应统一用 `ExternalResponse` 包装：

```java
// 通用响应类
ExternalResponse { boolean success; String code; String message; Object data; }
// data 转换
respBody.getDataAs(DocBatchAddData.class);
```

关键校验步骤：`respBody == null` → 抛异常 → `!respBody.isSuccess()` → 抛异常（对外部 API data 可能还需要 `respBody.getData() == null` 判断）。

### 7. 前端 Token 鉴权

所有 `/techfin/sxd/**` 请求需携带请求头 `Authorization: Bearer <encrypted-token>`，token 由其他后端签发，明文为 JSON 载荷：

```json
{
  "userAccount": "<base64(登录账号)>",
  "exp": 1721980800000
}
```

- `userAccount` 为统一身份认证账号的 **Base64 编码**，解码后对应 `msp_user.account` 字段
- `exp` 为当前毫秒时间戳，**有效期 2 小时**，校验逻辑为 `now - exp ≤ 2 小时`
- **滑动窗口**：每次请求后端校验通过后，用 RSA 公钥重新加密 `{userAccount: base64(账号), exp: now}`，通过响应头 `X-Auth-Token` 返回刷新后的 token，前端下次请求时携带
- 解密后的 `userAccount` 存入 `request.setAttribute("userAccount", ...)` 供业务层使用

相关代码：
- `TokenInterceptor` — 拦截 `/sxd/**` 路径，RSA 私钥解密 → 解析 JSON → 校验有效期（通过 `rsa.token-validity-ms` 配置，默认 2 小时） → 公钥重新加密刷新 token
- `RsaUtils` — RSA 加解密工具类（`init` 初始化私钥+公钥、`decrypt` 解密、`encrypt` 加密刷新）
- `WebMvcConfig` — 注册拦截器
- 配置文件：`rsa.private-key` — RSA 私钥（PKCS8 PEM，含头尾）；`rsa.public-key` — RSA 公钥（X.509 PEM，含头尾）

### 8. API 路径

Context-path: `/techfin`
Controller base: `/sxd`

完整路径示例：
- `POST /techfin/sxd/upload-attachment` — 上传附件
- `DELETE /techfin/sxd/delete-attachment/{attId}` — 删除附件
- `POST /techfin/sxd/submit-materials` — 提交资料
- `POST /techfin/sxd/controller-name` — 查询实控人（用 cstId 查姓名、用 taskId 查 kjjr_ai_sxd_record 管户权，无管户权返回空字符串）
- `PUT /techfin/sxd/application-record/controller-name` — 确认实控人
- `POST /techfin/sxd/cust-ownership` — 管户权校验

### 9. 管户权校验

#### 9.1 管户权校验接口

`POST /techfin/sxd/cust-ownership`，校验当前用户是否拥有指定客户的管户权，结果写入 `kjjr_ai_sxd_record.has_ownership`。

**请求：**
```json
{ "taskId": "TASK-xxx", "cstId": "客户编号" }
```

**判断流程（`CustomerServiceImpl.getCustOwnership()`）：**

1. token 解密后的 `userAccount` → `msp_user.account` 查找用户 → 得到 `staff_code`、`role_id`（一个或多个，逗号分隔）、`dept_id`（仅一个）
   → 用 `role_id` 去 `msp_role` 表查询得到 `role_name` 集合（`role_id` 可能随环境变化，唯一不变的是角色名称，判断以 `role_name` 为准）
2. `dept_id` → `msp_dept.institution_no`
3. `role_name` 含**分行经办人员** **且** `institution_no` 为 `443536363`（科技金融创新中心） → ✅ 有管户权；否则进入下一步
4. `kjjr_ai_sxd_profile.cst_mngacc_inst_supr_insid` 匹配 `institution_no`（一致）**且** `role_name` 含**支行科室负责人** → ✅
5. 若上一步机构编号不一致、或一致但非支行科室负责人 → 进入下一步
6. `kjjr_ai_sxd_profile.cst_mngacc_cstmgr_id` 匹配 `msp_user.staff_code`（员工编号） → ✅
7. 其余情况 → ❌ 无管户权

结果写入 `kjjr_ai_sxd_record.has_ownership`（1-有，0-无）。

相关代码：
- `SxdController.getCustOwnership()` — 接口入口，从 request attribute 取 userAccount
- `CustomerService.getCustOwnership()` — Service 接口
- `CustomerServiceImpl.getCustOwnership()` — 实现类，注入 `MspUserMapper`、`MspDeptMapper`、`MspRoleMapper`、`CustomerProfileMapper`
- `MspRoleMapper` — 用 `role_id` 查 `msp_role`（`is_deleted = 0`）得 `role_name` 集合
- `RoleEnum` — 角色枚举，提供角色名称常量（按 `role_name` 匹配，不含 `role_id`）

#### 9.2 实控人查询的管户权检查

`POST /techfin/sxd/controller-name`（请求体 `{taskId, cstId}`）查询实控人时，用 `cstId` 查姓名、用 `taskId` 查管户权：

1. 校验 `taskId`、`cstId` 非空
2. 用 `cstId` 查询 `kjjr_ai_sxd_profile` 获取 `actCntlrNm`；查不到时抛出 `CUSTOMER_NOT_FOUND`
3. 用 `taskId`（主键）查询 `kjjr_ai_sxd_record` 获取 `has_ownership`；查不到时抛出 `TASK_NOT_FOUND`
4. `has_ownership = '1'` → 返回 `actCntlrNm`
5. `has_ownership` 为 `'0'` 或未设置 → 返回空字符串 `""`

相关代码：`CustomerServiceImpl.getControllerName(taskId, cstId)`

## Database Tables

| 表名 | 主键 | 说明 |
|------|------|------|
| `kjjr_ai_sxd_att` | `id` (BIGINT AUTO_INCREMENT) | 附件元信息，`att_id` 唯一索引 |
| `kjjr_ai_sxd_record` | `task_id` (VARCHAR(64)) | 申请记录，手工生成 `TASK-<32位hex>` |
| `kjjr_ai_sxd_doc` | `doc_id` (VARCHAR(64)) | 文档明细，外部 API 返回的 ID |
| `kjjr_ai_sxd_extract_data` | `id` (BIGINT AUTO_INCREMENT) | 提取数据缓存表 |
| `kjjr_ai_sxd_profile` | `cst_id` (VARCHAR(200)) | 客户信息表，以 `cst_id` 为主键 |
| `msp_user` | `id` (INT AUTO_INCREMENT) | 用户表，`account` 关联 token 中的 userAccount，`staff_code` 关联 `kjjr_ai_sxd_profile.cst_mngacc_cstmgr_id` |
| `msp_role` | `id` (INT AUTO_INCREMENT) | 角色表 |
| `msp_dept` | `id` (INT AUTO_INCREMENT) | 部门/机构表，`institution_no` 管户支行编号 |

详见 `docs/init-tables.sql`。

## Configuration

配置文件统一集中在 `techfin-controller/src/main/resources/application.properties`，主要包括：
- `api.doc-type.finance` / `api.doc-type.business` — 文档类型 ID 映射
- `file.upload.allowed-extensions.*` — 不同业务类型的文件扩展名白名单
- `api.default-token` — 外部 API 鉴权 token
- `rsa.private-key` — 前端 Token RSA 解密私钥（PKCS8 PEM）
- `rsa.public-key` — 前端 Token RSA 加密公钥（X.509 PEM，用于刷新 token）
- `rsa.token-validity-ms` — Token 有效期（毫秒，默认 7200000，即 2 小时）
- `sxd.cleanup.cron` — 定时清理任务 cron 表达式（默认 `0 0 2 * * ?`，每天凌晨 2:00）
- `sxd.cleanup.orphan-attachment-retention-hours` — 孤立附件保留时长（小时，默认 24）
- `mybatis-plus.configuration.log-impl` — SQL 日志

配置类：`ApiProperties`（prefix=`api`）、`FileUploadConfig`（prefix=`file.upload`）

## Documentation

业务功能说明文档在 `docs/` 目录下：
- `docs/上传材料功能说明.md` — 附件上传 + 提交资料全流程
- `docs/init-tables.sql` — SXD 模块建表 SQL
- `docs/create-msp-{dept,role,user}-table.sql` — MSP 模块建表 SQL
- `docs/要素提取功能说明.md` — 资料要素提取
- `docs/报告生成功能说明.md` — 报告生成
- `docs/信息确认功能说明.md` — 实控人查询（含管户权检查） + 管户权校验
