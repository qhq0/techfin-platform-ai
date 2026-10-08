#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""每日推送数据导入 kjjr_ai_sxd_profile（按主键 cst_id upsert）。

环境：Python 3 标准库 + mysql 客户端，无第三方包、无需联网（容器无外网）。
数据库连接常量 DB_* 写死在下方，部署时直接改（不走 Docker 环境变量）。

用法：
    python3 insert_sxd_profile.py               # 单次：扫描默认目录，取日期最新文件并 upsert
    python3 insert_sxd_profile.py --dir /path   # 指定推送目录
    python3 insert_sxd_profile.py --csv <路径>  # 指定单个文件（跳过自动扫描）
    python3 insert_sxd_profile.py --dry-run     # 只打印第 1 批 SQL，不执行
    python3 insert_sxd_profile.py --schedule    # 自循环定时：启动跑一次，之后每天 06:00
    python3 insert_sxd_profile.py --load-data   # 大文件快速通道（需服务端 local_infile=ON）
    其余参数（--limit / --batch-size / --skip-bad-lines 等）见 --help

    --schedule 由 /starts.sh 后台拉起即可，无需 cron/systemd/root：
        nohup python3 insert_sxd_profile.py --schedule --dir /app/push >> /app/insert.log 2>&1 &
    容器需设 TZ=Asia/Shanghai（否则差 8 小时）并挂载推送目录；部署细节见 docs/py脚本部署说明.md

数据文件：t101_sz_hjy_sxd_profile_YYYYMMDD_0001.csv，UTF-8、无表头、每行 49 个字段以 |@| 分隔；
第 2 列 etl_dt 不入库，空字段存 NULL，日期字段为 yyyy-mm-dd。

清理：每次入数前清理推送目录，只保留**最近 CLEANUP_KEEP_COUNT（默认 7）份**推送文件，
按**文件名里的日期**排序（不看 mtime）；只删整名完全符合
t101_sz_hjy_sxd_profile_YYYYMMDD_NNNN(.csv) 的文件，不递归子目录、不碰 .bak/.tmp 等临时文件，
本次要入数的文件永不删（即使它不在最近 N 份里）；
--keep-count 可改保留份数（0=只保留本次入数的那份），--no-cleanup 可禁用，
--cleanup-after 改为入数成功后再删，--dry-run 只预演不删）。

upsert：cst_id 已存在则更新、不存在则插入，实现为 INSERT ... ON DUPLICATE KEY UPDATE。

