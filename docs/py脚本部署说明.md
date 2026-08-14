# SXD 客户数据每日导入 — 部署说明（容器 + `--schedule` 自循环）

## 0. 适用场景

- **运行形态**：应用跑在 **Docker 容器**内（容器 PID 1 是 `/starts.sh`，无 systemd，定时任务也在容器内执行）
- **权限**：容器内以普通用户（如 `AP_USER`）运行，无 root
- **定时方案**：脚本 `--schedule` **自循环模式**，由 `/starts.sh` 后台拉起，**不依赖 cron / systemd / root**

> 为什么不用 systemd 定时：容器内 PID 1 是 `starts.sh` 而非 systemd，`systemctl`/`loginctl` 全部不可用。
> 而容器本身 24 小时运行 = 常驻保证，脚本进程随容器存活，到点必执行，效果等价甚至更可靠。

本仓库 `docs/` 目录下待部署的文件：

```
docs/
├── insert_sxd_profile.py                   # 导入脚本（含 --schedule 自循环模式）
├── t101_sz_hjy_sxd_profile_20260814_0001.csv  # 样例数据文件（每日推送格式）
└── 部署说明.md                             # 本文档
```

## 1. 背景与处理流程

数据源为**每日推送的文件**，命名规则：`t101_sz_hjy_sxd_profile_YYYYMMDD_0001.csv`（文件名内嵌日期 `YYYYMMDD`）。

```
每日推送文件 ──> 脚本自动扫描目录 ──> 取日期最新一份 ──> 按 cst_id upsert 到 kjjr_ai_sxd_profile
```

**批量更新原则（upsert，主键 `cst_id`）：**

1. `cst_id` 在表中**已存在** → **更新**该行
2. `cst_id` 在表中**不存在** → **插入**新行

实现方式：`INSERT ... ON DUPLICATE KEY UPDATE`，兼容 MariaDB 10.3+ 与 MySQL 8.0+。

## 2. 容器改造清单（三步）

### 2.1 Dockerfile：装 python3 + mysql 客户端、拷入脚本

```dockerfile
# 构建阶段用 root 安装运行时依赖（Kylin/CentOS 系；包名按镜像实际源调整）
RUN yum install -y python3 mysql \
    || dnf install -y python3 mysql \
    || yum install -y python3 mariadb

# 拷贝导入脚本到应用目录
COPY insert_sxd_profile.py ${AP_HOME}/

# 设置时区（关键！默认 UTC 会让"凌晨2点"差 8 小时）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
```

> `mysql` 客户端在 CentOS/Kylin 源里通常由 `mysql` 或 `mariadb` 包提供；装后容器内 `which mysql` 有结果即可。

### 2.2 starts.sh：后台拉起自循环脚本

```bash
#!/bin/sh
# 后台启动每日导入（容器在则进程在，到点执行；日志落盘方便排查）
nohup python3 ${AP_HOME}/insert_sxd_profile.py \
    --schedule --dir /push \
    >> /logs/insert.log 2>&1 &

# 原主进程继续：启动 Spring Boot 应用（保持前台，容器靠它存活）
exec java -jar ${AP_HOME}/app.jar
```

> `--dir /push` 指向推送卷目录；`--schedule` 启动即跑一次，之后每天 02:00 执行。

### 2.3 docker run：挂卷 + 环境变量

```bash
docker run -d \
  -v /宿主机/push目录:/push \                       # 每日推送文件落地卷（必须挂载）
  -e TZ=Asia/Shanghai \                              # 时区
  -p 8080:8080 \
  你的镜像名
```

> 每日文件推送到宿主机 `/宿主机/push目录/`，通过卷自动出现在容器 `/push/` 里，脚本每天扫描取最新。
> **数据库连接不需要传环境变量**，直接改脚本里的 `DB_*` 常量（见第 3 节）。

## 3. 数据库连接配置

数据库连接**直接写死在脚本里**（`docs/insert_sxd_profile.py` 顶部 `DB_*` 常量），部署时按实际环境修改即可，**不需要 Docker 环境变量**：

```python
# docs/insert_sxd_profile.py 顶部
DB_HOST = "localhost"     # 数据库地址（容器内访问宿主库填宿主机 IP）
DB_PORT = "3306"
DB_USER = "qiu"
DB_PASS = "Qiu@2026"
DB_NAME = "mydb"
```

## 4. 手动验证（构建后先跑通再上定时）

```bash
# 容器内执行（进容器或 docker exec）
docker exec -it <容器> sh

cd /app
# ① 只打印 SQL，不写库（确认能选中最新文件、SQL 无误）
python3 insert_sxd_profile.py --dir /push --dry-run

# ② 确认 upsert 片段正常（应看到 ON DUPLICATE KEY UPDATE，且不含 cst_id）
python3 insert_sxd_profile.py --dir /push --dry-run | grep -A2 "ON DUPLICATE"

# ③ 真正执行一次（首次全部插入）
python3 insert_sxd_profile.py --dir /push
```

**验证 upsert 生效**：再次执行 ③ 后 `SELECT COUNT(*)` 总行数不变；改一条 `dep_bal` 后重跑，观察该行被更新。

## 5. `--schedule` 定时说明

- **启动即执行一次**（容器重启后数据立刻刷新），之后**每天 `--hour:--minute`（默认 02:00）执行一次**
- 循环常驻，进程随容器存活；单次失败只记日志、不退出，下轮继续
- 时间用 `--hour` / `--minute` 调整，例如每天 3 点半：`--schedule --hour 3 --minute 30`
- 每次循环**重新扫描** `/push`，新到的每日文件自动被取用
- 查看日志：`cat /logs/insert.log`；确认进程：`ps aux | grep insert_sxd_profile`
- 停止方式：容器停止即随之停止；重启容器由 `starts.sh` 自动重新拉起

## 6. 日志与排障

| 现象 | 排查 |
|---|---|
| `未找到 t101_sz_hjy_sxd_profile_YYYYMMDD_ 推送文件` | `/push` 卷为空或文件名不合规（需匹配 `t101_sz_hjy_sxd_profile_YYYYMMDD_0001` 命名）；确认卷已挂载 |
| `推送目录不存在` | `--dir` 路径与挂载路径不一致 |
| `mysql 执行失败` | 数据库地址/账号/密码错误；客户端未安装；网络不通；`/push` 目录权限不足 |
| `python3: command not found` | Dockerfile 未装 python3，重新构建 |
| 定时不执行 | 确认 `starts.sh` 里 nohup 行存在、日志有 "进入 --schedule 模式"；`ps` 看进程是否存活 |
| 执行时间不对（差 8 小时） | 容器时区未设置，`date` 查看；按 2.1 设 `TZ=Asia/Shanghai` |

## 7. 文件格式约定（供上游核对）

- UTF-8 编码、无标题行、每行 **49 个字段**，以 `|@|` 分隔
- 第 2 列 `etl_dt` 不属于目标表，脚本自动跳过
- 日期字段（`data_bsn_dt`、`fd_dt`、`dep_bal_dt`、`acc_start_dt`）一律为 `yyyy-mm-dd`
- 空字段导入后存为 `NULL`
