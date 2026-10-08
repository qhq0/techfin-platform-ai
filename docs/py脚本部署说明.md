# SXD 客户数据每日导入 — 部署说明（容器 + `--schedule` 自循环）

## 0. 适用场景

- **运行形态**：应用跑在 **Docker 容器**内（容器 PID 1 是 `/starts.sh`，无 systemd，定时任务也在容器内执行）
- **容器内存**：目标环境 **2GB**（重点，见第 2 节）
- **容器无外网**：脚本必须保持**零第三方依赖**——只允许 Python 标准库 + `mysql` 命令行客户端，
  不得引入 pip 安装包、不得联网下载、不落临时文件。改动脚本后请按 §5 第 ⑤ 步复验
- **权限**：容器内以普通用户（如 `AP_USER`）运行，无 root
- **定时方案**：脚本 `--schedule` **自循环模式**，由 `/starts.sh` 后台拉起，**不依赖 cron / systemd / root**

> 为什么不用 systemd 定时：容器内 PID 1 是 `starts.sh` 而非 systemd，`systemctl`/`loginctl` 全部不可用。
> 而容器本身 24 小时运行 = 常驻保证，脚本进程随容器存活，到点必执行，效果等价甚至更可靠。

本仓库 `docs/` 目录下待部署的文件：

