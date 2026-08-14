#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从每日推送的数据文件读取客户信息，写入数据库表 kjjr_ai_sxd_profile。

数据文件命名：t101_sz_hjy_sxd_profile_YYYYMMDD_0001.csv（文件名内嵌日期 YYYYMMDD），
每天推送新文件，脚本自动扫描目录、按文件名中的日期取最新一份。

运行环境：Python 3 标准库 + mysql 客户端（mariadb-client / mysql-client 均可，兼容 MariaDB 10.3+），
不依赖任何第三方 Python 包。

用法：
    python3 insert_sxd_profile.py                  # 单次执行：扫描默认目录，读最新文件并 upsert
    python3 insert_sxd_profile.py --dir /path      # 指定每日文件推送目录
    python3 insert_sxd_profile.py --csv <路径>     # 指定单个文件（跳过自动扫描）
    python3 insert_sxd_profile.py --dry-run        # 只打印 SQL，不执行
    python3 insert_sxd_profile.py --schedule       # 自循环定时模式（容器/常驻场景）

--schedule 自循环模式（容器内推荐，无需 cron/systemd/root）：
    启动后立即执行一次，之后每天 --hour:--minute 执行一次，循环常驻，进程存活期间定时必触发。
    容器部署：在 /starts.sh 中后台拉起，进程随容器存活：
        nohup python3 insert_sxd_profile.py --schedule --dir /app/push >> /app/insert.log 2>&1 &
    注意：
      - 容器时区须正确（如 TZ=Asia/Shanghai），否则按 UTC 计算会差 8 小时
      - 镜像内需安装 python3 与 mysql 客户端；每日推送目录要挂卷进容器
      - 完整部署步骤见 docs/部署说明.md

CSV 约定：UTF-8 编码、无标题行、每行 49 个字段以 |@| 分隔。
  - 第 2 列 etl_dt 不属于目标表，脚本会自动跳过
  - 日期字段（data_bsn_dt、fd_dt、dep_bal_dt、acc_start_dt）均为 yyyy-mm-dd，直接入库

批量更新原则（upsert，主键 cst_id，兼容 MariaDB 10.3+）：
  1. cst_id 在表中已存在 -> 更新该行
  2. cst_id 在表中不存在 -> 插入新行
  实现：INSERT ... ON DUPLICATE KEY UPDATE

