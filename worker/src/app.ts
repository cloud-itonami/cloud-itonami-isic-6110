// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 etzhayyim Japan株式会社 / etzhayyim. All rights reserved.
// Licensed under the Apache License, Version 2.0 — see LICENSE at repo root.

// etzhayyim-project-open-network — telecom NMS operations + topology design
//
// 10 XRPC under com.etzhayyim.apps.openNetwork.*:
//   defineSite           (proc)   PoP / DC / cell tower / customer edge
//   defineLink           (proc)   bidirectional link between two sites
//   getSite              (query)  site detail + adjacent links
//   listLinks            (query)  links by site / status
//   recordUtilization    (proc)   5-min Mbps sample (in/out)
//   getLinkUtilization   (query)  hourly aggregate per link
//   reportIncident       (proc)   incident with severity DMN
//   listIncidents        (query)  incidents by site / link / since
//   requestChange        (proc)   change request with risk DMN
//   listChanges          (query)  change requests by status
//
// Storage: D1. Topology = sites + bidirectional links.
// Severity (incidentSeverity DMN) and risk (changeRisk DMN) mirrored.
// SEV1/SEV2 incidents and HIGH-risk changes → app.bsky.feed.post audit.

import AV1 from "../../dodaf/AV-1.json";
import OV1 from "../../dodaf/OV-1.json";
import OV5b from "../../dodaf/OV-5b.json";
import OV6a from "../../dodaf/OV-6a.json";
import CV2 from "../../dodaf/CV-2.json";
import SV1 from "../../dodaf/SV-1.json";
import defineLinkForm from "../../forms/defineLink.form.json";
import requestChangeForm from "../../forms/requestChange.form.json";
import { bootstrapDodaf } from "./dodaf-bootstrap";

const DODAF_VIEWS: Record<string, any> = {
  "open-network.AV-1": AV1, "open-network.OV-1": OV1, "open-network.OV-5b": OV5b,
  "open-network.OV-6a": OV6a, "open-network.CV-2": CV2, "open-network.SV-1": SV1,
};
const FORMS: Record<string, any> = {
  "openNetwork.defineLink.v1": defineLinkForm,
  "openNetwork.requestChange.v1": requestChangeForm,
};

