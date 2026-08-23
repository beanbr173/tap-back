package com.kreativesolutions.tapback.api

data class DeviceSession(
    val deviceId: String,
    val deviceSecret: String,
    val displayName: String
)

data class PairInfo(
    val pairId: String,
    val partnerId: String,
    val partnerName: String
)

data class MeSnapshot(
    val deviceId: String,
    val displayName: String,
    val pair: PairInfo?
)

data class AlertLog(
    val id: String,
    val pairId: String,
    val senderId: String,
    val receiverId: String,
    val kind: String,
    val sentAt: Long,
    val receivedAt: Long?,
    val ackedAt: Long?
) {
    val isAcked: Boolean get() = ackedAt != null
}

data class ScheduleItem(
    val id: String,
    val pairId: String,
    val hour: Int,
    val minute: Int,
    val timezone: String,
    val days: List<Int>,
    val enabled: Boolean
)

data class InviteCode(
    val code: String,
    val expiresAt: Long
)