数据库连接信息直接写死在脚本下方 DB_* 常量中，部署时按实际环境修改即可（无需 Docker 环境变量）。
"""

import os
import re
import sys
import time
import argparse
import subprocess
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

# 每日推送文件名（新格式）：t101_sz_hjy_sxd_profile_YYYYMMDD_0001.csv，日期在内嵌位置
FILE_RE = re.compile(r"^t101_sz_hjy_sxd_profile_(\d{8})_\d+")

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


def find_latest_file(dir_path):
    """在推送目录中找 t101_sz_hjy_sxd_profile_YYYYMMDD_0001 文件，取日期最新一份。"""
    candidates = []
    for name in os.listdir(dir_path):
        m = FILE_RE.match(name)
        if m and os.path.isfile(os.path.join(dir_path, name)):
            candidates.append((m.group(1), name))
    if not candidates:
        return None
    candidates.sort()                       # 按 (日期, 文件名) 升序，取最后一个即最新
    return os.path.join(dir_path, candidates[-1][1])


def sql_quote(v):
    """把单个字段值转成 SQL 字面量；空字段一律写成 NULL。"""
    v = v.strip()
    if v == "":
        return "NULL"
    # 转义反斜杠与单引号，避免破坏 SQL / 注入
    v = v.replace("\\", "\\\\").replace("'", "''")
    return "'" + v + "'"


def load_rows(csv_path):
    """读取 CSV，返回按表列顺序排列的 48 个字段值列表（已去掉 etl_dt）。"""
    with open(csv_path, "r", encoding="utf-8") as f:
        lines = [ln.rstrip("\n") for ln in f if ln.strip() != ""]

    rows = []
    for ln in lines:
        vals = ln.split("|@|")
        if len(vals) != 49:
            raise ValueError("行字段数应为 49，实际 %d：%r" % (len(vals), ln[:80]))
        del vals[1]                          # 去掉 etl_dt（目标表无此列）
        assert len(vals) == len(COLUMNS)
        rows.append(vals)
    return rows


def build_sql(rows):
    """生成 INSERT ... ON DUPLICATE KEY UPDATE，按主键 cst_id upsert。"""
    col_sql = ", ".join("`%s`" % c for c in COLUMNS)
    tuples = []
    for vals in rows:
        quoted = [sql_quote(v) for v in vals]
        tuples.append("(" + ", ".join(quoted) + ")")
    # 更新字段 = 除主键 cst_id 外的所有列；冲突时整行刷新
    update_cols = [c for c in COLUMNS if c != "cst_id"]
    update_sql = ", ".join("`%s`=VALUES(`%s`)" % (c, c) for c in update_cols)
    return ("INSERT INTO `%s` (%s) VALUES\n%s\n"
            "ON DUPLICATE KEY UPDATE\n%s;" % (
                TABLE, col_sql, ",\n".join(tuples), update_sql))


def run_mysql(sql):
    """通过 mysql 客户端执行 SQL；密码走 MYSQL_PWD 环境变量，避免暴露在进程列表。"""
    env = dict(os.environ)
    env["MYSQL_PWD"] = DB_PASS
    cmd = [
        "mysql", "-h", DB_HOST, "-P", DB_PORT, "-u", DB_USER,
        "--default-character-set=utf8mb4", "--batch", DB_NAME,
    ]
    proc = subprocess.run(cmd, input=sql.encode("utf-8"),
                          capture_output=True, env=env)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout.decode("utf-8", "replace") + "\n")
        sys.stderr.write(proc.stderr.decode("utf-8", "replace") + "\n")
        sys.exit("mysql 执行失败（返回码 %d）" % proc.returncode)
    if proc.stderr.strip():
        sys.stderr.write(proc.stderr.decode("utf-8", "replace") + "\n")


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
    """执行一次导入：找最新文件、生成 SQL、执行（或 --dry-run 只打印），返回导入行数。"""
    csv_path = resolve_csv_path(args)
    rows = load_rows(csv_path)
    sql = build_sql(rows)
    print("读取文件：%s（共 %d 行）" % (csv_path, len(rows)), file=sys.stderr)
    if args.dry_run:
        print(sql)
        return len(rows)
    run_mysql("SET NAMES utf8mb4;\n" + sql)
    print("已 upsert %d 行到 %s.%s（cst_id 存在则更新，不存在则插入）" % (
        len(rows), DB_NAME, TABLE))
    return len(rows)


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
        except SystemExit:
            # run_mysql 等已输出错误信息，仅跳过本轮继续等待，不退出循环
            pass
        except Exception as e:
            print("本次执行失败：%s" % e, file=sys.stderr)

        delay = next_run_delay(args.hour, args.minute)
        nxt = (datetime.now() + timedelta(seconds=delay)).strftime("%Y-%m-%d %H:%M:%S")
        print("下次执行时间：%s（%d 秒后）" % (nxt, delay), file=sys.stderr)
        time.sleep(delay)


def main():
    ap = argparse.ArgumentParser(
        description="导入每日推送数据到 kjjr_ai_sxd_profile（按 cst_id upsert）")
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--dir", default=here,
                    help="每日推送文件目录（默认：脚本所在目录）")
    ap.add_argument("--csv", default=None,
                    help="指定单个文件路径（跳过按日期自动扫描）")
    ap.add_argument("--dry-run", action="store_true", help="只打印 SQL，不执行")
    ap.add_argument("--schedule", action="store_true",
                    help="自循环定时模式：启动执行一次，之后每天 --hour:--minute 执行，常驻")
    ap.add_argument("--hour", type=int, choices=range(24), default=2,
                    help="定时执行小时（默认 2）")
    ap.add_argument("--minute", type=int, choices=range(60), default=0,
                    help="定时执行分钟（默认 0）")
    args = ap.parse_args()

    if args.schedule:
        if args.dry_run:
            sys.exit("--schedule 与 --dry-run 不能同时使用")
        schedule_loop(args)
    else:
        try:
            run_import(args)
        except ValueError as e:
            sys.exit(str(e))


if __name__ == "__main__":
    main()
