import type { ReachRowInput } from "./types";

export interface ParsedReach {
  rows: ReachRowInput[];
  errors: string[];
}

const HEADER = /^ad[_ ]?id\b/i;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Columns: ad_id, ad_name, location, reach, impressions (optional), date (YYYY-MM-DD).
 * Comma or tab separated, so a selection copied from a spreadsheet works.
 */
export function parseReachCsv(text: string): ParsedReach {
  const rows: ReachRowInput[] = [];
  const errors: string[] = [];
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);

  lines.forEach((line, index) => {
    if (index === 0 && HEADER.test(line)) return;
    const label = `Line ${index + 1}`;
    const cells = line.split(/\t|,/).map((cell) => cell.trim().replace(/^"(.*)"$/, "$1").trim());
    if (cells.length < 5) {
      errors.push(`${label}: expected ad_id, ad_name, location, reach, impressions, date`);
      return;
    }
    const [adId, adName, location, reachText, ...rest] = cells;
    const day = rest[rest.length - 1];
    const impressionsText = rest.length >= 2 ? rest[0] : "";
    const reach = Number(reachText.replaceAll(",", ""));
    const impressions = impressionsText === "" ? undefined : Number(impressionsText.replaceAll(",", ""));

    if (!adId) errors.push(`${label}: ad_id is empty`);
    else if (!location) errors.push(`${label}: location is empty`);
    else if (!Number.isInteger(reach) || reach < 0) errors.push(`${label}: reach must be a whole number`);
    else if (impressions !== undefined && (!Number.isInteger(impressions) || impressions < 0)) {
      errors.push(`${label}: impressions must be a whole number`);
    } else if (!DATE.test(day)) errors.push(`${label}: date must look like 2026-10-31`);
    else {
      rows.push({ ad_id: adId, ad_name: adName || undefined, location, reach, impressions, day });
    }
  });

  return { rows, errors };
}