```
docs/
├── insert_sxd_profile.py                      # 导入脚本（含 --schedule 自循环模式）
├── t101_sz_hjy_sxd_profile_20260814_0001.csv  # 样例数据文件（每日推送格式）
└── py脚本部署说明.md                          # 本文档
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

### 1.1 历史推送文件清理（每次入数前只保留最近 7 份）

脚本在每次入数**之前**先清理推送目录，只留下**最近 `CLEANUP_KEEP_COUNT`（默认 7）份**推送文件，
更早的历史推送文件删除，避免目录无限膨胀。

```
每日推送文件 ──> 扫描目录 ──> 取日期最新一份 ──> 删除较早的历史推送文件（只留最近 7 份）──> 按 cst_id upsert
```

**保留口径**：把目录里日期能解析的推送文件按**文件名里的日期**升序排，保留最后 N 份。

| 目录里的份数 | `--keep-count 7`（默认）的结果 |
|---|---|
| 10 份 | 保留最新 7 份，删掉最早的 3 份 |
| 恰好 7 份 | 一份都不删 |
| 5 份 | 一份都不删 |
| `--keep-count 0` | 只保留本次要入数的那一份（等于改造前的"只留最新"行为） |

**删除范围（按"宁可少删、不可误删"设计）**：

- 只处理 `/push` **本层**的普通文件，**不递归**子目录、不跟随符号链接
- 只删**整名完全吻合** `t101_sz_hjy_sxd_profile_YYYYMMDD_NNNN(.csv)` 的文件
- 同前缀的 `.bak` / `.tmp` / `.part` / `.csv.ok` / `.csv.gz` 等**一律不动**（也正因如此，
  这类文件不会被误当成数据文件拿去入数）
- 其它无关文件、子目录里的文件一律不动
- 本次要入数的那份**永不删除**，**哪怕它不在最近 N 份里**；若它的命名不完全规范，则整个清理直接放弃
- 文件名日期解析不出来的文件（如 `20261332`）**一律不删**，且**不占用保留名额**（不会把正常文件挤掉）
- 单个文件删除失败只告警、不中断，**不影响本次入数**

**开关**：

| 参数 | 行为 |
|---|---|
| （默认，不传） | 入数**前**清理，保留最近 7 份 |
| `--keep-count N` | 改成保留最近 N 份（`0` = 只保留本次入数的那份） |
| `--cleanup-after` | 入数**成功后**才清理（入数失败则历史文件保留，更稳妥） |
| `--no-cleanup` | 完全不清理，历史文件都留着 |
| `--dry-run` | 只打印将删除/保留哪些文件，**不真删** |

**三点注意**：

1. 默认"先删后导"意味着**一旦本次入数失败，被删掉的旧文件已经没了**。若对数据留存有要求，
   建议改用 `--cleanup-after`：导入成功才删，失败时旧文件还在，重跑即可。
2. `--limit`（冒烟测试）与 `--csv`（指定单个文件）两种情况下会**自动跳过清理**，避免误删。
3. **磁盘占用按份数算**：单文件约 1.6GB，保留 7 份 ≈ **11GB**，先确认 NAS 空间；
   空间紧张就把 `--keep-count` 调小（如 `--keep-count 3` ≈ 5GB）。

**推荐上线方式**：先预演一次，确认要删的正是预期的历史文件，再切默认执行：

```bash
python3 insert_sxd_profile.py --dir /push --dry-run    # 只预演，不删任何文件
```

## 2. 大文件与内存（2GB 容器重点）

### 2.1 旧版为什么会被杀掉

旧实现在 `load_rows()` 里 `[ln for ln in f]` 把**整份文件读进内存**，之后又整份复制了 4~5 次：

| 阶段 | 内存 |
|---|---|
| `lines = [ln for ln in f]` | 整个文件（每行 49 个 str 对象，对象头开销 ≈ 2.4KB/行） |
| `rows.append(vals)` | 再一份，`list[list[str]]` |
| `build_sql(rows)` | 拼出**一整条**巨型 SQL 字符串 + 全部元组片段 |
| `sql.encode("utf-8")` | 再一份 bytes（`subprocess.run(input=...)` 持有） |

峰值 ≈ **文件大小的 5~8 倍**。2GB 容器里一旦文件超过约 **250MB**，内核 OOM-killer 就会直接 SIGKILL，
表现是"跑到 `with open(...)` 那一行就被杀"，日志无 Python 堆栈、退出码 137。
经营范围、注册地址、`byzd1~10` 这些宽字段越多，放大倍数越高。

> 注意：报错指向 `open()` 只是因为那是第一次接触数据的代码行，并不是 `open()` 本身耗内存。

### 2.2 新版做法：全程流式分批，内存与文件大小无关

- 逐行读文件（生成器），不落地整份文件
- 按「**行数 + 单批 SQL 字节数**」双重上限切小批，每批一条 `INSERT ... ON DUPLICATE KEY UPDATE`
- SQL 写入**一个常驻 mysql 客户端进程**的 stdin，stdout/stderr 由后台线程持续消费（防管道写满死锁）
- 单批字节上限启动时按服务端 `max_allowed_packet` 自动收紧，不会踩 `packet too large`
- **每批独立提交**：中途失败已入库的批次保留，重跑幂等（upsert），不会写坏数据

### 2.3 实测峰值物理内存（真实进程 RSS）

| 场景 | 峰值物理内存 |
|---|---|
| **旧版** @ 40MB 文件 | **303 MB（7.5 倍文件大小）** |
| 新版 @ 40MB 文件 | 30.8 MB |
| 新版 @ 120MB 文件 | 31.0 MB |
| 新版 @ 500MB 文件 | 31.3 MB |
| 新版 @ 500MB ＋ `--max-batch-bytes 524288 --batch-size 200` | 27.3 MB |
| 新版 @ 500MB ＋ `--load-data`（Python 侧） | 27.9 MB |

文件从 40MB 放大到 500MB（12.5 倍），峰值只从 30.8MB 变到 31.3MB —— **基本不动**。
其中约 25MB 是 Python 解释器自身基线，脚本净开销只有几 MB。

### 2.4 2GB 容器的结论与推荐配置

- **默认参数直接用，不需要调参**：峰值恒定 ≈ **31MB，占 2GB 的 1.5%**，与推送文件多大无关
- 想更保守（比如容器里有 JVM 抢内存）：加 `--max-batch-bytes 1048576`，峰值降到 ≈ 27MB，代价只是多几次往返
- 读大文件会占用操作系统 page cache，那部分属**可回收内存**，不会导致 OOM
- 判定是否真的是 OOM：宿主机 `dmesg | grep -i "killed process"`，或 `docker inspect --format '{{.State.OOMKilled}}' <容器名>`，
  脚本自身退出码 **137 / 143** 都属被杀；正常失败退出码为 **1** 并打印中文原因
- `--schedule` 常驻进程（≈31MB）＋ 常驻 mysql 客户端子进程，合计仍在几十 MB 量级

### 2.5 `--load-data` 的适用边界（同容器跑 DB 时慎用）

`--load-data` 用 `LOAD DATA LOCAL INFILE` 把文件灌进 InnoDB **临时表**再分批刷入目标表，
Python 侧几乎零解析开销，**速度最快**，但代价转移到了数据库端：

- 灌表期间临时表会占用**磁盘 + 服务端内存**（innodb_temp 空间），文件越大占用越大
- 需要服务端 `local_infile=ON`，否则脚本会明确报错并提示（不会静默失败）
- **本脚本默认 `DB_HOST = localhost`**：如果 MySQL 就在这台 2GB 容器/同一宿主机上，慎用 `--load-data`；
  数据库独立部署或资源充足时，才建议用它提速

> 结论：**默认流式模式是 2GB 容器下的稳妥选择**；`--load-data` 作为明确知道 DB 侧有余量时的加速选项。

## 3. 容器改造清单（三步）

### 3.1 Dockerfile：装 python3 + mysql 客户端、拷入脚本

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

# 日志统一按 UTF-8 写（避免容器 locale 非 UTF-8 时，insert.log 里的中文变乱码）
ENV PYTHONIOENCODING=utf-8
```

