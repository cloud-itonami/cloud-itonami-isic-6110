/**
 * open-network kotoba — record types.
 *
 * Per ADR-2605203000 Option B. Telecom NMS: network sites + links + incidents.
 * Registry on AT PDS records (replaces D1 sites/links/incidents).
 * ADR-2605172000 kotoba.
 *
 * Identity hierarchy:
 *   did:web:open-network.etzhayyim.com                      — controller
 *   did:web:open-network.etzhayyim.com:site:{siteId}        — a network site
 *   did:web:open-network.etzhayyim.com:link:{linkId}        — a link
 *   did:web:open-network.etzhayyim.com:incident:{id}        — a NOC incident
 */

export const ONET_DID_PREFIX = "did:web:open-network.etzhayyim.com:" as const;

export const SITE_COLLECTION = "com.etzhayyim.apps.openNetwork.site";
export const LINK_COLLECTION = "com.etzhayyim.apps.openNetwork.link";
export const INCIDENT_COLLECTION = "com.etzhayyim.apps.openNetwork.incident";

// ─── Site ───────────────────────────────────────────────────────────

export type SiteKind = "pop" | "dc" | "cellTower" | "customerEdge";

export interface SiteRecord {
  did: string;
  siteId: string;
  name: string;
  kind: SiteKind;
  location?: string;
  createdAt: string;
}

export interface SiteView extends SiteRecord {
  siteUri: string;
}

export interface DefineSiteInput {
  siteId: string;
  name: string;
  kind: SiteKind;
  location?: string;
}

export interface DefineSiteOutput {
  status: "defined" | "alreadyExists" | "rejected";
  siteUri?: string;
  did?: string;
  siteId?: string;
  error?: string;
}

export interface GetSiteInput {
  siteId: string;
}

export interface GetSiteOutput {
  site?: SiteView;
  error?: string;
}

export interface ListSitesInput {
  kind?: SiteKind;
  limit?: number;
  cursor?: string;
}

export interface ListSitesOutput {
  items: SiteView[];
  cursor?: string;
  total: number;
}

// ─── Link ───────────────────────────────────────────────────────────

export type LinkMedia = "fiber" | "microwave" | "copper" | "satellite" | "other";
export type LinkStatus = "up" | "down" | "degraded" | "planned";

export interface LinkRecord {
  did: string;
  linkId: string;
  /** Endpoints (site ids); a link is bidirectional A↔Z. */
  aSiteId: string;
  zSiteId: string;
  /** Capacity in Mbps (integer). */
  capacityMbps?: number;
  media?: LinkMedia;
  status: LinkStatus;
  createdAt: string;
}

export interface LinkView extends LinkRecord {
  linkUri: string;
}

export interface DefineLinkInput {
  linkId: string;
  aSiteId: string;
  zSiteId: string;
  capacityMbps?: number;
  media?: LinkMedia;
  status?: LinkStatus;
}

export interface DefineLinkOutput {
  status: "defined" | "alreadyExists" | "rejected" | "siteNotFound";
  linkUri?: string;
  did?: string;
  linkId?: string;
  error?: string;
}

export interface GetLinkInput {
  linkId: string;
}

export interface GetLinkOutput {
  link?: LinkView;
  error?: string;
}

export interface ListLinksInput {
  /** Match links touching this site (either endpoint). */
  siteId?: string;
  status?: LinkStatus;
  media?: LinkMedia;
  limit?: number;
  cursor?: string;
}

export interface ListLinksOutput {
  items: LinkView[];
  cursor?: string;
  total: number;
}

// ─── Incident ───────────────────────────────────────────────────────

export type IncidentSeverity = "sev1" | "sev2" | "sev3" | "sev4";
export type IncidentStatus = "open" | "mitigated" | "resolved";

export interface IncidentRecord {
  did: string;
  incidentId: string;
  severity: IncidentSeverity;
  status: IncidentStatus;
  /** Affected site and/or link (at least one). */
  siteId?: string;
  linkId?: string;
  impact?: string;
  reportedAt: string;
  createdAt: string;
}

export interface IncidentView extends IncidentRecord {
  incidentUri: string;
}

export interface ReportIncidentInput {
  incidentId: string;
  severity: IncidentSeverity;
  siteId?: string;
  linkId?: string;
  impact?: string;
  reportedAt?: string;
}

export interface ReportIncidentOutput {
  status: "reported" | "alreadyExists" | "rejected" | "targetNotFound";
  incidentUri?: string;
  did?: string;
  incidentId?: string;
  error?: string;
}

export interface ListIncidentsInput {
  siteId?: string;
  linkId?: string;
  severity?: IncidentSeverity;
  status?: IncidentStatus;
  limit?: number;
  cursor?: string;
}

export interface ListIncidentsOutput {
  items: IncidentView[];
  cursor?: string;
  total: number;
}

export interface CoverageInput {
  maxScan?: number;
}

export interface CoverageOutput {
  siteCount?: number;
  sitesByKind?: Record<string, number>;
  linkCount?: number;
  linksByStatus?: Record<string, number>;
  incidentCount?: number;
  openSev1?: number;
  truncated?: boolean;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export const SITE_KINDS: ReadonlySet<SiteKind> = new Set(["pop", "dc", "cellTower", "customerEdge"]);
export const SEVERITIES: ReadonlySet<IncidentSeverity> = new Set(["sev1", "sev2", "sev3", "sev4"]);

export function siteDid(id: string): string {
  return `${ONET_DID_PREFIX}site:${id.toLowerCase()}`;
}
export function siteRkey(id: string): string {
  return `site-${id.toLowerCase()}`;
}
export function linkDid(id: string): string {
  return `${ONET_DID_PREFIX}link:${id.toLowerCase()}`;
}
export function linkRkey(id: string): string {
  return `link-${id.toLowerCase()}`;
}
export function incidentDid(id: string): string {
  return `${ONET_DID_PREFIX}incident:${id.toLowerCase()}`;
}
export function incidentRkey(id: string): string {
  return `incident-${id.toLowerCase()}`;
}
