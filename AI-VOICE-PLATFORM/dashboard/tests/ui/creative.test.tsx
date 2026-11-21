import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../src/creativeApi";
import type { CreativeDetail, CreativeStatus } from "../../src/creativeTypes";
import { CreativeStudioPage } from "../../src/CreativeStudioPage";

vi.mock("../../src/creativeApi", () => ({
  generateCopy: vi.fn(), generateArtwork: vi.fn(), uploadArtwork: vi.fn(), listCreatives: vi.fn(), getCreative: vi.fn(),
  editCreative: vi.fn(), submitCreative: vi.fn(), approveCreative: vi.fn(), rejectCreative: vi.fn(), archiveCreative: vi.fn(),
  creativeImage: vi.fn(), fileToBase64: vi.fn(),
}));
const m = vi.mocked(api);

function detail(status: CreativeStatus, over: Partial<CreativeDetail["versions"][number]> = {}, allowed: CreativeDetail["allowed_actions"] = ["edit", "submit", "archive"]): CreativeDetail {
  return {
    asset: { id: "cr1", client_id: "c1", name: "Diwali villas", ad_type: "villa", status, current_version: 2, approved_version: null,
      image_id: "img1", headline: "East-facing villas", created_by: "asha.manager@example.com", updated_at: "2026-11-20T10:00:00Z" },
    versions: [{ version: 2, image_id: "img1", image_source: "ai_generated", image_prompt: "Villa at dawn", headline: "East-facing villas",
      primary_text: "Visit the model villa.", description: null, cta: "LEARN_MORE", facts: ["3 BHK villas from ₹85 lakh"], claim_check: null,
      change_note: null, created_by: "asha.manager@example.com", created_at: "2026-11-20T10:00:00Z", ...over }],
    approvals: [], allowed_actions: allowed,
  };
}

beforeEach(() => {
  m.listCreatives.mockResolvedValue([detail("GENERATED").asset]);
  m.creativeImage.mockResolvedValue(new Blob(["x"], { type: "image/png" }));
  globalThis.URL.createObjectURL = vi.fn(() => "blob:x");
  globalThis.URL.revokeObjectURL = vi.fn();
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Creative studio", () => {
  it("writes copy from verified facts and shows the claim check", async () => {
    m.generateCopy.mockResolvedValue({ headlines: ["H1", "H2", "H3", "H4", "H5"], primary_texts: ["P1", "P2", "P3"], descriptions: ["D1", "D2", "D3"],
      cta_recommendations: [{ cta: "LEARN_MORE", reason: "Lead form" }], concepts: [{ title: "Dawn", visual: "Villa at dawn", angle: "Family" }],
      claim_check: { unsupported_numbers: ["49 lakh"], flagged_phrases: [] } });
    render(<CreativeStudioPage clientId="c1" />);
    fireEvent.change(screen.getByLabelText(/Verified facts/), { target: { value: "3 BHK villas from ₹85 lakh\n\nRERA registered" } });
    fireEvent.change(screen.getByLabelText("Property or offer"), { target: { value: "Green Villas" } });
    fireEvent.click(screen.getByRole("button", { name: "Write variations" }));
    await waitFor(() => expect(m.generateCopy).toHaveBeenCalledWith("c1", expect.objectContaining({
      product: "Green Villas", facts: ["3 BHK villas from ₹85 lakh", "RERA registered"] })));
    expect(await screen.findByText(/Not in your verified facts/)).toBeTruthy();
    expect(screen.getAllByRole("radio", { name: /H\d/ })).toHaveLength(5);
    expect(screen.getByText(/not guaranteed to perform better/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Use as artwork prompt/ }));
    expect((screen.getByLabelText(/Describe the artwork/) as HTMLTextAreaElement).value).toBe("Villa at dawn");
  });

  it("previews the ad and blocks approval while prohibited claims remain", async () => {
    m.getCreative.mockResolvedValue(detail("REVIEW_PENDING", { claim_check: { unsupported_numbers: [], flagged_phrases: [{ text: "guaranteed", reason: "Guarantees are not allowed" }] } }, ["edit", "approve", "reject", "archive"]));
    render(<CreativeStudioPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Open Diwali villas" }));
    const dialog = await screen.findByRole("dialog", { name: "Diwali villas" });
    expect(await within(dialog).findByLabelText("Ad preview")).toBeTruthy();
    expect(within(dialog).getByText(/illustrative only/)).toBeTruthy();
    expect(within(dialog).getByText(/Not allowed in property ads/)).toBeTruthy();
    expect((within(dialog).getByRole("button", { name: "Approve v2" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("unverified figures need the approver to confirm they checked them", async () => {
    m.getCreative.mockResolvedValue(detail("REVIEW_PENDING", { claim_check: { unsupported_numbers: ["12 km"], flagged_phrases: [] } }, ["edit", "approve", "reject", "archive"]));
    m.approveCreative.mockResolvedValue(detail("APPROVED", {}, ["edit", "archive"]));
    render(<CreativeStudioPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Open Diwali villas" }));
    const dialog = await screen.findByRole("dialog", { name: "Diwali villas" });
    const approve = await within(dialog).findByRole("button", { name: "Approve v2" }) as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
    fireEvent.click(within(dialog).getByLabelText(/I verified the flagged figures/));
    fireEvent.click(approve);
    await waitFor(() => expect(m.approveCreative).toHaveBeenCalledWith("cr1", true));
  });

  it("editing saves a new version and submit waits for unsaved changes", async () => {
    m.getCreative.mockResolvedValue(detail("EDITING"));
    m.editCreative.mockResolvedValue(detail("EDITING", { version: 3, headline: "New headline" }));
    render(<CreativeStudioPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Open Diwali villas" }));
    const dialog = await screen.findByRole("dialog", { name: "Diwali villas" });
    fireEvent.change(await within(dialog).findByLabelText(/^Headline/), { target: { value: "New headline" } });
    expect((within(dialog).getByRole("button", { name: "Submit for review" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save new version" }));
    await waitFor(() => expect(m.editCreative).toHaveBeenCalledWith("cr1", expect.objectContaining({ headline: "New headline" })));
  });
});
