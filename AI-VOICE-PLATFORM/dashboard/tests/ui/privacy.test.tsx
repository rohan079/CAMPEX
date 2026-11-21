import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { Sensitive, actorName, maskEmail, maskPhone, safeName } from "../../src/privacy";

describe("privacy helpers", () => {
  it("masks phone numbers and emails", () => {
    expect(maskPhone("+919876543210")).toBe("+91 •••••• 3210");
    expect(maskPhone("9876543210")).toBe("•••••• 3210");
    expect(maskEmail("ravi.kumar@example.com")).toBe("r•••@example.com");
  });

  it("never uses a raw phone as a display name", () => {
    expect(safeName("Ravi")).toBe("Ravi");
    expect(safeName("+919876545678")).toBe("Lead · 5678");
    expect(safeName(null, "9876543210")).toBe("Lead · 3210");
  });

  it("shows team members by first name", () => {
    expect(actorName("asha.manager@example.com")).toBe("Asha");
  });

  it("reveals a value only on request", () => {
    render(<Sensitive kind="phone" value="+919876543210" />);
    expect(screen.queryByText("+919876543210")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show phone number" }));
    expect(screen.getByText("+919876543210")).toBeTruthy();
  });
});
