import { sendFcm } from "./fcm";
import type { Env } from "./types";

interface DeviceRow {
  id: string;
  secret_hash: string;
  display_name: string;
  fcm_token: string | null;
}

interface GroupRow {
  id: string;
}

interface AlertRow {
  id: string;
  pair_id: string;
  sender_id: string;
  receiver_id: string;
  kind: string;
  sent_at: number;
  received_at: number | null;
  acked_at: number | null;
}

interface ScheduleRow {
  id: string;
  pair_id: string;
  sender_id: string;
  receiver_id: string;
  hour: number;
  minute: number;
  timezone: string;
  days_mask: number;
  enabled: number;
  last_fired_date: string | null;
}

interface MemberRow {
  id: string;
  display_name: string;
  fcm_token: string | null;
}

const EVERYONE = "*";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS });
    }
    try {
      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";
      const response = await route(request, env, path);
      Object.entries(CORS).forEach(([key, value]) => response.headers.set(key, value));
      return response;
    } catch (error) {
      const message = error instanceof Error ? error.message : "Server error";
      const status = message === "Unauthorized" ? 401 : 400;
      return json({ error: message }, status);
    }
  },

  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    await fireDueSchedules(env);
  },
};

async function route(request: Request, env: Env, path: string): Promise<Response> {
  if (request.method === "GET" && path === "/health") {
    return json({ ok: true, name: "tapback-api" });
  }
  if (request.method === "POST" && path === "/v1/devices") {
    return registerDevice(request, env);
  }

  const device = await requireDevice(request, env);

  if (request.method === "PUT" && path === "/v1/devices/me") {
    return updateDevice(request, env, device);
  }
  if (request.method === "GET" && path === "/v1/me") {
    return getMe(env, device);
  }
  if (request.method === "POST" && path === "/v1/invites") {
    return createInvite(env, device);
  }
  if (request.method === "POST" && path === "/v1/invites/join") {
    return joinInvite(request, env, device);
  }
  if (request.method === "DELETE" && (path === "/v1/pairs/me" || path === "/v1/groups/me")) {
    return leaveGroup(env, device);
  }
  if (request.method === "POST" && path === "/v1/alerts") {
    return createAlert(request, env, device, "manual");
  }
  if (request.method === "GET" && path === "/v1/alerts") {
    return listAlerts(env, device);
  }
  const received = path.match(/^\/v1\/alerts\/([^/]+)\/received$/);
  if (request.method === "POST" && received) {
    return markReceived(env, device, received[1]);
  }
  const acked = path.match(/^\/v1\/alerts\/([^/]+)\/ack$/);
  if (request.method === "POST" && acked) {
    return ackAlert(env, device, acked[1]);
  }
  if (request.method === "GET" && path === "/v1/schedules") {
    return listSchedules(env, device);
  }
  if (request.method === "POST" && path === "/v1/schedules") {
    return createSchedule(request, env, device);
  }
  const scheduleId = path.match(/^\/v1\/schedules\/([^/]+)$/);
  if (request.method === "PUT" && scheduleId) {
    return updateSchedule(request, env, device, scheduleId[1]);
  }
  if (request.method === "DELETE" && scheduleId) {
    return deleteSchedule(env, device, scheduleId[1]);
  }
  return json({ error: "Not found" }, 404);
}

async function registerDevice(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const displayName = String(body.displayName || "").trim();
  if (!displayName) throw new Error("displayName is required.");
  const id = crypto.randomUUID();
  const secret = randomSecret();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO devices (id, secret_hash, display_name, fcm_token, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  )
    .bind(id, await sha256(secret), displayName, body.fcmToken ?? null, now, now)
    .run();
  return json({ deviceId: id, deviceSecret: secret, displayName });
}