内存：全程流式分批，峰值内存恒定（实测约 31MB，与文件大小无关）。旧版整份读入再拼一条巨型 SQL，
峰值约为文件大小的 5~8 倍，大文件会被容器 OOM-killer 杀掉（退出码 137），已弃用。
"""

import os
import re
import sys
import time
import argparse
import subprocess
import threading
from datetime import datetime, timedelta

# ---------------------------------------------------------------------------
# 数据库连接信息（直接在此写死，部署时按实际环境修改）
# ---------------------------------------------------------------------------
DB_HOST = "localhost"
DB_PORT = "3306"
DB_USER = "qiu"
DB_PASS = "Qiu@2026"
DB_NAME = "mydb"

# 目标表
TABLE = "kjjr_ai_sxd_profile"

# 每日推送文件名：t101_sz_hjy_sxd_profile_YYYYMMDD_0001.csv，日期在内嵌位置。
# **整名必须完全吻合**（可选 .csv 后缀）：这样 .bak / .tmp / .part / .csv.ok 这类同前缀的
# 临时、备份、标记文件既不会被误当成数据文件去入数，也不会在清理时被误删。
FILE_RE = re.compile(r"^t101_sz_hjy_sxd_profile_(\d{8})_\d+(\.csv)?$")

# 清理策略：只保留最近多少份推送文件（按**文件名里的日期**排序），更早的删除。
# 改成别的份数就动这一个数；命令行 --keep-count 可临时覆盖。
CLEANUP_KEEP_COUNT = 7

# 表列顺序（与 DESCRIBE kjjr_ai_sxd_profile 一致，不含 etl_dt）
COLUMNS = [
    "data_bsn_dt", "cst_id", "cst_nm", "fd_dt", "lgl_rprs_nm", "act_cntlr_nm",
    "rgst_cpamt", "arcptl_cpamt", "credit_code", "CPCT_TPCD", "entp_sz_cd",
    "dtl_adr", "org_oprt_scop_dsc", "entp_bliy", "tech_tag", "tech_flow",
    "kc_score", "ENTP_PTNT_NUM", "ENTPPRCTNEWTPPTNT_NUM", "ENTP_IVT_PTNT_NUM",
    "CLST5YRINNRSWCOPR_NUM", "if_loan", "product_name", "loan_amount",
    "loan_term", "loan_balance", "dep_bal", "dep_bal_dt", "dep_aadbal",
    "acc_start_dt", "acc_type", "isug_pnum", "avg_12_isug_amt", "if_yuqi",
    "ltgtrltd_ind", "if_rad_alarm", "cst_mngacc_cstmgr_id",
    "cst_mngacc_inst_supr_insid",
    "byzd1", "byzd2", "byzd3", "byzd4", "byzd5",
    "byzd6", "byzd7", "byzd8", "byzd9", "byzd10",
]

# 文件字段数（含 etl_dt）
RAW_FIELDS = 49

# 派生 SQL 片段（模块级只算一次）
COL_SQL = ", ".join("`%s`" % c for c in COLUMNS)
UPDATE_COLS = [c for c in COLUMNS if c != "cst_id"]
UPDATE_SQL = ", ".join("`%s`=VALUES(`%s`)" % (c, c) for c in UPDATE_COLS)
INSERT_HEAD = "INSERT INTO `%s` (%s) VALUES\n" % (TABLE, COL_SQL)
INSERT_TAIL = "\nON DUPLICATE KEY UPDATE\n%s;" % UPDATE_SQL

# 流式分批默认参数
DEFAULT_BATCH_SIZE = 1000                     # 单批最多行数
DEFAULT_MAX_BATCH_BYTES = 8 * 1024 * 1024     # 单批 SQL 字节上限（仍会按服务端 max_allowed_packet 收紧）
DEFAULT_LOG_EVERY = 50000                     # 每处理多少行打一条进度日志

# --load-data 模式
STAGING_TABLE = "_tmp_sxd_profile"
STAGING_COLS = ["c%d" % (i + 1) for i in range(RAW_FIELDS)]
# 目标列 -> 临时表列：第 1 列取 c1，其余跳过 c2(etl_dt) 依次后移一位
TARGET_FROM_STAGING = ["`c1`"] + ["`c%d`" % (k + 2) for k in range(1, len(COLUMNS))]
DEFAULT_CHUNK_ROWS = 50000                    # 灌库时每个 INSERT...SELECT 最多刷多少行


class MysqlError(RuntimeError):
    """mysql 客户端执行失败。"""


class Stats(object):
    """一次导入的统计信息。"""

    __slots__ = ("skip_bad", "bad", "rows")

    def __init__(self, skip_bad=False):
        self.skip_bad = skip_bad      # True: 字段数异常的行跳过并告警；False: 直接报错中止
        self.bad = 0                  # 被跳过的坏行数
        self.rows = 0                 # 已解析出的数据行数


def log(msg):
    """统一日志输出（带时间戳，便于容器内 append 到日志文件后排障）。"""
    sys.stderr.write("[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    sys.stderr.flush()


# ---------------------------------------------------------------------------
# mysql 客户端封装
# ---------------------------------------------------------------------------
def mysql_env():
    """构造环境变量：密码走 MYSQL_PWD，避免暴露在进程列表里。"""
    env = dict(os.environ)
    env["MYSQL_PWD"] = DB_PASS
    return env


def mysql_base_cmd(extra_args=()):
    return ["mysql", "-h", DB_HOST, "-P", DB_PORT, "-u", DB_USER,
            "--default-character-set=utf8mb4", "--batch"] + list(extra_args) + [DB_NAME]


def mysql_once(sql, extra_args=()):
    """一次性执行一小段 SQL（仅用于取变量等小查询），返回 stdout 文本。"""
    try:
        proc = subprocess.run(mysql_base_cmd(extra_args), input=sql.encode("utf-8"),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=mysql_env())
    except OSError as e:
        raise MysqlError("无法启动 mysql 客户端（是否已安装并在 PATH 中）：%s" % e)
    if proc.returncode != 0:
        msg = proc.stderr.decode("utf-8", "replace").strip() or \
              proc.stdout.decode("utf-8", "replace").strip()
        raise MysqlError("mysql 执行失败（返回码 %d）：%s" % (proc.returncode, msg))
    return proc.stdout.decode("utf-8", "replace")


def query_variable(name):
    """读一个服务端变量，取不到返回 None。"""
    try:
        out = mysql_once("SHOW VARIABLES LIKE '%s';" % name, extra_args=("-N",))
    except MysqlError as e:
        log("读取服务端变量 %s 失败：%s" % (name, e))
        return None
    parts = out.strip().split("\t")
    return parts[1].strip() if len(parts) >= 2 else (out.strip() or None)


class MysqlRunner(object):
    """常驻 mysql 客户端：SQL 从 stdin 流式写入，stdout/stderr 由后台线程消化。

    这样做的原因：
      - 每条 SQL 写完即释放，不在 Python 侧积累，内存恒定
      - 只写不读会写满管道缓冲导致死锁，所以必须并发消费 stdout/stderr
      - 整份输入结束后关闭 stdin（EOF），再看返回码判断成败，错误信息完整
    """

    def __init__(self, extra_args=()):
        self.cmd = mysql_base_cmd(extra_args)
        self.err_lines = []
        self.out_lines = []
        try:
            self.proc = subprocess.Popen(
                self.cmd, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=mysql_env())
        except OSError as e:
            raise MysqlError("无法启动 mysql 客户端（是否已安装并在 PATH 中）：%s" % e)
        self._threads = [
            self._spawn_drain(self.proc.stdout, self.out_lines),
            self._spawn_drain(self.proc.stderr, self.err_lines),
        ]

    @staticmethod
    def _spawn_drain(stream, sink):
        def drain():
            try:
                for raw in iter(stream.readline, b""):
                    line = raw.decode("utf-8", "replace").rstrip()
                    if line:
                        sink.append(line)
                        if len(sink) > 200:      # 只留最近 200 行，避免长任务日志把内存吃满
                            del sink[:100]
            except Exception:
                pass
        t = threading.Thread(target=drain)
        t.daemon = True
        t.start()
        return t

    def write(self, text):
        """写入一段 SQL 并 flush；客户端已退出则立即报错。"""
        try:
            self.proc.stdin.write(text.encode("utf-8"))
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError):
            raise MysqlError(self.error_text() or "写入 mysql 失败（客户端已退出）")

    def error_text(self):
        """取最近的错误输出，过滤掉无关的 Warning。"""
        errs = [ln for ln in self.err_lines if "Warning" not in ln]
        return "\n".join((errs or self.err_lines)[-15:])

    def close(self):
        """收尾：关 stdin 触发 EOF，等客户端退出，返回码非 0 视为失败。"""
        if self.proc is None:
            return
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        code = self.proc.wait()
        for t in self._threads:
            t.join(timeout=5)
        if code != 0:
            raise MysqlError(self.error_text() or ("mysql 客户端返回码 %d" % code))
        if self.err_lines:
            sys.stderr.write("\n".join(self.err_lines) + "\n")


# ---------------------------------------------------------------------------
# 文件解析（全程流式，不把文件读进内存）
# ---------------------------------------------------------------------------
def list_push_files(dir_path):
    """列出推送目录下符合命名规则的文件，返回按 (日期, 文件名) 升序的 [(日期, 文件名)]。

    只看该目录下的普通文件，不递归子目录、不跟随符号链接。
    """
    out = []
    for name in os.listdir(dir_path):
        m = FILE_RE.match(name)
        if m and os.path.isfile(os.path.join(dir_path, name)):
            out.append((m.group(1), name))
    out.sort()                              # 按 (日期, 文件名) 升序，最后一个即最新
    return out


def find_latest_file(dir_path):
    """在推送目录中找 t101_sz_hjy_sxd_profile_YYYYMMDD_0001 文件，取日期最新一份。"""
    candidates = list_push_files(dir_path)
    return os.path.join(dir_path, candidates[-1][1]) if candidates else None


def cleanup_old_files(dir_path, keep_name, keep_count=CLEANUP_KEEP_COUNT, dry_run=False):
    """删除推送目录中较早的推送文件，只保留最近 keep_count 份（外加本次要入数的那个）。

    排序按**文件名里的日期**，不看 mtime（NAS 挂载 / rsync / 复制都会改 mtime，按它会误删）。
      例：keep_count=7 且目录里有 10 份 → 保留日期最新的 7 份，删除更早的 3 份。
      keep_count=0 即只保留本次要入数的那一份（等同于"只留最新"）。

    安全约束（删除属破坏性操作，务必保守）：
      - 只处理该目录下的普通文件，不递归、不动子目录
      - 只删整名完全吻合推送规范的文件（FILE_RE）；同前缀的 .bak/.tmp/写一半的文件一律不碰
      - keep_name（本次要入数的文件）永不删除，哪怕它不在"最近 N 份"之内；
        若其命名不完全规范则整个清理直接放弃
      - 文件名日期解析不出来的文件一律不删，也不占用"最近 N 份"的名额
      - 单个文件删除失败只告警不中断，绝不影响本次入数
      - dry_run=True 时只打印将要删除/保留的内容，不真删
    """
    if not FILE_RE.match(keep_name):
        log("清理放弃：待保留文件命名不完全符合推送规范，为安全起见不删任何文件：%s" % keep_name)
        return 0

    entries = list_push_files(dir_path)      # [(date_str, name)]，已按 (日期, 文件名) 升序
    dated = []                               # [(日期, 文件名)]，只含日期能解析的
    undated = []                             # 日期解析不出来的：永不删除，也不占保留名额
    for date_str, name in entries:
        try:
            dated.append((datetime.strptime(date_str, "%Y%m%d").date(), name))
        except ValueError:
            undated.append(name)
            log("清理跳过：文件名日期无法解析（不删除）：%s" % name)
    dated.sort()

    keep = {name for _date, name in dated[-keep_count:]} if keep_count > 0 else set()
    keep.add(keep_name)                      # 本次入数的文件永不删
    keep.update(undated)

    removed = 0
    skipped = 0
    for _date, name in entries:
        path = os.path.join(dir_path, name)
        if name in keep:
            if dry_run:
                log("[dry-run] 保留：%s" % name)
            continue
        if not FILE_RE.match(name):          # 兜底：将来若放宽了匹配，这里仍只删规范命名
            log("清理跳过非标准命名文件（不删除）：%s" % name)
            skipped += 1
            continue
        try:
            size = os.path.getsize(path) / 1024.0
        except OSError:
            size = 0.0
        if dry_run:
            log("[dry-run] 将删除历史文件：%s（%.1f KB）" % (name, size))
            removed += 1
            continue
        try:
            os.remove(path)
        except OSError as e:
            log("删除失败（跳过，不影响本次入数）：%s -> %s" % (name, e))
            skipped += 1
            continue
        log("已删除历史文件：%s（%.1f KB）" % (name, size))
        removed += 1

    extra = "，另有 %d 个日期异常文件不删" % len(undated) if undated else ""
    tail = ("，跳过 %d 个非标准/失败文件" % skipped) if skipped else ""
    if dry_run:
        log("清理预演（保留最近 %d 份 + 本次入数的那份%s）：将删除 %d 个，保留 %d 个"
            % (keep_count, extra, removed, len(keep)))
    else:
        log("清理完成（保留最近 %d 份 + 本次入数的那份%s）：删除 %d 个历史文件，保留 %d 个%s"
            % (keep_count, extra, removed, len(keep), tail))
    return removed


def cleanup_push_dir(args, csv_path):
    """按参数决定是否清理；返回删除个数。"""
    if args.csv:
        if not args.no_cleanup:
            log("本次用 --csv 指定文件，跳过历史文件清理（需要清理请改用 --dir）")
        return 0
    if args.no_cleanup:
        log("已指定 --no-cleanup，保留历史推送文件")
        return 0
    if args.limit:
        log("本次带 --limit（冒烟测试），跳过历史文件清理，避免误删数据")
        return 0
    if not os.path.isdir(args.dir):
        log("清理跳过：目录不存在 %s" % args.dir)
        return 0
    return cleanup_old_files(args.dir, os.path.basename(csv_path),
                             keep_count=args.keep_count, dry_run=args.dry_run)


def sql_quote(v):
    """把单个字段值转成 SQL 字面量；空字段一律写成 NULL。"""
    v = v.strip()
    if v == "":
        return "NULL"
    # 转义反斜杠与单引号，避免破坏 SQL / 注入
    v = v.replace("\\", "\\\\").replace("'", "''")
    return "'" + v + "'"


def sql_string(raw):
    """把普通字符串转成 SQL 字面量（不做空值判断，用于文件路径等）。"""
    return "'" + raw.replace("\\", "\\\\").replace("'", "''") + "'"


def iter_rows(csv_path, stats):
    """逐行读取 CSV，产出按表列顺序排列的 48 个字段值（已去掉 etl_dt）。

    生成器实现：内存占用与文件大小无关，只与单行大小有关。
    """
    with open(csv_path, "r", encoding="utf-8", buffering=1 << 20, newline="") as f:
        for lineno, raw in enumerate(f, 1):
            ln = raw.rstrip("\r\n")             # 兼容 CRLF
            if not ln.strip():
                continue
            vals = ln.split("|@|")
            if len(vals) != RAW_FIELDS:
                if stats.skip_bad:
                    stats.bad += 1
                    log("跳过第 %d 行：字段数应为 %d，实际 %d" % (lineno, RAW_FIELDS, len(vals)))
                    continue
                raise ValueError("第 %d 行字段数应为 %d，实际 %d：%r"
                                 % (lineno, RAW_FIELDS, len(vals), ln[:80]))
            del vals[1]                          # 去掉 etl_dt（目标表无此列）
            stats.rows += 1
            yield vals


def quote_row(vals):
    return "(" + ", ".join(sql_quote(v) for v in vals) + ")"


def iter_batches(csv_path, batch_size, max_bytes, stats, limit=0):
    """把行流切成批次：同时受『行数上限』与『单批 SQL 字节上限』约束。

    字节上限用来防 mysql "packet too large"（宽字段行可达数 KB）。
    注意这里必须按 UTF-8 **字节数**统计，中文一个字符占 3 字节，用字符数会低估约 3 倍。
    yield 的是已转义好的行片段列表，单批内存有界（<= max_bytes 量级）。
    """
    base = len(INSERT_HEAD.encode("utf-8")) + len(INSERT_TAIL.encode("utf-8"))
    buf = []
    size = base
    for vals in iter_rows(csv_path, stats):
        if limit and stats.rows > limit:         # 只导前 limit 行（冒烟测试用）
            stats.rows -= 1
            break
        t = quote_row(vals)
        add = len(t.encode("utf-8")) + 2         # 逗号 + 换行
        if buf and (len(buf) >= batch_size or size + add > max_bytes):
            yield buf
            buf = []
            size = base
        elif not buf and size + add > max_bytes:
            log("第 %d 行单行即超过单批字节上限(%d)，该批将单独成批" % (stats.rows, max_bytes))
        buf.append(t)
        size += add
    if buf:
        yield buf


def build_insert_sql(row_fragments):
    return INSERT_HEAD + ",\n".join(row_fragments) + INSERT_TAIL


# ---------------------------------------------------------------------------
# 导入实现
# ---------------------------------------------------------------------------
def run_import_stream(args, csv_path):
    """默认模式：流式分批 INSERT ... ON DUPLICATE KEY UPDATE。"""
    stats = Stats(skip_bad=args.skip_bad_lines)
    budget = args.max_batch_bytes

    runner = None
    if not args.dry_run:
        server_max = query_variable("max_allowed_packet")
        try:
            server_max = int(server_max)
        except (TypeError, ValueError):
            server_max = None
        if server_max and budget > server_max * 0.8:
            budget = int(server_max * 0.8)
            log("按服务端 max_allowed_packet=%d 自动收紧单批上限为 %d 字节"
                % (server_max, budget))
        runner = MysqlRunner()
        runner.write("SET NAMES utf8mb4;\n")
        log("开始导入：%s（单批 <= %d 行 / %d 字节）" % (csv_path, args.batch_size, budget))
    else:
        log("dry-run：解析 %s（单批 <= %d 行 / %d 字节）" % (csv_path, args.batch_size, budget))

    batches = 0
    printed = False
    last_log = 0
    t0 = time.time()
    err = None
    try:
        for row_fragments in iter_batches(csv_path, args.batch_size, budget, stats,
                                          limit=args.limit):
            batches += 1
            sql = build_insert_sql(row_fragments)
            if runner is not None:
                runner.write(sql)
            elif not printed:
                sys.stdout.write(sql + "\n")
                printed = True
            if stats.rows - last_log >= args.log_every:
                last_log = stats.rows
                log("已处理 %d 行（%d 批），耗时 %.1fs" % (stats.rows, batches, time.time() - t0))
    except BaseException as e:                   # noqa: BLE001 - 需要把错误和收尾合并处理
        err = e

    if runner is not None:
        try:
            runner.close()
        except MysqlError as e:
            if err is None:
                err = e
    if err is not None:
        raise err

    dt = time.time() - t0
    if args.dry_run:
        log("dry-run 完成：共 %d 行 / %d 批（仅打印第 1 批 SQL），跳过坏行 %d"
            % (stats.rows, batches, stats.bad))
    else:
        log("已 upsert %d 行到 %s.%s（%d 批，跳过坏行 %d，耗时 %.1fs）"
            % (stats.rows, DB_NAME, TABLE, batches, stats.bad, dt))
    return stats.rows


def count_lines(csv_path):
    """快速统计文件行数（二进制分块扫描，内存恒定），仅用于 --load-data 估算分片。"""
    n = 0
    with open(csv_path, "rb", buffering=1 << 20) as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            n += chunk.count(b"\n")
    return n


def run_import_load_data(args, csv_path):
    """--load-data 模式：mysql 客户端直接读文件灌临时表，Python 不解析行。"""
    if args.limit:
        raise ValueError("--load-data 模式不支持 --limit")

    local_infile = query_variable("local_infile")
    if local_infile is not None and local_infile.upper() not in ("ON", "1", "TRUE"):
        raise ValueError(
            "服务端 local_infile=%s，--load-data 需要服务端开启 local_infile=ON"
            "（或去掉 --load-data 走默认流式模式）" % local_infile)

    approx = count_lines(csv_path)
    runner = MysqlRunner(extra_args=("--local-infile=1",))
    err = None
    t0 = time.time()
    try:
        runner.write("SET NAMES utf8mb4;\n")
        # 1) 建会话级临时表（下会话即消失，不污染库结构）：49 个 TEXT + 自增行号
        runner.write("DROP TEMPORARY TABLE IF EXISTS `%s`;\n" % STAGING_TABLE)
        runner.write(
            "CREATE TEMPORARY TABLE `%s` (\n"
            "  `_rid` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,\n"
            "%s\n"
            "  PRIMARY KEY (`_rid`)\n"
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
            % (STAGING_TABLE, ",\n".join("  `%s` TEXT NULL" % c for c in STAGING_COLS)))

        # 2) LOAD DATA LOCAL INFILE：字段用 |@| 分隔，行以 \n 结束；
        #    空串统一置 NULL，末列额外去掉 CRLF 残留的 \r，保持与原逻辑一致的空值语义
        assigns = []
        for i, col in enumerate(STAGING_COLS):
            if i == RAW_FIELDS - 1:
                assigns.append("`%s`=NULLIF(TRIM(TRAILING '\\r' FROM @v%d), '')" % (col, i + 1))
            else:
                assigns.append("`%s`=NULLIF(@v%d, '')" % (col, i + 1))
        runner.write(
            "LOAD DATA LOCAL INFILE %s INTO TABLE `%s`\n"
            "CHARACTER SET utf8mb4\n"
            "FIELDS TERMINATED BY '|@|'\n"
            "LINES TERMINATED BY '\\n'\n"
            "(%s)\n"
            "SET %s;\n"
            % (sql_string(csv_path), STAGING_TABLE,
               ", ".join("@v%d" % (i + 1) for i in range(RAW_FIELDS)),
               ", ".join(assigns)))
        log("LOAD DATA 已提交（约 %d 行），开始分批刷入目标表" % approx)

        # 3) 分批 INSERT ... SELECT ... ON DUPLICATE KEY UPDATE，避免一个巨型事务
        chunk = args.chunk_rows
        start = 0
        chunks = 0
        while start < approx:
            end = start + chunk
            runner.write(
                "INSERT INTO `%s` (%s)\nSELECT %s FROM `%s` WHERE `_rid` > %d AND `_rid` <= %d\n"
                "ON DUPLICATE KEY UPDATE\n%s;\n"
                % (TABLE, COL_SQL, ", ".join(TARGET_FROM_STAGING), STAGING_TABLE,
                   start, end, UPDATE_SQL))
            chunks += 1
            start = end
            log("已刷入约 %d / %d 行，耗时 %.1fs" % (min(start, approx), approx, time.time() - t0))
        # 收尾：文件末尾无换行符时行数会少算 1，用无上限分片兜底
        runner.write(
            "INSERT INTO `%s` (%s)\nSELECT %s FROM `%s` WHERE `_rid` > %d\n"
            "ON DUPLICATE KEY UPDATE\n%s;\n"
            % (TABLE, COL_SQL, ", ".join(TARGET_FROM_STAGING), STAGING_TABLE, start, UPDATE_SQL))
        runner.write("DROP TEMPORARY TABLE IF EXISTS `%s`;\n" % STAGING_TABLE)
    except BaseException as e:                   # noqa: BLE001
        err = e

    try:
        runner.close()
    except MysqlError as e:
        if err is None:
            err = e
    if err is not None:
        raise err

    log("--load-data 完成：约 %d 行已 upsert 到 %s.%s（%d 片，耗时 %.1fs）"
        % (approx, DB_NAME, TABLE, chunks + 1, time.time() - t0))
    return approx


def resolve_csv_path(args):
    """根据 --csv / --dir 解析出本次要导入的文件；找不到则抛 ValueError。"""
    if args.csv:
        csv_path = args.csv
        if not os.path.isfile(csv_path):
            raise ValueError("找不到文件：%s" % csv_path)
    else:
        if not os.path.isdir(args.dir):
            raise ValueError("推送目录不存在：%s" % args.dir)
        csv_path = find_latest_file(args.dir)
        if csv_path is None:
            raise ValueError("目录 %s 下未找到 t101_sz_hjy_sxd_profile_YYYYMMDD_ 推送文件" % args.dir)
    return csv_path


def run_import(args):
    """执行一次导入，返回导入行数。

    清理历史推送文件的时机：
      - 默认：**入数前**清理，只保留本次要入数的最新文件
      - --cleanup-after：先入数，**成功之后**才清理（入数失败则历史文件保留，更稳）
    """
    csv_path = resolve_csv_path(args)
    if not args.cleanup_after:
        cleanup_push_dir(args, csv_path)         # 清理发生在入数之前
    if args.load_data:
        rows = run_import_load_data(args, csv_path)
    else:
        rows = run_import_stream(args, csv_path)
    if args.cleanup_after:
        # 能走到这里说明入数已成功；失败会抛异常，历史文件原样保留
        cleanup_push_dir(args, csv_path)
    return rows


def next_run_delay(hour, minute):
    """从现在到下一个 HH:MM 的秒数；今天该时刻已过则算到明天。"""
    now = datetime.now()
    target = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=1)
    return int((target - now).total_seconds())


def schedule_loop(args):
    """--schedule 模式：启动立即执行一次，此后每天 HH:MM 执行一次，常驻循环。"""
    print("进入 --schedule 模式：启动立即执行一次，之后每天 %02d:%02d 执行（Ctrl+C 退出）"
          % (args.hour, args.minute), file=sys.stderr)
    while True:
        try:
            run_import(args)
        except Exception as e:                   # 单次失败只记日志，不退出循环
            log("本次执行失败：%s" % e)

        delay = next_run_delay(args.hour, args.minute)
        nxt = (datetime.now() + timedelta(seconds=delay)).strftime("%Y-%m-%d %H:%M:%S")
        log("下次执行时间：%s（%d 秒后）" % (nxt, delay))
        time.sleep(delay)


def main():
    ap = argparse.ArgumentParser(
        description="导入每日推送数据到 kjjr_ai_sxd_profile（按 cst_id upsert，流式分批、内存恒定）")
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--dir", default=here,
                    help="每日推送文件目录（默认：脚本所在目录）")
    ap.add_argument("--csv", default=None,
                    help="指定单个文件路径（跳过按日期自动扫描）")
    ap.add_argument("--dry-run", action="store_true",
                    help="只解析并打印第 1 批 SQL，不连库、不写入")
    ap.add_argument("--schedule", action="store_true",
                    help="自循环定时模式：启动执行一次，之后每天 --hour:--minute 执行，常驻")
    ap.add_argument("--hour", type=int, choices=range(24), default=6,
                    help="定时执行小时（默认 6，即每天凌晨 6 点）")
    ap.add_argument("--minute", type=int, choices=range(60), default=0,
                    help="定时执行分钟（默认 0）")
    ap.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE,
                    help="单批最多行数（默认 %d）" % DEFAULT_BATCH_SIZE)
    ap.add_argument("--max-batch-bytes", type=int, default=DEFAULT_MAX_BATCH_BYTES,
                    help="单批 SQL 字节上限，防 packet too large（默认 %d）" % DEFAULT_MAX_BATCH_BYTES)
    ap.add_argument("--log-every", type=int, default=DEFAULT_LOG_EVERY,
                    help="每处理多少行打一条进度日志（默认 %d）" % DEFAULT_LOG_EVERY)
    ap.add_argument("--skip-bad-lines", action="store_true",
                    help="字段数异常的行跳过并告警，而不是中止（默认中止）")
    ap.add_argument("--limit", type=int, default=0,
                    help="只导入前 N 行（0=全部，冒烟测试用）")
    ap.add_argument("--load-data", action="store_true",
                    help="大文件快速通道：用 LOAD DATA LOCAL INFILE 灌临时表再刷入"
                         "（需服务端 local_infile=ON）")
    ap.add_argument("--chunk-rows", type=int, default=DEFAULT_CHUNK_ROWS,
                    help="--load-data 时每个 INSERT...SELECT 刷入的最大行数（默认 %d）"
                         % DEFAULT_CHUNK_ROWS)
    ap.add_argument("--no-cleanup", action="store_true",
                    help="保留历史推送文件，不清理（默认：入数前只保留最近 %d 份推送文件）"
                         % CLEANUP_KEEP_COUNT)
    ap.add_argument("--keep-count", type=int, default=CLEANUP_KEEP_COUNT,
                    help="清理时只保留最近多少份推送文件，更早的删除（默认 %d；"
                         "0=只保留本次入数的那份）" % CLEANUP_KEEP_COUNT)
    ap.add_argument("--cleanup-after", action="store_true",
                    help="改为入数成功后再清理历史文件（入数失败则历史文件保留，更稳妥）")
    args = ap.parse_args()

    if args.batch_size < 1 or args.chunk_rows < 1 or args.log_every < 1:
        sys.exit("--batch-size / --chunk-rows / --log-every 必须为正整数")
    if args.keep_count < 0:
        sys.exit("--keep-count 不能为负")
    if args.no_cleanup and args.cleanup_after:
        sys.exit("--no-cleanup 与 --cleanup-after 不能同时使用")

    if args.schedule:
        if args.dry_run:
            sys.exit("--schedule 与 --dry-run 不能同时使用")
        schedule_loop(args)
    else:
        try:
            run_import(args)
        except (ValueError, MysqlError) as e:
            sys.exit(str(e))


if __name__ == "__main__":
    main()
