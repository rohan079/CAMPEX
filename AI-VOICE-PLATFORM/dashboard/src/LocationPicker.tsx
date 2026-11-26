import { useEffect, useId, useRef, useState } from "react";
import { searchMetaLocations } from "./executionApi";
import type { MetaLocation } from "./executionTypes";

export interface PickedLocation {
  location: string;
  key: string | null;
  type: MetaLocation["type"] | null;
}

const TYPE_LABEL: Record<MetaLocation["type"], string> = { city: "City", region: "Region", country: "Country" };

/**
 * Location input backed by Meta's own location search. Picking a suggestion stores Meta's location key, so the
 * publish targets exactly that place instead of re-matching free text (which Meta often can't).
 */
export function LocationPicker({ clientId, value, onChange, label = "Location", required }: {
  clientId?: string; value: PickedLocation; onChange: (next: PickedLocation) => void; label?: string; required?: boolean;
}) {
  const [results, setResults] = useState<MetaLocation[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState(-1);
  const typed = useRef(false);
  const listId = useId();

  useEffect(() => {
    const q = value.location.trim();
    if (!typed.current || !clientId || q.length < 2 || value.key) { setResults([]); setLoading(false); return undefined; }
    let live = true;
    setLoading(true);
    const timer = window.setTimeout(() => {
      searchMetaLocations(clientId, q)
        .then((r) => { if (live) { setResults(r.locations); setError(null); setOpen(true); setActive(-1); } })
        .catch((err) => { if (live) { setResults([]); setError(err instanceof Error ? err.message : "Meta location search failed"); } })
        .finally(() => live && setLoading(false));
    }, 350);
    return () => { live = false; window.clearTimeout(timer); };
  }, [clientId, value.location, value.key]);

  function pick(row: MetaLocation) {
    typed.current = false;
    onChange({ location: row.label || row.name, key: row.key, type: row.type });
    setOpen(false);
    setResults([]);
  }

  function onKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (!open || results.length === 0) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, results.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === "Enter" && active >= 0) { e.preventDefault(); pick(results[active]); }
    else if (e.key === "Escape") setOpen(false);
  }

  const showList = open && results.length > 0;
  return (
    <div className="form-field location-picker">
      <label htmlFor={`${listId}-input`}><span>{label} <small>search and pick the place Meta should target</small></span></label>
      <div className="location-input-wrap">
        <input id={`${listId}-input`} value={value.location} maxLength={160} required={required} autoComplete="off"
          role="combobox" aria-expanded={showList} aria-controls={listId} aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
          placeholder="Start typing a city, region or country"
          onChange={(e) => { typed.current = true; onChange({ location: e.target.value, key: null, type: null }); }}
          onKeyDown={onKey} onFocus={() => results.length > 0 && setOpen(true)} onBlur={() => window.setTimeout(() => setOpen(false), 150)} />
        {showList && (
          <ul id={listId} role="listbox" className="location-options">
            {results.map((row, i) => (
              <li key={`${row.type}-${row.key}`} id={`${listId}-${i}`} role="option" aria-selected={i === active}
                className={i === active ? "active" : undefined} onMouseDown={(e) => { e.preventDefault(); pick(row); }}>
                <span>{row.label || row.name}</span><small>{TYPE_LABEL[row.type] ?? row.type}</small>
              </li>
            ))}
          </ul>
        )}
      </div>
      {value.key ? (
        <span className="location-status ok">✓ Matched in Meta as a {TYPE_LABEL[value.type ?? "city"]?.toLowerCase() ?? "place"}</span>
      ) : loading ? (
        <span className="location-status">Searching Meta…</span>
      ) : error ? (
        <span className="location-status warn">{error}</span>
      ) : typed.current && value.location.trim().length >= 2 && results.length === 0 && clientId ? (
        <span className="location-status warn">Meta has no place by that name. Try just the city or region name.</span>
      ) : value.location.trim() ? (
        <span className="location-status warn">Not matched yet. Pick a suggestion so the publish can't fail on location.</span>
      ) : null}
    </div>
  );
}
