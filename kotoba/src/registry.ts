/**
 * open-network kotoba — site + link + incident registries + coverage.
 * AT PDS records (no RW). Links reference two existing sites; incidents
 * reference an existing site and/or link.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  INCIDENT_COLLECTION,
  LINK_COLLECTION,
  SEVERITIES,
  SITE_COLLECTION,
  SITE_KINDS,
  incidentDid,
  incidentRkey,
  linkDid,
  linkRkey,
  siteDid,
  siteRkey,
  type CoverageInput,
  type CoverageOutput,
  type DefineLinkInput,
  type DefineLinkOutput,
  type DefineSiteInput,
  type DefineSiteOutput,
  type GetLinkInput,
  type GetLinkOutput,
  type GetSiteInput,
  type GetSiteOutput,
  type IncidentRecord,
  type IncidentSeverity,
  type IncidentView,
  type LinkRecord,
  type LinkStatus,
  type LinkView,
  type ListIncidentsInput,
  type ListIncidentsOutput,
  type ListLinksInput,
  type ListLinksOutput,
  type ListSitesInput,
  type ListSitesOutput,
  type ReportIncidentInput,
  type ReportIncidentOutput,
  type SiteKind,
  type SiteRecord,
  type SiteView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

// ─── Site ───────────────────────────────────────────────────────────

export async function defineSite(
  e: Etzhayyim,
  input: DefineSiteInput
): Promise<DefineSiteOutput> {
  if (!input.siteId || !input.name || !input.kind) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (!SITE_KINDS.has(input.kind)) return { status: "rejected", error: "invalidKind" };
  const rkey = siteRkey(input.siteId);
  const existing = await e
    .read<SiteRecord>({ collection: SITE_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", siteUri: existing.records[0].uri, did: existing.records[0].value.did, siteId: input.siteId };
  }
  const did = siteDid(input.siteId);
  const record: SiteRecord = {
    did,
    siteId: input.siteId,
    name: input.name,
    kind: input.kind,
    location: input.location,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: SITE_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", siteUri: receipt.uri, did, siteId: input.siteId };
}

export async function getSite(e: Etzhayyim, input: GetSiteInput): Promise<GetSiteOutput> {
  if (!input.siteId) return { error: "invalidSiteId" };
  const resp = await e
    .read<SiteRecord>({ collection: SITE_COLLECTION, rkey: siteRkey(input.siteId) })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { site: { ...r.value, siteUri: r.uri } };
}

export async function listSites(e: Etzhayyim, input: ListSitesInput = {}): Promise<ListSitesOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<SiteRecord>({ collection: SITE_COLLECTION, cursor: input.cursor, limit });
  const items: SiteView[] = resp.records
    .filter((r) => (input.kind ? r.value.kind === input.kind : true))
    .map((r) => ({ ...r.value, siteUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Link ───────────────────────────────────────────────────────────

export async function defineLink(
  e: Etzhayyim,
  input: DefineLinkInput
): Promise<DefineLinkOutput> {
  if (!input.linkId || !input.aSiteId || !input.zSiteId) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (input.aSiteId.toLowerCase() === input.zSiteId.toLowerCase()) {
    return { status: "rejected", error: "selfLink" };
  }
  if (
    !(await exists(e, SITE_COLLECTION, siteRkey(input.aSiteId))) ||
    !(await exists(e, SITE_COLLECTION, siteRkey(input.zSiteId)))
  ) {
    return { status: "siteNotFound", error: "siteNotFound" };
  }
  const rkey = linkRkey(input.linkId);
  const existing = await e
    .read<LinkRecord>({ collection: LINK_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", linkUri: existing.records[0].uri, did: existing.records[0].value.did, linkId: input.linkId };
  }
  const did = linkDid(input.linkId);
  const record: LinkRecord = {
    did,
    linkId: input.linkId,
    aSiteId: input.aSiteId.toLowerCase(),
    zSiteId: input.zSiteId.toLowerCase(),
    capacityMbps: input.capacityMbps,
    media: input.media,
    status: input.status ?? "up",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: LINK_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", linkUri: receipt.uri, did, linkId: input.linkId };
}

export async function getLink(e: Etzhayyim, input: GetLinkInput): Promise<GetLinkOutput> {
  if (!input.linkId) return { error: "invalidLinkId" };
  const resp = await e
    .read<LinkRecord>({ collection: LINK_COLLECTION, rkey: linkRkey(input.linkId) })
    .catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { link: { ...r.value, linkUri: r.uri } };
}

export async function listLinks(e: Etzhayyim, input: ListLinksInput = {}): Promise<ListLinksOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<LinkRecord>({ collection: LINK_COLLECTION, cursor: input.cursor, limit });
  const site = input.siteId?.toLowerCase();
  const items: LinkView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (site && v.aSiteId !== site && v.zSiteId !== site) return false;
      if (input.status && v.status !== input.status) return false;
      if (input.media && v.media !== input.media) return false;
      return true;
    })
    .map((r) => ({ ...r.value, linkUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Incident ───────────────────────────────────────────────────────

export async function reportIncident(
  e: Etzhayyim,
  input: ReportIncidentInput
): Promise<ReportIncidentOutput> {
  if (!input.incidentId || !input.severity) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (!SEVERITIES.has(input.severity)) return { status: "rejected", error: "invalidSeverity" };
  if (!input.siteId && !input.linkId) {
    return { status: "rejected", error: "needSiteOrLink" };
  }
  if (input.siteId && !(await exists(e, SITE_COLLECTION, siteRkey(input.siteId)))) {
    return { status: "targetNotFound", error: "siteNotFound" };
  }
  if (input.linkId && !(await exists(e, LINK_COLLECTION, linkRkey(input.linkId)))) {
    return { status: "targetNotFound", error: "linkNotFound" };
  }
  const rkey = incidentRkey(input.incidentId);
  const existing = await e
    .read<IncidentRecord>({ collection: INCIDENT_COLLECTION, rkey })
    .catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", incidentUri: existing.records[0].uri, did: existing.records[0].value.did, incidentId: input.incidentId };
  }
  const did = incidentDid(input.incidentId);
  const now = new Date().toISOString();
  const record: IncidentRecord = {
    did,
    incidentId: input.incidentId,
    severity: input.severity,
    status: "open",
    siteId: input.siteId ? input.siteId.toLowerCase() : undefined,
    linkId: input.linkId ? input.linkId.toLowerCase() : undefined,
    impact: input.impact,
    reportedAt: input.reportedAt ?? now,
    createdAt: now,
  };
  const receipt = await e.write({ collection: INCIDENT_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "reported", incidentUri: receipt.uri, did, incidentId: input.incidentId };
}

export async function listIncidents(e: Etzhayyim, input: ListIncidentsInput = {}): Promise<ListIncidentsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<IncidentRecord>({ collection: INCIDENT_COLLECTION, cursor: input.cursor, limit });
  const items: IncidentView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.siteId && v.siteId !== input.siteId.toLowerCase()) return false;
      if (input.linkId && v.linkId !== input.linkId.toLowerCase()) return false;
      if (input.severity && v.severity !== input.severity) return false;
      if (input.status && v.status !== input.status) return false;
      return true;
    })
    .map((r) => ({ ...r.value, incidentUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

async function countAll<T>(
  e: Etzhayyim,
  collection: string,
  maxScan: number,
  onRow: (v: T) => void
): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const sitesByKind: Record<string, number> = {};
  const siteCount = await countAll<SiteRecord>(e, SITE_COLLECTION, maxScan, (v) => {
    sitesByKind[v.kind as SiteKind] = (sitesByKind[v.kind as SiteKind] ?? 0) + 1;
  });
  const linksByStatus: Record<string, number> = {};
  const linkCount = await countAll<LinkRecord>(e, LINK_COLLECTION, maxScan, (v) => {
    linksByStatus[v.status as LinkStatus] = (linksByStatus[v.status as LinkStatus] ?? 0) + 1;
  });
  let openSev1 = 0;
  const incidentCount = await countAll<IncidentRecord>(e, INCIDENT_COLLECTION, maxScan, (v) => {
    if (v.severity === "sev1" && v.status === "open") openSev1 += 1;
  });
  return {
    siteCount,
    sitesByKind,
    linkCount,
    linksByStatus,
    incidentCount,
    openSev1,
    truncated: siteCount >= maxScan || linkCount >= maxScan || incidentCount >= maxScan,
  };
}
