package com.ccb.techfin.dao.sxd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ccb.techfin.model.sxd.entity.ExtractData;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 提取数据缓存表 Mapper。
 *
 * <p>
 * 除了 {@link BaseMapper} 提供的通用 CRUD，另有两条自定义语句支撑「整体覆盖」写法：
 * {@link #upsert(ExtractData)} 与 {@link #deleteStaleByKeys(String, List)}。
 * 它们与表上的唯一键 {@code uk_task_doc_table(task_id, doc_id, table_name)} 配套使用，
 * 用于替代原先的「{@code DELETE ... WHERE task_id = ?} + 逐行 {@code INSERT}」。
 * </p>
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Mapper
public interface ExtractDataMapper extends BaseMapper<ExtractData> {

    /**
     * 按自然键 {@code (task_id, doc_id, table_name)} 做 upsert：命中则更新 {@code text}，未命中则插入。
     *
     * <p>
     * <b>依赖</b>表上的唯一键 {@code uk_task_doc_table} —— 没有它，{@code ON DUPLICATE KEY UPDATE}
     * 永远不触发，本条语句等价于纯 {@code INSERT}，并发下会产生重复行。
     * </p>
     *
     * <p>
     * ⚠️ <b>自定义 {@code @Insert} 不会触发 MyBatis-Plus 的自动填充与 ASSIGN_ID 发号</b>
     * （那两条逻辑挂在 MP 自己的 insert 语句上）。所以调用方必须先把以下三列填好：
     * </p>
     * <ul>
     *   <li>{@code id}：用 {@code IdWorker.getId()}。它取的正是
     *       {@code mybatis-plus.global-config.sequence.*} 配置的节点标识 —— MP 构建
     *       {@code SqlSessionFactory} 时会把该 generator 一并注入 {@code IdWorker} 的静态字段，
     *       因此自定义 SQL 里手工发号与 MP 自动发号<b>用的是同一个 Sequence</b>，集群下不会撞。</li>
     *   <li>{@code created_at} / {@code updated_at}：两列都是 {@code NOT NULL} 且建表无默认值，
     *       漏填会直接报 <b>1364</b>。这里只更新 {@code updated_at}，{@code created_at} 保留首次写入的时间。</li>
     * </ul>
     *
     * @param row 待写入的缓存行，调用前须已填好 id 与两个时间列
     * @return 受影响行数（MySQL 规定：插入记 1，命中更新记 2）
     */
    @Insert("INSERT INTO kjjr_ai_sxd_extract_data "
            + "(id, task_id, doc_id, table_name, `text`, created_at, updated_at) "
            + "VALUES (#{id}, #{taskId}, #{docId}, #{tableName}, #{text}, #{createdAt}, #{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE `text` = VALUES(`text`), updated_at = VALUES(updated_at)")
    int upsert(ExtractData row);

    /**
     * 删除该任务下「上一轮有、本轮不再产出」的残留行，以保持整体覆盖语义。
     *
     * <p>
     * 按 {@code (doc_id, table_name)} 的<b>差集</b>删除，<b>不是</b> {@code DELETE WHERE task_id} ——
     * 后者在缺索引时会全表加锁、并发重入时会与插入争抢同一片间隙锁。
     * 本语句的 {@code task_id} 条件命中 {@code uk_task_doc_table} 的最左前缀，
     * 只锁本任务的行。
     * </p>
     *
     * ⚠️ {@code keys} 不能为空，否则 {@code foreach} 会生成出 {@code NOT IN ()} 的非法语法。
     *
     * @param taskId 任务 ID
     * @param keys   本轮产出的行（只用到其中的 docId 与 tableName）
     * @return 被清理的残留行数
     */
    @Delete("<script>"
            + "DELETE FROM kjjr_ai_sxd_extract_data "
            + "WHERE task_id = #{taskId} "
            + "AND (doc_id, table_name) NOT IN "
            + "<foreach collection='keys' item='k' open='(' separator=',' close=')'>"
            + "(#{k.docId}, #{k.tableName})"
            + "</foreach>"
            + "</script>")
    int deleteStaleByKeys(@Param("taskId") String taskId, @Param("keys") List<ExtractData> keys);
}