> `mysql` 客户端在 CentOS/Kylin 源里通常由 `mysql` 或 `mariadb` 包提供；装后容器内 `which mysql` 有结果即可。

### 3.2 starts.sh：后台拉起自循环脚本

```bash
#!/bin/sh
# 后台启动每日导入（容器在则进程在，到点执行；日志落盘方便排查）
nohup python3 ${AP_HOME}/insert_sxd_profile.py \
    --schedule --dir /push \
    >> /logs/insert.log 2>&1 &

# 原主进程继续：启动 Spring Boot 应用（保持前台，容器靠它存活）
exec java -jar ${AP_HOME}/app.jar
```

> `--dir /push` 指向推送卷目录；`--schedule` 启动即跑一次，之后每天 06:00 执行。

### 3.3 docker run：挂卷 + 环境变量

```bash
docker run -d \
  -v /宿主机/push目录:/push \                       # 每日推送文件落地卷（必须挂载）
  -e TZ=Asia/Shanghai \                              # 时区
  -m 2g \                                            # 2GB 内存上限
  -p 8080:8080 \
  你的镜像名
```

> 每日文件推送到宿主机 `/宿主机/push目录/`，通过卷自动出现在容器 `/push/` 里，脚本每天扫描取最新。
> **数据库连接不需要传环境变量**，直接改脚本里的 `DB_*` 常量（见第 4 节）。

## 4. 数据库连接配置

数据库连接**直接写死在脚本里**（`docs/insert_sxd_profile.py` 顶部 `DB_*` 常量），部署时按实际环境修改即可，**不需要 Docker 环境变量**：

```python
# docs/insert_sxd_profile.py 顶部
DB_HOST = "localhost"     # 数据库地址（容器内访问宿主库填宿主机 IP）
DB_PORT = "3306"
DB_USER = "qiu"
DB_PASS = "Qiu@2026"
DB_NAME = "mydb"
```

## 5. 手动验证（构建后先跑通再上定时）

