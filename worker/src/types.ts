export interface Env {
  DB: D1Database;
  KV: KVNamespace;
  FCM_PROJECT_ID?: string;
  FCM_CLIENT_EMAIL?: string;
  FCM_PRIVATE_KEY?: string;
}

export type PushKind = "ping" | "ack";

export interface PushPayload {
  type: PushKind;
  alertId: string;
  fromName: string;
  title: string;
  body: string;
}
