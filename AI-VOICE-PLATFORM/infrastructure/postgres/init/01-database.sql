-- Runs once, the first time the Postgres volume is created.
-- Tables are NOT created here: the lead service owns the schema and applies it with Flyway on startup
-- (springboot-lead-service/src/main/resources/db/migration).
ALTER DATABASE voiceleads SET timezone TO 'UTC';
