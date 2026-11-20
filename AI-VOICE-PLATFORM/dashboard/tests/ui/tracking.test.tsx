import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../src/measurementApi";
import { EventMonitor, PixelManager } from "../../src/TrackingPage";
import type { Pixel } from "../../src/measurementTypes";

vi.mock("../../src/measurementApi", async () => (await import("./apiMock")).mockMeasurementApi());
const m = vi.mocked(api);

const pixel: Pixel = {
  id: "p1", client_id: "c1", meta_pixel_id: "123456789", name: "Main site", allowed_domains: ["example.com"], enabled: true,
  enabled_events: ["PageView", "Lead"], forward_custom_events: false, consent_mode: "required", test_event_code: "TEST1",
  capi_token_configured: true, capi_dispatch_enabled: true, created_at: "", updated_at: "",
};

afterEach(cleanup);

describe("Pixel setup screen", () => {
  beforeEach(() => {
    m.listPixels.mockResolvedValue([pixel]);
    m.createPixel.mockResolvedValue(pixel);
    m.sendTestEvent.mockResolvedValue({ status: "accepted", event_id: "test-1", delivery_status: "pending" });
  });

  it("lists pixels without ever showing the token", async () => {
    render(<PixelManager clientId="c1" />);
    expect(await screen.findByText("Main site")).toBeTruthy();
    expect(screen.getByText(/Token stored/)).toBeTruthy();
    expect(document.body.textContent).not.toContain("EAA");
  });

  it("creates a pixel with parsed domains and a write-only token", async () => {
    render(<PixelManager clientId="c1" />);
    await screen.findByText("Main site");
    const form = screen.getByRole("form", { name: "Pixel configuration" });
    fireEvent.change(within(form).getByLabelText(/Meta Pixel ID/), { target: { value: "987654321" } });
    fireEvent.change(within(form).getByLabelText(/^Name/), { target: { value: "Offers" } });
    fireEvent.change(within(form).getByLabelText(/Website domains/), { target: { value: "offers.example.com, example.com" } });
    fireEvent.change(within(form).getByLabelText(/Conversions API access token/), { target: { value: "EAA-secret" } });
    fireEvent.submit(form);
    await waitFor(() => expect(m.createPixel).toHaveBeenCalled());
    const [clientId, body] = m.createPixel.mock.calls[0];
    expect(clientId).toBe("c1");
    expect(body.allowed_domains).toEqual(["offers.example.com", "example.com"]);
    expect(body.capi_access_token).toBe("EAA-secret");
    expect(body.consent_mode).toBe("required");
    expect(await screen.findByText("Pixel created.")).toBeTruthy();
  });

  it("sends a test event for a pixel with a test code", async () => {
    render(<PixelManager clientId="c1" />);
    fireEvent.click(await screen.findByText("Send test event"));
    await waitFor(() => expect(m.sendTestEvent).toHaveBeenCalledWith("c1", "p1"));
    expect(await screen.findByText(/Test event test-1 queued/)).toBeTruthy();
  });
});

describe("Event monitoring", () => {
  beforeEach(() => {
    m.getEventSummary.mockResolvedValue({
      received: 42, sent_to_meta: 30, failed: 2, pending: 5, skipped_no_consent: 3, not_forwarded: 2, deduplicated: 7,
      delivery_success_rate: 0.9375, by_event_name: [{ name: "Lead", count: 12 }], by_source: [],
      by_delivery_status: [{ name: "sent", count: 30 }], by_day: [], capi_dispatch_enabled: true,
    });
    m.listEvents.mockResolvedValue({ events: [{
      id: "e1", event_id: "evt-1", event_name: "Lead", event_category: "meta_standard", meta_event_name: "Lead", lead_id: null,
      visitor_id: "v1", campaign_id: "1202", ad_id: null, event_source: "website", event_time: "2026-11-20T10:00:00Z",
      received_at: "2026-11-20T10:00:00Z", event_source_url: "https://example.com", identifiers_present: ["em"], fbc_present: true,
      fbp_present: false, consent_status: "granted", meta_delivery_status: "failed", delivery_attempts: 1, last_error: "Invalid",
      duplicate_count: 1, test: false, deliveries: null,
    }], total: 1, limit: 50, offset: 0 });
    m.getEvent.mockImplementation(async () => ({ ...(await m.listEvents.mock.results[0].value).events[0], deliveries: [
      { attempt_number: 1, http_status: 400, events_received: null, fbtrace_id: "T1", error_code: 100, error_message: "Invalid parameter", retryable: false, outcome: "failed", attempted_at: "2026-11-20T10:01:00Z" },
    ] }));
  });

  it("shows delivery KPIs and filters by delivery status", async () => {
    render(<EventMonitor clientId="c1" days={30} />);
    expect(await screen.findByText("42")).toBeTruthy();
    expect(screen.getByText("94%")).toBeTruthy();
    expect(screen.getByText("7")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Delivery status"), { target: { value: "failed" } });
    await waitFor(() => expect(m.listEvents).toHaveBeenLastCalledWith("c1", expect.objectContaining({ delivery_status: "failed" })));
  });

  it("opens an event with its Meta delivery attempts", async () => {
    render(<EventMonitor clientId="c1" days={30} />);
    fireEvent.click(await screen.findByText("1 duplicate(s)"));
    expect(await screen.findByText("Invalid parameter")).toBeTruthy();
    expect(screen.getByText("T1")).toBeTruthy();
  });
});
