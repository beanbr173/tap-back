package com.kreativesolutions.tapback.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TapBackApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun registerDevice(
        baseUrl: String,
        displayName: String,
        fcmToken: String?
    ): DeviceSession = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("displayName", displayName)
            .put("fcmToken", fcmToken ?: JSONObject.NULL)
        val json = post(baseUrl, "/v1/devices", body, auth = null)
        DeviceSession(
            deviceId = json.getString("deviceId"),
            deviceSecret = json.getString("deviceSecret"),
            displayName = json.getString("displayName")
        )
    }

    suspend fun updateDevice(
        baseUrl: String,
        auth: DeviceSession,
        displayName: String? = null,
        fcmToken: String? = null
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (displayName != null) body.put("displayName", displayName)
        if (fcmToken != null) body.put("fcmToken", fcmToken)
        put(baseUrl, "/v1/devices/me", body, auth)
    }

    suspend fun createInvite(
        baseUrl: String,
        auth: DeviceSession,
        groupId: String? = null
    ): InviteCode =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            if (!groupId.isNullOrBlank()) body.put("groupId", groupId)
            val json = post(baseUrl, "/v1/invites", body, auth)
            InviteCode(
                code = json.getString("code"),
                expiresAt = json.getLong("expiresAt")
            )
        }

    suspend fun joinInvite(baseUrl: String, auth: DeviceSession, code: String): GroupInfo =
        withContext(Dispatchers.IO) {
            val json = post(
                baseUrl,
                "/v1/invites/join",
                JSONObject().put("code", code.trim().uppercase()),
                auth
            )
            json.optJSONObject("group")?.let { parseGroup(it) }
                ?: json.optJSONObject("pair")?.let { pairToGroup(it) }
                ?: error("Join did not return a family.")
        }

    suspend fun me(baseUrl: String, auth: DeviceSession): MeSnapshot =
        withContext(Dispatchers.IO) {
            val json = get(baseUrl, "/v1/me", auth)
            val groupsJson = json.optJSONArray("groups")
            val parsedGroups = if (groupsJson != null) {
                buildList {
                    for (i in 0 until groupsJson.length()) {
                        add(parseGroup(groupsJson.getJSONObject(i)))
                    }
                }
            } else {
                emptyList()
            }
            val groupObj = json.optJSONObject("group")
            val pairObj = json.optJSONObject("pair")
            val fallback = groupObj?.let { parseGroup(it) } ?: pairObj?.let { pairToGroup(it) }
            val groups = parsedGroups.ifEmpty { listOfNotNull(fallback) }
            MeSnapshot(
                deviceId = json.getJSONObject("device").getString("id"),
                displayName = json.getJSONObject("device").getString("displayName"),
                groups = groups,
                group = groups.firstOrNull(),
                pair = pairObj?.let { parsePair(it) }
            )
        }

    suspend fun sendAlert(
        baseUrl: String,
        auth: DeviceSession,
        groupId: String,
        receiverId: String? = null
    ): AlertLog = withContext(Dispatchers.IO) {
        val body = JSONObject().put("groupId", groupId).put("pairId", groupId)
        if (!receiverId.isNullOrBlank()) body.put("receiverId", receiverId)
        val json = post(baseUrl, "/v1/alerts", body, auth)
        parseAlert(json.getJSONObject("alert"))
    }

    suspend fun markReceived(baseUrl: String, auth: DeviceSession, alertId: String) =
        withContext(Dispatchers.IO) {
            post(baseUrl, "/v1/alerts/$alertId/received", JSONObject(), auth)
        }

    suspend fun ackAlert(baseUrl: String, auth: DeviceSession, alertId: String): AlertLog =
        withContext(Dispatchers.IO) {
            val json = post(baseUrl, "/v1/alerts/$alertId/ack", JSONObject(), auth)
            parseAlert(json.getJSONObject("alert"))
        }

    suspend fun listAlerts(baseUrl: String, auth: DeviceSession): List<AlertLog> =
        withContext(Dispatchers.IO) {
            val json = get(baseUrl, "/v1/alerts", auth)
            parseAlerts(json.getJSONArray("alerts"))
        }

    suspend fun listSchedules(baseUrl: String, auth: DeviceSession): List<ScheduleItem> =
        withContext(Dispatchers.IO) {
            val json = get(baseUrl, "/v1/schedules", auth)
            parseSchedules(json.getJSONArray("schedules"))
        }

    suspend fun createSchedule(
        baseUrl: String,
        auth: DeviceSession,
        pairId: String,
        hour: Int,
        minute: Int,
        timezone: String,
        days: List<Int>,
        receiverId: String? = null
    ): ScheduleItem = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pairId", pairId)
            .put("groupId", pairId)
            .put("hour", hour)
            .put("minute", minute)
            .put("timezone", timezone)
            .put("days", JSONArray(days))
        if (!receiverId.isNullOrBlank()) body.put("receiverId", receiverId)
        val json = post(baseUrl, "/v1/schedules", body, auth)
        parseSchedule(json.getJSONObject("schedule"))
    }

    suspend fun setScheduleEnabled(
        baseUrl: String,
        auth: DeviceSession,
        scheduleId: String,
        enabled: Boolean
    ) = withContext(Dispatchers.IO) {
        put(
            baseUrl,
            "/v1/schedules/$scheduleId",
            JSONObject().put("enabled", enabled),
            auth
        )
    }

    suspend fun deleteSchedule(baseUrl: String, auth: DeviceSession, scheduleId: String) =
        withContext(Dispatchers.IO) {
            delete(baseUrl, "/v1/schedules/$scheduleId", auth)
        }

    suspend fun unlink(baseUrl: String, auth: DeviceSession, groupId: String? = null) =
        withContext(Dispatchers.IO) {
            val path = if (groupId.isNullOrBlank()) "/v1/pairs/me" else "/v1/groups/$groupId"
            delete(baseUrl, path, auth)
        }

    private fun parsePair(json: JSONObject) = PairInfo(
        pairId = json.getString("id"),
        partnerId = json.getString("partnerId"),
        partnerName = json.getString("partnerName")
    )

    private fun pairToGroup(json: JSONObject) = GroupInfo(
        groupId = json.getString("id"),
        name = json.optString("partnerName").ifBlank { "Family" },
        inviteCode = "",
        members = listOf(
            Member(id = json.getString("partnerId"), displayName = json.getString("partnerName"))
        )
    )

    private fun parseGroup(json: JSONObject): GroupInfo {
        val membersJson = json.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (i in 0 until membersJson.length()) {
                val member = membersJson.getJSONObject(i)
                add(Member(id = member.getString("id"), displayName = member.getString("displayName")))
            }
        }
        val id = json.optString("id").ifBlank { json.optString("groupId") }
        return GroupInfo(
            groupId = id,
            name = json.optString("name"),
            inviteCode = json.optString("inviteCode"),
            members = members
        )
    }

    private fun parseAlert(json: JSONObject) = AlertLog(
        id = json.getString("id"),
        pairId = json.optString("groupId").ifBlank { json.getString("pairId") },
        senderId = json.getString("senderId"),
        receiverId = json.getString("receiverId"),
        senderName = json.optString("senderName"),
        receiverName = json.optString("receiverName"),
        kind = json.getString("kind"),
        sentAt = json.getLong("sentAt"),
        receivedAt = json.optLongOrNull("receivedAt"),
        ackedAt = json.optLongOrNull("ackedAt")
    )

    private fun parseAlerts(array: JSONArray) = buildList {
        for (i in 0 until array.length()) add(parseAlert(array.getJSONObject(i)))
    }

    private fun parseSchedule(json: JSONObject): ScheduleItem {
        val daysJson = json.optJSONArray("days") ?: JSONArray()
        val days = buildList {
            for (i in 0 until daysJson.length()) add(daysJson.getInt(i))
        }
        return ScheduleItem(
            id = json.getString("id"),
            pairId = json.optString("groupId").ifBlank { json.getString("pairId") },
            receiverId = json.optString("receiverId").takeIf { it.isNotBlank() && it != "null" },
            receiverName = json.optString("receiverName"),
            hour = json.getInt("hour"),
            minute = json.getInt("minute"),
            timezone = json.getString("timezone"),
            days = days,
            enabled = json.optBoolean("enabled", true)
        )
    }

    private fun parseSchedules(array: JSONArray) = buildList {
        for (i in 0 until array.length()) add(parseSchedule(array.getJSONObject(i)))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun get(baseUrl: String, path: String, auth: DeviceSession): JSONObject {
        return execute(
            Request.Builder()
                .url(url(baseUrl, path))
                .get()
                .apply { addAuth(auth) }
                .build()
        )
    }

    private fun post(
        baseUrl: String,
        path: String,
        body: JSONObject,
        auth: DeviceSession?
    ): JSONObject {
        val builder = Request.Builder()
            .url(url(baseUrl, path))
            .post(body.toString().toRequestBody(JSON))
        if (auth != null) builder.addAuth(auth)
        return execute(builder.build())
    }

    private fun put(
        baseUrl: String,
        path: String,
        body: JSONObject,
        auth: DeviceSession
    ): JSONObject {
        return execute(
            Request.Builder()
                .url(url(baseUrl, path))
                .put(body.toString().toRequestBody(JSON))
                .apply { addAuth(auth) }
                .build()
        )
    }

    private fun delete(baseUrl: String, path: String, auth: DeviceSession): JSONObject {
        return execute(
            Request.Builder()
                .url(url(baseUrl, path))
                .delete()
                .apply { addAuth(auth) }
                .build()
        )
    }

    private fun Request.Builder.addAuth(auth: DeviceSession): Request.Builder {
        return header("Authorization", "Bearer ${auth.deviceId}:${auth.deviceSecret}")
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    JSONObject(text).optString("error").ifBlank { null }
                } catch (_: Exception) {
                    null
                }
                error(message ?: "Request failed (HTTP ${response.code}).")
            }
            if (text.isBlank()) return JSONObject()
            return JSONObject(text)
        }
    }

    private fun url(baseUrl: String, path: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Server URL is not set. Open Settings and paste your Worker URL." }
        return normalized + path
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
