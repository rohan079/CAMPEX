# PostgreSQL

One Postgres 16 database, `voiceleads`, used **only by the Spring lead service**. The voice service has no
database and never connects to this one; it reports call outcomes to the lead service over HTTP.

- `init/` — scripts Postgres runs once, when the data volume is first created.
- The schema is not here. It is versioned in
  `springboot-lead-service/src/main/resources/db/migration` and applied by Flyway at startup, so the schema
  always travels with the code that depends on it. To change it, add a new `V<n>__description.sql`.

## Tables

| Table | Purpose |
| --- | --- |
| `leads` | One row per person: phone, language, ad details, status, follow-up window |
| `lead_consents` | Evidence that someone agreed to a call (keyword, message id, time) |
| `voice_calls` | One row per call attempt and its status |
| `whatsapp_events` | Inbound WhatsApp message ids, so Meta retries are processed once |

Deleting a lead removes its consents and calls (`ON DELETE CASCADE`).

## Handy commands

```bash
docker compose -f infrastructure/docker-compose.yml exec postgres psql -U voice voiceleads
docker compose -f infrastructure/docker-compose.yml down -v   # wipe local data
```