async function updateDevice(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson(request);
  const displayName = body.displayName ? String(body.displayName).trim() : device.display_name;
  const fcmToken = body.fcmToken !== undefined ? body.fcmToken : device.fcm_token;
  await env.DB.prepare(
    `UPDATE devices SET display_name = ?, fcm_token = ?, updated_at = ? WHERE id = ?`
  )
    .bind(displayName, fcmToken, Date.now(), device.id)
    .run();
  return json({ ok: true });
}

async function getMe(env: Env, device: DeviceRow): Promise<Response> {
  const group = await serializeGroup(env, device.id);
  const others = group?.members ?? [];
  const first = others[0];
  return json({
    device: { id: device.id, displayName: device.display_name },
    group,
    pair: group && first
      ? { id: group.id, partnerId: first.id, partnerName: first.displayName }
      : null,
  });
}

async function createInvite(env: Env, device: DeviceRow): Promise<Response> {
  const group = await ensureGroup(env, device.id);
  const existing = await env.DB.prepare(
    `SELECT code, expires_at FROM invites
     WHERE group_id = ? AND (used_at IS NULL) AND expires_at > ?
     ORDER BY created_at DESC LIMIT 1`
  )
    .bind(group.id, Date.now())
    .first<{ code: string; expires_at: number }>();
  if (existing) {
    return json({ code: existing.code, expiresAt: existing.expires_at });
  }
  const code = randomCode();
  const now = Date.now();
  const expiresAt = now + 90 * 24 * 60 * 60 * 1000;
  await env.DB.prepare(
    `INSERT INTO invites (code, creator_id, group_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)`
  )
    .bind(code, device.id, group.id, now, expiresAt)
    .run();
  return json({ code, expiresAt });
}