```bash
# 容器内执行（进容器或 docker exec）
docker exec -it <容器> sh

cd /app
# ① 只解析并打印第 1 批 SQL，不连库；同时预演会删掉哪些历史文件（不真删）
#    确认「将删除历史文件：…」列表里没有不该删的东西，再往下走
python3 insert_sxd_profile.py --dir /push --dry-run

# ② 确认 upsert 片段正常（应看到 ON DUPLICATE KEY UPDATE，且不含 cst_id）
python3 insert_sxd_profile.py --dir /push --dry-run | grep -A2 "ON DUPLICATE"

# ③ 小样本冒烟：只导前 5000 行，确认能连库、能写入（大文件必备这一步）
python3 insert_sxd_profile.py --dir /push --limit 5000

# ④ 真正执行一次（全量）
python3 insert_sxd_profile.py --dir /push

# ⑤ 无外网 / 无第三方包复验：隔离模式（不加载 site-packages）应能跑通、退出码 0
#    改过脚本后务必执行一次，确认没有引入 pip 依赖
python3 -I -S insert_sxd_profile.py --dir /push --dry-run --limit 2 && echo "依赖复验通过"
```

**验证 upsert 生效**：再次执行 ④ 后 `SELECT COUNT(*)` 总行数不变；改一条 `dep_bal` 后重跑，观察该行被更新。

**观察导入进度**：脚本按 `--log-every`（默认每 5 万行）在 stderr 打一条带时间戳的进度日志，
`tail -f /logs/insert.log` 可以看到"已处理 N 行（M 批）"，不会像旧版那样静默卡死。

## 6. `--schedule` 定时说明

- **启动即执行一次**（容器重启后数据立刻刷新），之后**每天 `--hour:--minute`（默认 06:00）执行一次**
- 循环常驻，进程随容器存活；单次失败只记日志、不退出，下轮继续
- 时间用 `--hour` / `--minute` 调整，例如每天 3 点半：`--schedule --hour 3 --minute 30`
- 每次循环**重新扫描** `/push`，新到的每日文件自动被取用
- 查看日志：`cat /logs/insert.log`；确认进程：`ps aux | grep insert_sxd_profile`
- 停止方式：容器停止即随之停止；重启容器由 `starts.sh` 自动重新拉起

## 7. 日志与排障

| 现象 | 排查 |
|---|---|
| 进程被杀、**退出码 137**、日志无堆栈 | 内存不足被 OOM-killer 杀。确认用的是本版脚本（旧版内存放大 5~8 倍）；查 `docker inspect --format '{{.State.OOMKilled}}' <容器>`；必要时 `--max-batch-bytes 1048576` |
| `未找到 t101_sz_hjy_sxd_profile_YYYYMMDD_ 推送文件` | `/push` 卷为空或文件名不合规（需匹配 `t101_sz_hjy_sxd_profile_YYYYMMDD_0001` 命名）；确认卷已挂载 |
| `推送目录不存在` | `--dir` 路径与挂载路径不一致 |
| `无法启动 mysql 客户端` | 镜像未装 mysql 客户端，或不在 PATH；按 3.1 重装 |
| `mysql 执行失败` / 客户端返回码非 0 | 数据库地址/账号/密码错误；网络不通；`/push` 目录权限不足 |
| `Got a packet bigger than 'max_allowed_packet'` | 单批 SQL 超了服务端上限。脚本已自动按 `max_allowed_packet` 收紧，若仍出现，手工加 `--max-batch-bytes 1048576` |
| `第 N 行字段数应为 49，实际 M` | 推送文件某行字段数不合规。确认上游格式后，可用 `--skip-bad-lines` 跳过坏行继续导入（坏行会记日志） |
| `服务端 local_infile=OFF，--load-data 需要…` | 用 `--load-data` 但服务端没开；要么在服务端开 `local_infile=ON`，要么去掉该参数走默认模式 |
| `python3: command not found` | Dockerfile 未装 python3，重新构建 |
| 历史文件**没被清理** | ① 用了 `--no-cleanup` / `--limit` / `--csv`（这三种情况会主动跳过清理，日志里有说明）；② 旧文件名不符合规范（如 `xxx.csv.bak`）被安全跳过；③ 保留文件本身命名不规范，清理整体放弃 |
| 清理后保留的**份数不对** | 看 `--keep-count`（默认 7）；每次日志里 `清理完成（保留最近 N 份 + 本次入数的那份）：…` 会写明本次实际用的 N；想先看效果用 `--dir /push --dry-run` |
| 想确认会删哪些文件 | 先跑 `--dir /push --dry-run`，日志里会逐条打印"将删除历史文件：…"，且**不会真删** |
| 上游文件还在推送中就被当成最新文件入数 | 删/导的目标永远是文件名日期最大的那份；若上游存在"写一半"的情况，建议上游先写临时名再改名，或改用 `--cleanup-after` 保留旧文件兜底 |
| 扫描不到任何推送文件 | 文件**整名**必须吻合 `t101_sz_hjy_sxd_profile_YYYYMMDD_NNNN` 或加 `.csv` 后缀；`.bak`/`.tmp`/`.part` 等后缀不会被识别为数据文件 |
| `ModuleNotFoundError: No module named xxx` | 脚本被引入了第三方包，而容器无外网装不上。改回标准库实现，并按 §5 第 ⑤ 步复验 |
| 连不上数据库（`localhost` 改成主机名后） | 容器内 DNS 需能解析该主机名；无外网环境建议直接填 IP |
| 定时不执行 | 确认 `starts.sh` 里 nohup 行存在、日志有 "进入 --schedule 模式"；`ps` 看进程是否存活 |
| 执行时间不对（差 8 小时） | 容器时区未设置，`date` 查看；按 3.1 设 `TZ=Asia/Shanghai` |

