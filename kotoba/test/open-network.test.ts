import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  defineSite,
  getSite,
  listSites,
  defineLink,
  getLink,
  listLinks,
  reportIncident,
  listIncidents,
  coverage,
} from "../src/index.js";

describe("open-network kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-network.etzhayyim.com" });
  });

  describe("site", () => {
    it("defines + gets + lists + rejects bad kind", async () => {
      expect((await defineSite(e, { siteId: "S-A", name: "Tokyo PoP", kind: "pop", location: "tokyo" })).status).toBe("defined");
      expect((await getSite(e, { siteId: "S-A" })).site?.kind).toBe("pop");
      expect((await listSites(e, { kind: "pop" })).total).toBe(1);
      expect((await defineSite(e, { siteId: "S-X", name: "x", kind: "router" as any })).status).toBe("rejected");
    });
  });

  describe("link (refs two sites)", () => {
    beforeEach(async () => {
      await defineSite(e, { siteId: "S-A", name: "A", kind: "pop" });
      await defineSite(e, { siteId: "S-B", name: "B", kind: "dc" });
    });
    it("defines between existing sites; rejects self/missing", async () => {
      expect((await defineLink(e, { linkId: "L-1", aSiteId: "S-A", zSiteId: "S-B", capacityMbps: 10000, media: "fiber" })).status).toBe("defined");
      expect((await getLink(e, { linkId: "L-1" })).link?.capacityMbps).toBe(10000);
      expect((await defineLink(e, { linkId: "L-2", aSiteId: "S-A", zSiteId: "S-A" })).status).toBe("rejected");
      expect((await defineLink(e, { linkId: "L-3", aSiteId: "S-A", zSiteId: "NOPE" })).status).toBe("siteNotFound");
    });
    it("lists links touching a site (either endpoint)", async () => {
      await defineLink(e, { linkId: "L-1", aSiteId: "S-A", zSiteId: "S-B" });
      expect((await listLinks(e, { siteId: "S-A" })).total).toBe(1);
      expect((await listLinks(e, { siteId: "S-B" })).total).toBe(1);
    });
  });

  describe("incident + coverage", () => {
    beforeEach(async () => {
      await defineSite(e, { siteId: "S-A", name: "A", kind: "pop" });
      await defineSite(e, { siteId: "S-B", name: "B", kind: "dc" });
      await defineLink(e, { linkId: "L-1", aSiteId: "S-A", zSiteId: "S-B" });
    });
    it("reports against a site/link; rejects neither + bad targets", async () => {
      expect((await reportIncident(e, { incidentId: "I-1", severity: "sev1", linkId: "L-1", impact: "outage" })).status).toBe("reported");
      expect((await reportIncident(e, { incidentId: "I-2", severity: "sev2" })).status).toBe("rejected");
      expect((await reportIncident(e, { incidentId: "I-3", severity: "sev1", siteId: "NOPE" })).status).toBe("targetNotFound");
      expect((await reportIncident(e, { incidentId: "I-4", severity: "sev9" as any, siteId: "S-A" })).status).toBe("rejected");
    });
    it("lists by severity + coverage rolls up open sev1", async () => {
      await reportIncident(e, { incidentId: "I-1", severity: "sev1", linkId: "L-1" });
      await reportIncident(e, { incidentId: "I-2", severity: "sev3", siteId: "S-A" });
      expect((await listIncidents(e, { severity: "sev1" })).total).toBe(1);
      const cov = await coverage(e);
      expect(cov.siteCount).toBe(2);
      expect(cov.linkCount).toBe(1);
      expect(cov.incidentCount).toBe(2);
      expect(cov.openSev1).toBe(1);
      expect(cov.sitesByKind?.pop).toBe(1);
    });
  });
});
