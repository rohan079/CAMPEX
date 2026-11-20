import { vi } from "vitest";

/**
 * Every Phase 1 API call is a mock: UI tests never reach the network, Firebase or Meta.
 * Pure helpers (labels, date ranges) are the real implementations.
 */
export async function mockMeasurementApi() {
  const format = await vi.importActual<typeof import("../../src/measurementFormat")>("../../src/measurementFormat");
  return {
    ...format,
    listPixels: vi.fn(), createPixel: vi.fn(), updatePixel: vi.fn(), deletePixel: vi.fn(), listEvents: vi.fn(),
    getEvent: vi.fn(), getEventSummary: vi.fn(), sendTestEvent: vi.fn(), getAdAccounts: vi.fn(), setAdAccounts: vi.fn(),
    getAttributionSummary: vi.fn(), getCampaignReport: vi.fn(), getLeadAttribution: vi.fn(), getKpis: vi.fn(),
    getPipeline: vi.fn(), listAgents: vi.fn(), createAgent: vi.fn(), listProjects: vi.fn(), createProject: vi.fn(),
    getPerformance: vi.fn(), createManualLead: vi.fn(), getCrmLead: vi.fn(), assignLead: vi.fn(), changeLeadStatus: vi.fn(),
    listActivities: vi.fn(), addActivity: vi.fn(), addFollowUp: vi.fn(), updateFollowUp: vi.fn(), saveQualification: vi.fn(),
    recordLeadConsent: vi.fn(), listSiteVisits: vi.fn(), createSiteVisit: vi.fn(), updateSiteVisitStatus: vi.fn(),
    rescheduleSiteVisit: vi.fn(), listClientSales: vi.fn(), getSale: vi.fn(), createClientSale: vi.fn(), updateSale: vi.fn(),
    confirmSale: vi.fn(), cancelSale: vi.fn(),
  };
}
