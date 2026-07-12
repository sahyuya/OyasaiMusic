package com.github.sahyuya.oyasaiMusic.db

import com.github.sahyuya.oyasaiMusic.model.UserRewardData
import com.github.sahyuya.oyasaiMusic.util.UuidUtil
import java.util.UUID

/**
 * users テーブル（個人ステータス・未受取報酬）へのアクセスを担当するリポジトリ。
 * データ・システム設計書 1-2章: オンライン・オフライン問わず加算され、
 * メインメニューからの一括受取アクションでリセットされる。
 */
class UserRepository(private val db: DatabaseManager) {

    fun get(uuid: UUID): UserRewardData = db.transaction { conn ->
        conn.prepareStatement("SELECT * FROM users WHERE uuid = ?").use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(uuid))
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    UserRewardData(
                        uuid = uuid,
                        pendingMoney = rs.getLong("pending_money"),
                        pendingPoints = rs.getLong("pending_points"),
                    )
                } else {
                    UserRewardData(uuid = uuid)
                }
            }
        }
    }

    private fun ensureRow(conn: java.sql.Connection, uuid: UUID) {
        conn.prepareStatement(
            "INSERT INTO users (uuid, pending_money, pending_points) VALUES (?, 0, 0) " +
                    "ON CONFLICT(uuid) DO NOTHING"
        ).use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(uuid))
            ps.executeUpdate()
        }
    }

    /** いいね送信報酬・視聴ポイント等、未受取残高への加算。金額とポイントは同時または個別に指定可能。 */
    fun addPending(uuid: UUID, money: Long = 0, points: Long = 0) = db.transaction { conn ->
        ensureRow(conn, uuid)
        conn.prepareStatement(
            "UPDATE users SET pending_money = pending_money + ?, pending_points = pending_points + ? WHERE uuid = ?"
        ).use { ps ->
            ps.setLong(1, money)
            ps.setLong(2, points)
            ps.setBytes(3, UuidUtil.toBytes(uuid))
            ps.executeUpdate()
        }
    }

    /**
     * 未受取報酬を一括受取（取得すると同時に0へリセット）する。
     * メインメニューの「未受取報酬の一括受取ボタン」から呼び出される想定。
     *
     * @return 受取前の残高
     */
    fun claim(uuid: UUID): UserRewardData = db.transaction { conn ->
        ensureRow(conn, uuid)
        val current = conn.prepareStatement("SELECT * FROM users WHERE uuid = ?").use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(uuid))
            ps.executeQuery().use { rs ->
                rs.next()
                UserRewardData(
                    uuid = uuid,
                    pendingMoney = rs.getLong("pending_money"),
                    pendingPoints = rs.getLong("pending_points"),
                )
            }
        }
        conn.prepareStatement(
            "UPDATE users SET pending_money = 0, pending_points = 0 WHERE uuid = ?"
        ).use { ps ->
            ps.setBytes(1, UuidUtil.toBytes(uuid))
            ps.executeUpdate()
        }
        current
    }
}