export interface Env {
  NETWORK_DB: D1Database;
  PDS?: Fetcher;
  AUTH_SERVICE?: Fetcher;
  APP_HANDLE: string;
  PRIMARY_DID: string;
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS sites (
    site_did TEXT PRIMARY KEY,
    site_code TEXT NOT NULL UNIQUE,
    site_type TEXT NOT NULL,
    name TEXT NOT NULL,
    operator_did TEXT NOT NULL,
    region TEXT,
    defined_at TEXT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS links (
    link_did TEXT PRIMARY KEY,
    link_code TEXT NOT NULL UNIQUE,
    site_a_did TEXT NOT NULL,
    site_b_did TEXT NOT NULL,
    media TEXT NOT NULL,
    capacity_mbps INTEGER NOT NULL,
    length_km REAL,
    status TEXT NOT NULL DEFAULT 'in-service',
    defined_at TEXT NOT NULL,
    FOREIGN KEY (site_a_did) REFERENCES sites(site_did),
    FOREIGN KEY (site_b_did) REFERENCES sites(site_did),
    CHECK (site_a_did != site_b_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_links_a ON links(site_a_did)`,
  `CREATE INDEX IF NOT EXISTS idx_links_b ON links(site_b_did)`,
  `CREATE TABLE IF NOT EXISTS utilization (
    sample_id TEXT PRIMARY KEY,
    link_did TEXT NOT NULL,
    sampled_at TEXT NOT NULL,
    in_mbps REAL NOT NULL,
    out_mbps REAL NOT NULL,
    over_capacity INTEGER NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_util_link_time ON utilization(link_did, sampled_at DESC)`,
  `CREATE TABLE IF NOT EXISTS incidents (
    incident_did TEXT PRIMARY KEY,
    target_did TEXT NOT NULL,
    started_at TEXT NOT NULL,
    resolved_at TEXT,
    customers_impacted INTEGER NOT NULL,
    service_down INTEGER NOT NULL,
    data_loss INTEGER NOT NULL,
    severity TEXT NOT NULL,
    require_public_notice INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_incidents_target ON incidents(target_did, started_at DESC)`,
  `CREATE TABLE IF NOT EXISTS changes (
    change_did TEXT PRIMARY KEY,
    target_did TEXT NOT NULL,
    title TEXT NOT NULL,
    blast_radius TEXT NOT NULL,
    reversible INTEGER NOT NULL,
    in_maintenance_window INTEGER NOT NULL,
    risk_level TEXT NOT NULL,
    approver_level TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'submitted',
    scheduled_start TEXT NOT NULL,
    scheduled_end TEXT NOT NULL,
    description TEXT,
    submitted_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_changes_status ON changes(status, scheduled_start)`,
];

let schemaReady = false;
async function ensureSchema(db: D1Database) {
  if (schemaReady) return;
  for (const s of SCHEMA) await db.exec(s.replace(/\s+/g, " "));
  schemaReady = true;
}

function nanoid(len = 12): string {
  const a = "abcdefghijklmnopqrstuvwxyz0123456789";
  const b = crypto.getRandomValues(new Uint8Array(len));
  let o = ""; for (let i = 0; i < len; i++) o += a[b[i] % a.length]; return o;
}
const now = () => new Date().toISOString();

type XrpcError = "InvalidRequest" | "SiteNotFound" | "LinkNotFound" | "Conflict" | "Unauthorized" | "InternalError";
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { "content-type": "application/json" } });
const err = (e: XrpcError, m: string, s = 400) => json({ error: e, message: m }, s);

function classifyIncident(input: { customersImpacted: number; serviceDown: boolean; dataLoss: boolean })
  : { severity: "SEV1" | "SEV2" | "SEV3" | "SEV4" | "SEV5"; requirePublicNotice: boolean } {
  if (input.dataLoss) return { severity: "SEV1", requirePublicNotice: true };
  if (input.customersImpacted >= 10000 && input.serviceDown) return { severity: "SEV1", requirePublicNotice: true };
  if (input.customersImpacted >= 1000  && input.serviceDown) return { severity: "SEV2", requirePublicNotice: true };
  if (input.customersImpacted >= 100)  return { severity: "SEV3", requirePublicNotice: false };
  if (input.customersImpacted >= 1)    return { severity: "SEV4", requirePublicNotice: false };
  return { severity: "SEV5", requirePublicNotice: false };
}

function classifyChange(input: { blastRadius: string; reversible: boolean; inMaintenanceWindow: boolean })
  : { riskLevel: "LOW" | "MED" | "HIGH"; approverLevel: string } {
  if (!input.reversible) return { riskLevel: "HIGH", approverLevel: "CAB+VP" };
  if (input.blastRadius === "regional") return { riskLevel: "HIGH", approverLevel: "CAB+VP" };
  if (input.blastRadius === "site") {
    return input.inMaintenanceWindow
      ? { riskLevel: "MED",  approverLevel: "NOC-Manager" }
      : { riskLevel: "HIGH", approverLevel: "CAB+VP" };
  }
  return { riskLevel: "LOW", approverLevel: "Peer-Review" };
}

async function defineSite(env: Env, input: any): Promise<Response> {
  const { siteCode, siteType, name, operatorDid, region } = input ?? {};
  if (typeof siteCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(siteCode))
    return err("InvalidRequest", "siteCode required");
  if (typeof siteType !== "string"
    || !["pop", "dc", "cell", "edge", "core", "customer"].includes(siteType))
    return err("InvalidRequest", "siteType invalid (pop|dc|cell|edge|core|customer)");
  if (typeof name !== "string" || !name.length) return err("InvalidRequest", "name required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  const id = nanoid(10);
  const siteDid = `did:web:${env.APP_HANDLE}:site:${id}`;
  const definedAt = now();
  await env.NETWORK_DB.prepare(
    `INSERT INTO sites (site_did, site_code, site_type, name, operator_did, region, defined_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).bind(siteDid, siteCode, siteType, name, operatorDid, region ?? null, definedAt).run();
  return json({ siteDid, siteCode, siteType, name, operatorDid, region: region ?? null, definedAt });
}

async function defineLink(env: Env, input: any): Promise<Response> {
  const { siteAdid, siteBdid, linkCode, media, capacityMbps, lengthKm } = input ?? {};
  if (typeof siteAdid !== "string" || typeof siteBdid !== "string")
    return err("InvalidRequest", "siteAdid + siteBdid required");
  if (siteAdid === siteBdid) return err("InvalidRequest", "endpoints must differ");
  if (typeof linkCode !== "string" || !/^[A-Z0-9-]{2,32}$/.test(linkCode))
    return err("InvalidRequest", "linkCode required");
  if (!["fiber", "microwave", "satellite", "copper"].includes(media))
    return err("InvalidRequest", "media invalid");
  if (!Number.isInteger(capacityMbps) || capacityMbps < 1)
    return err("InvalidRequest", "capacityMbps ≥ 1 required");
  const [a, b] = await Promise.all([
    env.NETWORK_DB.prepare(`SELECT site_did FROM sites WHERE site_did = ?`).bind(siteAdid).first<any>(),
    env.NETWORK_DB.prepare(`SELECT site_did FROM sites WHERE site_did = ?`).bind(siteBdid).first<any>(),
  ]);
  if (!a || !b) return err("SiteNotFound", "endpoint site not found", 404);
  const id = nanoid(10);
  const linkDid = `did:web:${env.APP_HANDLE}:link:${id}`;
  const definedAt = now();
  await env.NETWORK_DB.prepare(
    `INSERT INTO links (link_did, link_code, site_a_did, site_b_did, media, capacity_mbps, length_km, status, defined_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, 'in-service', ?)`
  ).bind(linkDid, linkCode, siteAdid, siteBdid, media, capacityMbps,
         Number.isFinite(lengthKm) ? lengthKm : null, definedAt).run();
  return json({ linkDid, linkCode, siteAdid, siteBdid, media, capacityMbps,
                lengthKm: Number.isFinite(lengthKm) ? lengthKm : null, definedAt });
}

async function getSite(env: Env, params: URLSearchParams): Promise<Response> {
  const siteDid = params.get("siteDid");
  if (!siteDid) return err("InvalidRequest", "siteDid required");
  const s = await env.NETWORK_DB.prepare(`SELECT * FROM sites WHERE site_did = ?`).bind(siteDid).first<any>();
  if (!s) return err("SiteNotFound", "no such site", 404);
  const ls = await env.NETWORK_DB.prepare(
    `SELECT * FROM links WHERE site_a_did = ? OR site_b_did = ?`
  ).bind(siteDid, siteDid).all<any>();
  return json({
    siteDid: s.site_did, siteCode: s.site_code, siteType: s.site_type, name: s.name,
    operatorDid: s.operator_did, region: s.region ?? undefined, definedAt: s.defined_at,
    adjacentLinks: (ls.results ?? []).map((r) => ({
      linkDid: r.link_did, linkCode: r.link_code,
      peerDid: r.site_a_did === siteDid ? r.site_b_did : r.site_a_did,
      media: r.media, capacityMbps: r.capacity_mbps, status: r.status,
    })),
  });
}

async function listLinks(env: Env, params: URLSearchParams): Promise<Response> {
  const siteDid = params.get("siteDid");
  const status = params.get("status");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (siteDid) { clauses.push(`(site_a_did = ? OR site_b_did = ?)`); binds.push(siteDid, siteDid); }
  if (status)  { clauses.push(`status = ?`); binds.push(status); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.NETWORK_DB.prepare(`SELECT COUNT(*) AS c FROM links ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.NETWORK_DB.prepare(
    `SELECT * FROM links ${where} ORDER BY defined_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    links: (rows.results ?? []).map((r) => ({
      linkDid: r.link_did, linkCode: r.link_code, siteAdid: r.site_a_did, siteBdid: r.site_b_did,
      media: r.media, capacityMbps: r.capacity_mbps, lengthKm: r.length_km ?? undefined,
      status: r.status, definedAt: r.defined_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function recordUtilization(env: Env, input: any): Promise<Response> {
  const { linkDid, sampledAt, inMbps, outMbps } = input ?? {};
  if (typeof linkDid !== "string") return err("InvalidRequest", "linkDid required");
  if (typeof sampledAt !== "string") return err("InvalidRequest", "sampledAt required");
  if (!Number.isFinite(inMbps) || inMbps < 0) return err("InvalidRequest", "inMbps ≥ 0");
  if (!Number.isFinite(outMbps) || outMbps < 0) return err("InvalidRequest", "outMbps ≥ 0");
  const link = await env.NETWORK_DB.prepare(`SELECT capacity_mbps FROM links WHERE link_did = ?`)
    .bind(linkDid).first<any>();
  if (!link) return err("LinkNotFound", "no such link", 404);
  const cap105 = link.capacity_mbps * 1.05;
  const overCapacity = inMbps > cap105 || outMbps > cap105;
  const id = `u_${nanoid(14)}`;
  await env.NETWORK_DB.prepare(
    `INSERT INTO utilization (sample_id, link_did, sampled_at, in_mbps, out_mbps, over_capacity)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(id, linkDid, sampledAt, inMbps, outMbps, overCapacity ? 1 : 0).run();
  return json({ sampleId: id, linkDid, sampledAt, inMbps, outMbps, overCapacity });
}

async function getLinkUtilization(env: Env, params: URLSearchParams): Promise<Response> {
  const linkDid = params.get("linkDid");
  if (!linkDid) return err("InvalidRequest", "linkDid required");
  const since = params.get("since");
  const until = params.get("until") ?? now();
  if (!since) return err("InvalidRequest", "since (ISO 8601) required");
  const rows = await env.NETWORK_DB.prepare(
    `SELECT sampled_at, in_mbps, out_mbps FROM utilization
     WHERE link_did = ? AND sampled_at >= ? AND sampled_at <= ?
     ORDER BY sampled_at ASC`
  ).bind(linkDid, since, until).all<any>();
  type Bucket = { hour: string; nIn: number; sumIn: number; maxIn: number; nOut: number; sumOut: number; maxOut: number };
  const buckets = new Map<string, Bucket>();
  for (const r of rows.results ?? []) {
    const hour = (r.sampled_at as string).slice(0, 13) + ":00:00Z";
    let b = buckets.get(hour);
    if (!b) { b = { hour, nIn: 0, sumIn: 0, maxIn: 0, nOut: 0, sumOut: 0, maxOut: 0 }; buckets.set(hour, b); }
    b.nIn++;  b.sumIn  += r.in_mbps;  if (r.in_mbps  > b.maxIn)  b.maxIn  = r.in_mbps;
    b.nOut++; b.sumOut += r.out_mbps; if (r.out_mbps > b.maxOut) b.maxOut = r.out_mbps;
  }
  const profile = [...buckets.values()].sort((a, b) => a.hour.localeCompare(b.hour))
    .map((b) => ({
      hour: b.hour,
      avgInMbps:  b.nIn  ? b.sumIn  / b.nIn  : 0,
      maxInMbps:  b.maxIn,
      avgOutMbps: b.nOut ? b.sumOut / b.nOut : 0,
      maxOutMbps: b.maxOut,
    }));
  return json({ linkDid, since, until, profile });
}

async function reportIncident(env: Env, input: any): Promise<Response> {
  const { targetDid, startedAt, resolvedAt, customersImpacted, serviceDown, dataLoss, title, description } = input ?? {};
  if (typeof targetDid !== "string") return err("InvalidRequest", "targetDid required");
  if (typeof startedAt !== "string") return err("InvalidRequest", "startedAt required");
  if (typeof title !== "string" || !title.length) return err("InvalidRequest", "title required");
  if (!Number.isFinite(customersImpacted) || customersImpacted < 0)
    return err("InvalidRequest", "customersImpacted ≥ 0");
  const { severity, requirePublicNotice } = classifyIncident({
    customersImpacted: Number(customersImpacted), serviceDown: !!serviceDown, dataLoss: !!dataLoss,
  });
  const id = nanoid(12);
  const incidentDid = `did:web:${env.APP_HANDLE}:incident:${id}`;
  const reportedAt = now();
  await env.NETWORK_DB.prepare(
    `INSERT INTO incidents (incident_did, target_did, started_at, resolved_at, customers_impacted,
                            service_down, data_loss, severity, require_public_notice, title, description, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(incidentDid, targetDid, startedAt, resolvedAt ?? null, customersImpacted,
         serviceDown ? 1 : 0, dataLoss ? 1 : 0, severity,
         requirePublicNotice ? 1 : 0, title, description ?? null, reportedAt).run();
  if (requirePublicNotice && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: { $type: "app.bsky.feed.post",
            text: `[${severity}] ${title} — target ${targetDid}, ${customersImpacted} customers`,
            createdAt: reportedAt },
        }),
      });
    } catch {}
  }
  return json({ incidentDid, severity, requirePublicNotice, reportedAt });
}

async function listIncidents(env: Env, params: URLSearchParams): Promise<Response> {
  const targetDid = params.get("targetDid");
  const since = params.get("since");
  const minSeverity = params.get("minSeverity");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const sevRank = (s: string) => ({ SEV5: 0, SEV4: 1, SEV3: 2, SEV2: 3, SEV1: 4 } as any)[s] ?? -1;
  const clauses: string[] = []; const binds: any[] = [];
  if (targetDid) { clauses.push(`target_did = ?`); binds.push(targetDid); }
  if (since)     { clauses.push(`started_at >= ?`); binds.push(since); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.NETWORK_DB.prepare(`SELECT COUNT(*) AS c FROM incidents ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.NETWORK_DB.prepare(
    `SELECT * FROM incidents ${where} ORDER BY started_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  let incidents = (rows.results ?? []).map((r) => ({
    incidentDid: r.incident_did, targetDid: r.target_did, startedAt: r.started_at,
    resolvedAt: r.resolved_at ?? undefined, customersImpacted: r.customers_impacted,
    serviceDown: !!r.service_down, dataLoss: !!r.data_loss, severity: r.severity,
    requirePublicNotice: !!r.require_public_notice, title: r.title,
    description: r.description ?? undefined, reportedAt: r.reported_at,
  }));
  if (minSeverity) incidents = incidents.filter((i) => sevRank(i.severity) >= sevRank(minSeverity));
  return json({ incidents, total: Number(total?.c ?? 0), offset, limit });
}

async function requestChange(env: Env, input: any): Promise<Response> {
  const { targetDid, title, blastRadius, reversible, inMaintenanceWindow,
          scheduledStart, scheduledEnd, description } = input ?? {};
  if (typeof targetDid !== "string") return err("InvalidRequest", "targetDid required");
  if (typeof title !== "string" || !title.length) return err("InvalidRequest", "title required");
  if (!["component", "site", "regional"].includes(blastRadius))
    return err("InvalidRequest", "blastRadius invalid");
  if (typeof scheduledStart !== "string" || typeof scheduledEnd !== "string")
    return err("InvalidRequest", "scheduledStart/End required");
  const { riskLevel, approverLevel } = classifyChange({
    blastRadius, reversible: !!reversible, inMaintenanceWindow: !!inMaintenanceWindow,
  });
  const id = nanoid(12);
  const changeDid = `did:web:${env.APP_HANDLE}:change:${id}`;
  const submittedAt = now();
  await env.NETWORK_DB.prepare(
    `INSERT INTO changes (change_did, target_did, title, blast_radius, reversible, in_maintenance_window,
                          risk_level, approver_level, status, scheduled_start, scheduled_end, description, submitted_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'submitted', ?, ?, ?, ?)`
  ).bind(changeDid, targetDid, title, blastRadius, reversible ? 1 : 0,
         inMaintenanceWindow ? 1 : 0, riskLevel, approverLevel,
         scheduledStart, scheduledEnd, description ?? null, submittedAt).run();
  if (riskLevel === "HIGH" && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: { $type: "app.bsky.feed.post",
            text: `[CAB] HIGH-risk change requested: ${title} on ${targetDid} (approver: ${approverLevel}, ${scheduledStart} → ${scheduledEnd})`,
            createdAt: submittedAt },
        }),
      });
    } catch {}
  }
  return json({ changeDid, targetDid, riskLevel, approverLevel, status: "submitted", submittedAt });
}

async function listChanges(env: Env, params: URLSearchParams): Promise<Response> {
  const status = params.get("status");
  const targetDid = params.get("targetDid");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (status)    { clauses.push(`status = ?`); binds.push(status); }
  if (targetDid) { clauses.push(`target_did = ?`); binds.push(targetDid); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.NETWORK_DB.prepare(`SELECT COUNT(*) AS c FROM changes ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.NETWORK_DB.prepare(
    `SELECT * FROM changes ${where} ORDER BY scheduled_start DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    changes: (rows.results ?? []).map((r) => ({
      changeDid: r.change_did, targetDid: r.target_did, title: r.title,
      blastRadius: r.blast_radius, reversible: !!r.reversible,
      inMaintenanceWindow: !!r.in_maintenance_window,
      riskLevel: r.risk_level, approverLevel: r.approver_level, status: r.status,
      scheduledStart: r.scheduled_start, scheduledEnd: r.scheduled_end,
      description: r.description ?? undefined, submittedAt: r.submitted_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      await ensureSchema(env.NETWORK_DB);
      const url = new URL(req.url);
      if (url.pathname === "/health" || url.pathname === "/_worker/health")
        return json({ ok: true, did: env.PRIMARY_DID, ts: now() });
      if (url.pathname === "/_app/meta") {
        if (env.PDS) { try { await bootstrapDodaf(env as any); } catch {} }
        return json({
          did: env.PRIMARY_DID, handle: env.APP_HANDLE,
          xrpc: [
            "com.etzhayyim.apps.openNetwork.defineSite",
            "com.etzhayyim.apps.openNetwork.defineLink",
            "com.etzhayyim.apps.openNetwork.getSite",
            "com.etzhayyim.apps.openNetwork.listLinks",
            "com.etzhayyim.apps.openNetwork.recordUtilization",
            "com.etzhayyim.apps.openNetwork.getLinkUtilization",
            "com.etzhayyim.apps.openNetwork.reportIncident",
            "com.etzhayyim.apps.openNetwork.listIncidents",
            "com.etzhayyim.apps.openNetwork.requestChange",
            "com.etzhayyim.apps.openNetwork.listChanges",
          ],
          dodaf: Object.keys(DODAF_VIEWS), forms: Object.keys(FORMS),
          bpmn: ["defineLink", "requestChange"],
          dmn:  ["openNetwork.incidentSeverity", "openNetwork.changeRisk"],
        });
      }
      if (url.pathname === "/dodaf")
        return json({ views: Object.entries(DODAF_VIEWS).map(([id, v]: any) => ({
          viewId: id, viewType: v.viewType, title: v.title, version: v.version })) });
      if (url.pathname.startsWith("/dodaf/")) {
        const id = decodeURIComponent(url.pathname.slice("/dodaf/".length));
        const v = DODAF_VIEWS[id];
        return v ? json(v) : err("InvalidRequest", `no such view: ${id}`, 404);
      }
      if (url.pathname === "/forms")
        return json({ forms: Object.values(FORMS).map((f: any) => ({ formKey: f.formKey, name: f.name, version: f.version })) });
      if (url.pathname.startsWith("/forms/")) {
        const k = decodeURIComponent(url.pathname.slice("/forms/".length));
        const f = FORMS[k];
        return f ? json(f) : err("InvalidRequest", `no such form: ${k}`, 404);
      }
      if (!url.pathname.startsWith("/xrpc/"))
        return err("InvalidRequest", "only /xrpc/* is served", 404);
      const nsid = url.pathname.slice("/xrpc/".length);
      if (req.method === "GET") {
        switch (nsid) {
          case "com.etzhayyim.apps.openNetwork.getSite":            return await getSite(env, url.searchParams);
          case "com.etzhayyim.apps.openNetwork.listLinks":          return await listLinks(env, url.searchParams);
          case "com.etzhayyim.apps.openNetwork.getLinkUtilization": return await getLinkUtilization(env, url.searchParams);
          case "com.etzhayyim.apps.openNetwork.listIncidents":      return await listIncidents(env, url.searchParams);
          case "com.etzhayyim.apps.openNetwork.listChanges":        return await listChanges(env, url.searchParams);
          default: return err("InvalidRequest", `unknown query NSID: ${nsid}`, 404);
        }
      }
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        switch (nsid) {
          case "com.etzhayyim.apps.openNetwork.defineSite":         return await defineSite(env, body);
          case "com.etzhayyim.apps.openNetwork.defineLink":         return await defineLink(env, body);
          case "com.etzhayyim.apps.openNetwork.recordUtilization":  return await recordUtilization(env, body);
          case "com.etzhayyim.apps.openNetwork.reportIncident":     return await reportIncident(env, body);
          case "com.etzhayyim.apps.openNetwork.requestChange":      return await requestChange(env, body);
          default: return err("InvalidRequest", `unknown procedure NSID: ${nsid}`, 404);
        }
      }
      return err("InvalidRequest", "method not allowed", 405);
    } catch (e: any) {
      return err("InternalError", e?.message ?? String(e), 500);
    }
  },
};