## 8. 参数速查

| 参数 | 说明 |
|---|---|
| `--dir <路径>` | 每日推送目录（默认脚本所在目录） |
| `--csv <文件>` | 指定单个文件，跳过自动扫描 |
| `--dry-run` | 只解析并打印第 1 批 SQL，不连库 |
| `--schedule` / `--hour` / `--minute` | 常驻定时模式（默认每天 06:00） |
| `--batch-size` | 单批最多行数，默认 1000 |
| `--max-batch-bytes` | 单批 SQL 字节上限，默认 8MB（会按服务端 `max_allowed_packet` 自动收紧） |
| `--log-every` | 每处理多少行打一条进度日志，默认 50000 |
| `--limit N` | 只导前 N 行，冒烟测试用（0=全部） |
| `--skip-bad-lines` | 字段数异常的行跳过并告警，而不是中止 |
| `--load-data` / `--chunk-rows` | 大文件快速通道（LOAD DATA LOCAL INFILE），需服务端 `local_infile=ON` |
| `--no-cleanup` | 不清理历史推送文件（默认会清理，见 §1.1） |
| `--keep-count N` | 清理时只保留最近 N 份推送文件，更早的删除（默认 7；0=只保留本次入数的那份） |
| `--cleanup-after` | 改为入数成功后再清理，失败则保留历史文件 |

## 9. 文件格式约定（供上游核对）

- 文件名：`t101_sz_hjy_sxd_profile_YYYYMMDD_NNNN.csv`（`NNNN` 为批次序号）。
  脚本按**整名**匹配，`.bak` / `.tmp` / `.part` 等同前缀文件既不会被入数，也不会被清理删除
- UTF-8 编码、无标题行、每行 **49 个字段**，以 `|@|` 分隔，**行尾带一个 `|@|`**（对应最后一个空字段）
- 第 2 列 `etl_dt` 不属于目标表，脚本自动跳过
- 日期字段（`data_bsn_dt`、`fd_dt`、`dep_bal_dt`、`acc_start_dt`）一律为 `yyyy-mm-dd`
- 空字段导入后存为 `NULL`（`--load-data` 模式同样通过 `NULLIF` 保持该语义）
- 兼容 `\n` 与 `\r\n` 两种换行
