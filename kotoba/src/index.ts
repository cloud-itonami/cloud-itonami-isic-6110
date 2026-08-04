/**
 * open-network kotoba — barrel.
 *
 * Per ADR-2605203000 Option B. Telecom NMS registry on the etzhayyim substrate
 * (AT PDS records; no RW).
 *
 *   site     : defineSite / getSite / listSites
 *   link     : defineLink / getLink / listLinks
 *   incident : reportIncident / listIncidents
 *   coverage : topology + open-sev1 rollup
 */

export * from "./types.js";
export {
  defineSite,
  getSite,
  listSites,
  defineLink,
  getLink,
  listLinks,
  reportIncident,
  listIncidents,
  coverage,
} from "./registry.js";