async function joinInvite(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson(request);
  const code = String(body.code || "").trim().toUpperCase();
  const invite = await env.DB.prepare(
    `SELECT code, creator_id, group_id, expires_at FROM invites WHERE code = ?`
  )
    .bind(code)
    .first<{ code: string; creator_id: string; group_id: string | null; expires_at: number }>();
  if (!invite) throw new Error("That code was not found.");
  if (invite.expires_at < Date.now()) throw new Error("That code expired. Ask for a new one.");
  if (invite.creator_id === device.id) throw new Error("You cannot join your own code.");

  let groupId = invite.group_id;
  if (!groupId) {
    groupId = (await ensureGroup(env, invite.creator_id)).id;
    await env.DB.prepare(`UPDATE invites SET group_id = ? WHERE code = ?`).bind(groupId, code).run();
  }

  const current = await findGroup(env, device.id);
  if (current && current.id !== groupId) {
    throw new Error("You are already in a family. Leave it first to join another.");
  }
  if (!current) {
    await env.DB.prepare(
      `INSERT OR IGNORE INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
    )
      .bind(groupId, device.id, Date.now())
      .run();
  }
  const group = await serializeGroup(env, device.id);
  return json({ group, pair: legacyPair(group) });
}

async function leaveGroup(env: Env, device: DeviceRow): Promise<Response> {
  const group = await findGroup(env, device.id);
  await env.DB.prepare(`DELETE FROM group_members WHERE device_id = ?`).bind(device.id).run();
  await env.DB.prepare(`DELETE FROM pairs WHERE device_a = ? OR device_b = ?`)
    .bind(device.id, device.id)
    .run();
  await env.DB.prepare(`DELETE FROM schedules WHERE sender_id = ? OR receiver_id = ?`)
    .bind(device.id, device.id)
    .run();
  if (group) {
    const remaining = await env.DB.prepare(
      `SELECT COUNT(*) AS n FROM group_members WHERE group_id = ?`
    )
      .bind(group.id)
      .first<{ n: number }>();
    if (!remaining || remaining.n === 0) {
      await env.DB.prepare(`DELETE FROM groups WHERE id = ?`).bind(group.id).run();
      await env.DB.prepare(`DELETE FROM invites WHERE group_id = ?`).bind(group.id).run();
    }
  }
  return json({ ok: true });
}

async function createAlert(
  request: Request,
  env: Env,
  device: DeviceRow,
  kind: string
): Promise<Response> {
  const body = await readJson(request);
  const group = await requireGroup(env, device.id);
  const groupId = String(body.groupId || body.pairId || group.id);
  if (groupId !== group.id) throw new Error("You are not in that family.");
  const members = await listMembers(env, group.id);
  const requested = body.receiverId ? String(body.receiverId) : "";
  const targets = members.filter((member) => {
    if (member.id === device.id) return false;
    if (requested && requested !== EVERYONE) return member.id === requested;
    return true;
  });
  if (targets.length === 0) throw new Error("There is nobody else to check in with yet.");

  const alerts = [];
  for (const target of targets) {
    const alert = await insertAlert(env, group.id, device.id, target.id, kind);
    alerts.push(alert);
    await sendFcm(env, target.fcm_token, {
      type: "ping",
      alertId: alert.id,
      fromName: device.display_name,
      title: `Check-in from ${device.display_name}`,
      body: "Tap to let them know you're here.",
    });
  }
  return json({
    alert: serializeAlert(alerts[0], nameMap(members)),
    alerts: alerts.map((row) => serializeAlert(row, nameMap(members))),
  });
}

async function listAlerts(env: Env, device: DeviceRow): Promise<Response> {
  const group = await findGroup(env, device.id);
  const members = group ? await listMembers(env, group.id) : [];
  const rows = await env.DB.prepare(
    `SELECT * FROM alerts
     WHERE sender_id = ? OR receiver_id = ?
     ORDER BY sent_at DESC
     LIMIT 100`
  )
    .bind(device.id, device.id)
    .all<AlertRow>();
  const names = nameMap(members);
  return json({ alerts: (rows.results || []).map((row) => serializeAlert(row, names)) });
}

async function markReceived(env: Env, device: DeviceRow, alertId: string): Promise<Response> {
  const alert = await getAlert(env, alertId);
  if (!alert || alert.receiver_id !== device.id) throw new Error("Alert not found.");
  if (!alert.received_at) {
    await env.DB.prepare(`UPDATE alerts SET received_at = ? WHERE id = ? AND received_at IS NULL`)
      .bind(Date.now(), alertId)
      .run();
  }
  return json({ ok: true });
}

async function ackAlert(env: Env, device: DeviceRow, alertId: string): Promise<Response> {
  const alert = await getAlert(env, alertId);
  if (!alert || alert.receiver_id !== device.id) throw new Error("Alert not found.");
  const now = Date.now();
  await env.DB.prepare(
    `UPDATE alerts
     SET acked_at = COALESCE(acked_at, ?),
         received_at = COALESCE(received_at, ?)
     WHERE id = ?`
  )
    .bind(now, now, alertId)
    .run();
  const updated = (await getAlert(env, alertId))!;
  if (!alert.acked_at) {
    const sender = await getDevice(env, alert.sender_id);
    await sendFcm(env, sender?.fcm_token, {
      type: "ack",
      alertId: alert.id,
      fromName: device.display_name,
      title: `${device.display_name} tapped back`,
      body: "They received your check-in.",
    });
  }
  return json({ alert: serializeAlert(updated) });
}

async function listSchedules(env: Env, device: DeviceRow): Promise<Response> {
  const group = await findGroup(env, device.id);
  const members = group ? await listMembers(env, group.id) : [];
  const names = nameMap(members);
  const rows = await env.DB.prepare(
    `SELECT * FROM schedules WHERE sender_id = ? OR receiver_id = ? OR (pair_id IN (SELECT group_id FROM group_members WHERE device_id = ?) AND receiver_id = ?)
     ORDER BY hour, minute`
  )
    .bind(device.id, device.id, device.id, EVERYONE)
    .all<ScheduleRow>();
  return json({ schedules: (rows.results || []).map((row) => serializeSchedule(row, names)) });
}

async function createSchedule(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson(request);
  const group = await requireGroup(env, device.id);
  const hour = Number(body.hour);
  const minute = Number(body.minute);
  if (!Number.isInteger(hour) || hour < 0 || hour > 23) throw new Error("hour must be 0-23.");
  if (!Number.isInteger(minute) || minute < 0 || minute > 59) throw new Error("minute must be 0-59.");
  const timezone = String(body.timezone || "UTC");
  const days = Array.isArray(body.days) ? body.days.map(Number) : [0, 1, 2, 3, 4, 5, 6];
  const receiverId = body.receiverId ? String(body.receiverId) : EVERYONE;
  if (receiverId !== EVERYONE) {
    const members = await listMembers(env, group.id);
    if (!members.some((member) => member.id === receiverId && member.id !== device.id)) {
      throw new Error("Pick someone in your family.");
    }
  }
  const id = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO schedules
      (id, pair_id, sender_id, receiver_id, hour, minute, timezone, days_mask, enabled, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`
  )
    .bind(id, group.id, device.id, receiverId, hour, minute, timezone, daysToMask(days), Date.now())
    .run();
  const row = await env.DB.prepare(`SELECT * FROM schedules WHERE id = ?`)
    .bind(id)
    .first<ScheduleRow>();
  const members = await listMembers(env, group.id);
  return json({ schedule: serializeSchedule(row!, nameMap(members)) });
}

async function updateSchedule(
  request: Request,
  env: Env,
  device: DeviceRow,
  id: string
): Promise<Response> {
  const schedule = await env.DB.prepare(`SELECT * FROM schedules WHERE id = ?`)
    .bind(id)
    .first<ScheduleRow>();
  if (!schedule || (schedule.sender_id !== device.id && schedule.receiver_id !== device.id && schedule.receiver_id !== EVERYONE)) {
    throw new Error("Schedule not found.");
  }
  const body = await readJson(request);
  const enabled = body.enabled === false ? 0 : 1;
  await env.DB.prepare(`UPDATE schedules SET enabled = ? WHERE id = ?`).bind(enabled, id).run();
  return json({ ok: true });
}

async function deleteSchedule(env: Env, device: DeviceRow, id: string): Promise<Response> {
  const schedule = await env.DB.prepare(`SELECT * FROM schedules WHERE id = ?`)
    .bind(id)
    .first<ScheduleRow>();
  if (!schedule || schedule.sender_id !== device.id) {
    throw new Error("Schedule not found.");
  }
  await env.DB.prepare(`DELETE FROM schedules WHERE id = ?`).bind(id).run();
  return json({ ok: true });
}

async function fireDueSchedules(env: Env): Promise<void> {
  const rows = await env.DB.prepare(`SELECT * FROM schedules WHERE enabled = 1`).all<ScheduleRow>();
  for (const schedule of rows.results || []) {
    const zoned = zonedNow(schedule.timezone);
    if (!zoned) continue;
    if (((schedule.days_mask >> zoned.weekday) & 1) !== 1) continue;
    if (zoned.hour !== schedule.hour || zoned.minute !== schedule.minute) continue;
    if (schedule.last_fired_date === zoned.date) continue;

    const sender = await getDevice(env, schedule.sender_id);
    if (!sender) continue;
    const members = await listMembers(env, schedule.pair_id);
    const targets = members.filter((member) => {
      if (member.id === schedule.sender_id) return false;
      if (schedule.receiver_id && schedule.receiver_id !== EVERYONE) {
        return member.id === schedule.receiver_id;
      }
      return true;
    });
    for (const target of targets) {
      const alert = await insertAlert(env, schedule.pair_id, schedule.sender_id, target.id, "scheduled");
      await sendFcm(env, target.fcm_token, {
        type: "ping",
        alertId: alert.id,
        fromName: sender.display_name,
        title: `Check-in from ${sender.display_name}`,
        body: "Tap to let them know you're here.",
      });
    }
    await env.DB.prepare(`UPDATE schedules SET last_fired_date = ? WHERE id = ?`)
      .bind(zoned.date, schedule.id)
      .run();
  }
}

async function insertAlert(
  env: Env,
  groupId: string,
  senderId: string,
  receiverId: string,
  kind: string
): Promise<AlertRow> {
  const id = crypto.randomUUID();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO alerts (id, pair_id, sender_id, receiver_id, kind, sent_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  )
    .bind(id, groupId, senderId, receiverId, kind, now)
    .run();
  return {
    id,
    pair_id: groupId,
    sender_id: senderId,
    receiver_id: receiverId,
    kind,
    sent_at: now,
    received_at: null,
    acked_at: null,
  };
}

async function ensureGroup(env: Env, deviceId: string): Promise<GroupRow> {
  const existing = await findGroup(env, deviceId);
  if (existing) return existing;
  const now = Date.now();
  const oldPair = await env.DB.prepare(
    `SELECT id, device_a, device_b, created_at FROM pairs WHERE device_a = ? OR device_b = ?`
  )
    .bind(deviceId, deviceId)
    .first<{ id: string; device_a: string; device_b: string; created_at: number }>();
  if (oldPair) {
    await env.DB.prepare(`INSERT OR IGNORE INTO groups (id, created_at) VALUES (?, ?)`).bind(oldPair.id, oldPair.created_at).run();
    await env.DB.prepare(
      `INSERT OR IGNORE INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
    ).bind(oldPair.id, oldPair.device_a, oldPair.created_at).run();
    await env.DB.prepare(
      `INSERT OR IGNORE INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
    ).bind(oldPair.id, oldPair.device_b, oldPair.created_at).run();
    return { id: oldPair.id };
  }
  const id = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO groups (id, created_at) VALUES (?, ?)`).bind(id, now),
    env.DB.prepare(
      `INSERT INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
    ).bind(id, deviceId, now),
  ]);
  return { id };
}

