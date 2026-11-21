import { useCallback, useEffect, useRef, useState } from "react";
import { listNotifications, markAllNotificationsRead, markNotificationRead } from "./executionApi";
import type { AppNotification } from "./executionTypes";
import { formatWhen } from "./format";

const POLL_MS = 60_000;

/** In-app notification center in the top bar. Opening an execution notification jumps to Campaign approvals. */
export function NotificationBell({ onOpenExecution }: { onOpenExecution: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<AppNotification[]>([]);
  const [unread, setUnread] = useState(0);
  const [failed, setFailed] = useState(false);
  const panel = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      const result = await listNotifications();
      setItems(result.items);
      setUnread(result.unread_count);
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") setOpen(false); };
    const onClick = (event: MouseEvent) => { if (panel.current && !panel.current.contains(event.target as Node)) setOpen(false); };
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onClick);
    return () => { document.removeEventListener("keydown", onKey); document.removeEventListener("mousedown", onClick); };
  }, [open]);

  async function openItem(item: AppNotification) {
    if (!item.read_at) {
      try { await markNotificationRead(item.id); } catch { /* still navigate */ }
    }
    setOpen(false);
    void load();
    if (item.entity_type === "campaign_execution" && item.entity_id) onOpenExecution(item.entity_id);
  }

  return (
    <div className="notif" ref={panel}>
      <button type="button" className="icon-button notif-button" aria-haspopup="dialog" aria-expanded={open}
        aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"} onClick={() => { setOpen((v) => !v); void load(); }}>
        <span aria-hidden="true">🔔</span>
        {unread > 0 && <span className="notif-badge" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
      </button>
      {open && (
        <div className="notif-panel" role="dialog" aria-label="Notifications">
          <header><strong>Notifications</strong>
            {unread > 0 && <button type="button" className="text-button" onClick={async () => { await markAllNotificationsRead(); void load(); }}>Mark all read</button>}
          </header>
          {failed && <p className="notice error">Could not load notifications.</p>}
          {items.length === 0 && !failed ? <p className="empty-state">You're all caught up.</p> : (
            <ul>
              {items.map((item) => (
                <li key={item.id}>
                  <button type="button" className={`notif-item sev-${item.severity}${item.read_at ? "" : " unread"}`} onClick={() => void openItem(item)}>
                    <strong>{item.title}</strong>
                    {item.body && <span>{item.body}</span>}
                    <small>{formatWhen(item.created_at)}</small>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
