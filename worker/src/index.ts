import { sendFcm } from "./fcm";
import type { Env } from "./types";

interface DeviceRow {
  id: string;
  secret_hash: string;
  display_name: string;
  fcm_token: string | null;
}

interface PairRow {
  id: string;
  device_a: string;
  device_b: string;
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
  if (request.method === "DELETE" && path === "/v1/pairs/me") {
    return unlink(env, device);
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
  const pair = await findPair(env, device.id);
  return json({
    device: { id: device.id, displayName: device.display_name },
    pair: pair ? await serializePair(env, pair, device.id) : null,
  });
}

async function createInvite(env: Env, device: DeviceRow): Promise<Response> {
  if (await findPair(env, device.id)) {
    throw new Error("Already connected with someone. Unlink first.");
  }
  const code = randomCode();
  const now = Date.now();
  await env.DB.prepare(`DELETE FROM invites WHERE creator_id = ? AND used_at IS NULL`)
    .bind(device.id)
    .run();
  await env.DB.prepare(
    `INSERT INTO invites (code, creator_id, created_at, expires_at) VALUES (?, ?, ?, ?)`
  )
    .bind(code, device.id, now, now + 24 * 60 * 60 * 1000)
    .run();
  return json({ code, expiresAt: now + 24 * 60 * 60 * 1000 });
}

async function joinInvite(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  if (await findPair(env, device.id)) {
    throw new Error("Already connected with someone. Unlink first.");
  }
  const body = await readJson(request);
  const code = String(body.code || "").trim().toUpperCase();
  const invite = await env.DB.prepare(
    `SELECT code, creator_id, expires_at, used_at FROM invites WHERE code = ?`
  )
    .bind(code)
    .first<{ code: string; creator_id: string; expires_at: number; used_at: number | null }>();
  if (!invite) throw new Error("That code was not found.");
  if (invite.used_at) throw new Error("That code was already used.");
  if (invite.expires_at < Date.now()) throw new Error("That code expired. Ask for a new one.");
  if (invite.creator_id === device.id) throw new Error("You cannot join your own code.");
  if (await findPair(env, invite.creator_id)) {
    throw new Error("The other person is already connected with someone.");
  }

  const pairId = crypto.randomUUID();
  const [a, b] = [invite.creator_id, device.id].sort();
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO pairs (id, device_a, device_b, created_at) VALUES (?, ?, ?, ?)`
    ).bind(pairId, a, b, Date.now()),
    env.DB.prepare(`UPDATE invites SET used_at = ? WHERE code = ?`).bind(Date.now(), code),
  ]);
  const pair = (await findPair(env, device.id))!;
  return json({ pair: await serializePair(env, pair, device.id) });
}

async function unlink(env: Env, device: DeviceRow): Promise<Response> {
  await env.DB.prepare(`DELETE FROM pairs WHERE device_a = ? OR device_b = ?`)
    .bind(device.id, device.id)
    .run();
  await env.DB.prepare(`DELETE FROM schedules WHERE sender_id = ? OR receiver_id = ?`)
    .bind(device.id, device.id)
    .run();
  return json({ ok: true });
}

async function createAlert(
  request: Request,
  env: Env,
  device: DeviceRow,
  kind: string
): Promise<Response> {
  const body = await readJson(request);
  const pairId = String(body.pairId || "");
  const pair = await findPair(env, device.id);
  if (!pair || pair.id !== pairId) throw new Error("You are not connected on that pair.");
  const receiverId = partnerId(pair, device.id);
  const alert = await insertAlert(env, pair.id, device.id, receiverId, kind);
  const receiver = await getDevice(env, receiverId);
  await sendFcm(env, receiver?.fcm_token, {
    type: "ping",
    alertId: alert.id,
    fromName: device.display_name,
    title: `Check-in from ${device.display_name}`,
    body: "Tap to let them know you're here.",
  });
  return json({ alert: serializeAlert(alert) });
}

async function listAlerts(env: Env, device: DeviceRow): Promise<Response> {
  const rows = await env.DB.prepare(
    `SELECT * FROM alerts
     WHERE sender_id = ? OR receiver_id = ?
     ORDER BY sent_at DESC
     LIMIT 100`
  )
    .bind(device.id, device.id)
    .all<AlertRow>();
  return json({ alerts: (rows.results || []).map(serializeAlert) });
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
  const rows = await env.DB.prepare(
    `SELECT * FROM schedules WHERE sender_id = ? OR receiver_id = ? ORDER BY hour, minute`
  )
    .bind(device.id, device.id)
    .all<ScheduleRow>();
  return json({ schedules: (rows.results || []).map(serializeSchedule) });
}

async function createSchedule(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson(request);
  const pair = await findPair(env, device.id);
  if (!pair || pair.id !== String(body.pairId || "")) {
    throw new Error("You are not connected on that pair.");
  }
  const hour = Number(body.hour);
  const minute = Number(body.minute);
  if (!Number.isInteger(hour) || hour < 0 || hour > 23) throw new Error("hour must be 0-23.");
  if (!Number.isInteger(minute) || minute < 0 || minute > 59) throw new Error("minute must be 0-59.");
  const timezone = String(body.timezone || "UTC");
  const days = Array.isArray(body.days) ? body.days.map(Number) : [0, 1, 2, 3, 4, 5, 6];
  const id = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO schedules
      (id, pair_id, sender_id, receiver_id, hour, minute, timezone, days_mask, enabled, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`
  )
    .bind(
      id,
      pair.id,
      device.id,
      partnerId(pair, device.id),
      hour,
      minute,
      timezone,
      daysToMask(days),
      Date.now()
    )
    .run();
  const row = await env.DB.prepare(`SELECT * FROM schedules WHERE id = ?`)
    .bind(id)
    .first<ScheduleRow>();
  return json({ schedule: serializeSchedule(row!) });
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
  if (!schedule || (schedule.sender_id !== device.id && schedule.receiver_id !== device.id)) {
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
  if (!schedule || (schedule.sender_id !== device.id && schedule.receiver_id !== device.id)) {
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
    const receiver = await getDevice(env, schedule.receiver_id);
    if (!sender || !receiver) continue;

    const alert = await insertAlert(
      env,
      schedule.pair_id,
      schedule.sender_id,
      schedule.receiver_id,
      "scheduled"
    );
    await env.DB.prepare(`UPDATE schedules SET last_fired_date = ? WHERE id = ?`)
      .bind(zoned.date, schedule.id)
      .run();
    await sendFcm(env, receiver.fcm_token, {
      type: "ping",
      alertId: alert.id,
      fromName: sender.display_name,
      title: `Check-in from ${sender.display_name}`,
      body: "Tap to let them know you're here.",
    });
  }
}

async function insertAlert(
  env: Env,
  pairId: string,
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
    .bind(id, pairId, senderId, receiverId, kind, now)
    .run();
  return {
    id,
    pair_id: pairId,
    sender_id: senderId,
    receiver_id: receiverId,
    kind,
    sent_at: now,
    received_at: null,
    acked_at: null,
  };
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

async function findPair(env: Env, deviceId: string): Promise<PairRow | null> {
  return env.DB.prepare(`SELECT id, device_a, device_b FROM pairs WHERE device_a = ? OR device_b = ?`)
    .bind(deviceId, deviceId)
    .first<PairRow>();
}

async function serializePair(env: Env, pair: PairRow, selfId: string) {
  const otherId = partnerId(pair, selfId);
  const other = await getDevice(env, otherId);
  return {
    id: pair.id,
    partnerId: otherId,
    partnerName: other?.display_name || "Partner",
  };
}

function partnerId(pair: PairRow, selfId: string): string {
  return pair.device_a === selfId ? pair.device_b : pair.device_a;
}

function serializeAlert(row: AlertRow) {
  return {
    id: row.id,
    pairId: row.pair_id,
    senderId: row.sender_id,
    receiverId: row.receiver_id,
    kind: row.kind,
    sentAt: row.sent_at,
    receivedAt: row.received_at,
    ackedAt: row.acked_at,
  };
}

function serializeSchedule(row: ScheduleRow) {
  return {
    id: row.id,
    pairId: row.pair_id,
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