async function requireGroup(env: Env, deviceId: string): Promise<GroupRow> {
  const group = await findGroup(env, deviceId);
  if (!group) throw new Error("Connect with your family first.");
  return group;
}

async function findGroup(env: Env, deviceId: string): Promise<GroupRow | null> {
  const row = await env.DB.prepare(
    `SELECT group_id AS id FROM group_members WHERE device_id = ? LIMIT 1`
  )
    .bind(deviceId)
    .first<{ id: string }>();
  if (row) return row;
  const pair = await env.DB.prepare(
    `SELECT id, device_a, device_b, created_at FROM pairs WHERE device_a = ? OR device_b = ?`
  )
    .bind(deviceId, deviceId)
    .first<{ id: string; device_a: string; device_b: string; created_at: number }>();
  if (!pair) return null;
  await env.DB.prepare(`INSERT OR IGNORE INTO groups (id, created_at) VALUES (?, ?)`).bind(pair.id, pair.created_at).run();
  await env.DB.prepare(
    `INSERT OR IGNORE INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
  ).bind(pair.id, pair.device_a, pair.created_at).run();
  await env.DB.prepare(
    `INSERT OR IGNORE INTO group_members (group_id, device_id, joined_at) VALUES (?, ?, ?)`
  ).bind(pair.id, pair.device_b, pair.created_at).run();
  return { id: pair.id };
}

async function listMembers(env: Env, groupId: string): Promise<MemberRow[]> {
  const rows = await env.DB.prepare(
    `SELECT d.id, d.display_name, d.fcm_token
     FROM group_members m
     JOIN devices d ON d.id = m.device_id
     WHERE m.group_id = ?
     ORDER BY d.display_name`
  )
    .bind(groupId)
    .all<MemberRow>();
  return rows.results || [];
}

async function serializeGroup(env: Env, deviceId: string) {
  const group = await findGroup(env, deviceId);
  if (!group) return null;
  const members = await listMembers(env, group.id);
  const invite = await env.DB.prepare(
    `SELECT code FROM invites WHERE group_id = ? AND expires_at > ? ORDER BY created_at DESC LIMIT 1`
  )
    .bind(group.id, Date.now())
    .first<{ code: string }>();
  return {
    id: group.id,
    inviteCode: invite?.code || "",
    members: members
      .filter((member) => member.id !== deviceId)
      .map((member) => ({ id: member.id, displayName: member.display_name })),
  };
}

function legacyPair(group: Awaited<ReturnType<typeof serializeGroup>>) {
  const first = group?.members[0];
  if (!group || !first) return null;
  return { id: group.id, partnerId: first.id, partnerName: first.displayName };
}

async function requireDevice(request: Request, env: Env): Promise<DeviceRow> {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer\s+([^:]+):(.+)$/i);
  if (!match) throw new Error("Unauthorized");
  const device = await getDevice(env, match[1]);
  if (!device) throw new Error("Unauthorized");
  const incoming = await sha256(match[2]);
  if (incoming !== device.secret_hash) throw new Error("Unauthorized");
  return device;
}

async function getDevice(env: Env, id: string): Promise<DeviceRow | null> {
  return env.DB.prepare(
    `SELECT id, secret_hash, display_name, fcm_token FROM devices WHERE id = ?`
  )
    .bind(id)
    .first<DeviceRow>();
}

async function getAlert(env: Env, id: string): Promise<AlertRow | null> {
  return env.DB.prepare(`SELECT * FROM alerts WHERE id = ?`).bind(id).first<AlertRow>();
}

function nameMap(members: MemberRow[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const member of members) map[member.id] = member.display_name;
  return map;
}

function serializeAlert(row: AlertRow, names: Record<string, string> = {}) {
  return {
    id: row.id,
    pairId: row.pair_id,
    groupId: row.pair_id,
    senderId: row.sender_id,
    receiverId: row.receiver_id,
    senderName: names[row.sender_id] || "",
    receiverName: names[row.receiver_id] || "",
    kind: row.kind,
    sentAt: row.sent_at,
    receivedAt: row.received_at,
    ackedAt: row.acked_at,
  };
}

function serializeSchedule(row: ScheduleRow, names: Record<string, string> = {}) {
  const everyone = row.receiver_id === EVERYONE;
  return {
    id: row.id,
    pairId: row.pair_id,
    groupId: row.pair_id,
    receiverId: everyone ? null : row.receiver_id,
    receiverName: everyone ? "" : names[row.receiver_id] || "",
    hour: row.hour,
    minute: row.minute,
    timezone: row.timezone,
    days: maskToDays(row.days_mask),
    enabled: row.enabled === 1,
  };
}

function daysToMask(days: number[]): number {
  let mask = 0;
  for (const day of days) {
    if (day >= 0 && day <= 6) mask |= 1 << day;
  }
  return mask || 127;
}

function maskToDays(mask: number): number[] {
  const days: number[] = [];
  for (let i = 0; i <= 6; i++) {
    if (((mask >> i) & 1) === 1) days.push(i);
  }
  return days;
}

function zonedNow(timeZone: string): { hour: number; minute: number; weekday: number; date: string } | null {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      weekday: "short",
    }).formatToParts(new Date());
    const map: Record<string, string> = {};
    for (const part of parts) {
      if (part.type !== "literal") map[part.type] = part.value;
    }
    const weekdayMap: Record<string, number> = {
      Sun: 0,
      Mon: 1,
      Tue: 2,
      Wed: 3,
      Thu: 4,
      Fri: 5,
      Sat: 6,
    };
    return {
      hour: Number(map.hour),
      minute: Number(map.minute),
      weekday: weekdayMap[map.weekday] ?? 0,
      date: `${map.year}-${map.month}-${map.day}`,
    };
  } catch {
    return null;
  }
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (!text) return {};
  return JSON.parse(text) as Record<string, unknown>;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS },
  });
}

function randomSecret(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => alphabet[b % alphabet.length]).join("");
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
