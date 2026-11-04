# Server / Database

This directory contains the M3A Spring Boot application, the complete Flyway schema, system seed, and demo seed.

## Application

Build and start the full stack:

```bash
docker compose up -d --build
curl -fsS http://localhost:8080/actuator/health   # {"status":"UP"}
```

The `docker` profile runs Flyway on startup with `classpath:db/migration` plus
`classpath:db/dev` (dev-only upstream rows for the fixed `DevDataConfig` IDs),
against the `shijiangtai` database.

Run the full test suite (Compose PostgreSQL, not Testcontainers):

```bash
docker compose --profile test run --rm test
# focused run:
docker compose --profile test run --rm test \
  gradle test --tests '*TaskControllerIT' --no-daemon --console=plain
```

Dev headers are `X-Account-Id`, `X-School-Id`, `X-Account-Type` (for example
`...0010` / `...0001` / `TEACHER`); they are placeholders until M1 JWT lands.

Smoke flow: create a free task, upload a `.txt` material, publish, then list
student tasks. The full request sequence and expected responses are in
`docs/team/evidence/2026-11-04-m3a-docker-postgres.md` §3.

## Control WebSocket smoke

The M3B classroom control channel is a raw WebSocket (no STOMP):
`/ws/v1/sessions/{session_id}?ticket=...`. Tickets are one-time, valid for 30 s, and are
issued over REST with the student dev headers.

```bash
# 1) Create/publish a task and create a session (M3A flow), then export its id.
SESSION_ID=<session_id>

# 2) Request a one-time ticket.
TICKET=$(curl -fsS -X POST \
  "http://localhost:8080/api/v1/sessions/$SESSION_ID/ws-ticket" \
  -H 'X-Account-Id: 00000000-0000-0000-0000-000000000020' \
  -H 'X-School-Id: 00000000-0000-0000-0000-000000000001' \
  -H 'X-Account-Type: STUDENT' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["ticket"])')

# 3) Connect with any WebSocket client (for example Node `ws` or `wscat`).
wscat -c "ws://localhost:8080/ws/v1/sessions/$SESSION_ID?ticket=$TICKET"
```

After the server sends `session.snapshot`, send these envelopes (one JSON object per line):

```jsonc
{"v":1,"type":"recording.ready","command_id":"ready-1","payload":{"streams":[{"stream_type":"camera","status":"ready"},{"stream_type":"screen","status":"ready"},{"stream_type":"audio","status":"ready"}]}}
{"v":1,"type":"session.start","command_id":"start-1","payload":{}}
```

Expected order: `session.snapshot` → `recording.ready_ack` → `server.ack` →
`session.started` → `server.ack`; after the start a `session.time_tick` arrives every second.
Command events are pushed once by the actor sink; `server.ack` only confirms the command.
A full transcript, close-code checks, and the DB verification SQL are in
`docs/team/evidence/2026-11-04-m3b-classroom-ws.md` §5.

> `allowedOrigins("*")` is dev-only until the M1 gateway/JWT layer lands; tighten it before
> release.

## Migration commands

```bash
# Start PostgreSQL
docker compose up -d --wait postgres

# Reset and migrate the test database (shijiangtai_test)
./server/docker/reset-test-db.sh test
docker compose --profile migrate run --rm flyway

# Inspect applied versions
docker compose --profile migrate run --rm flyway info
```

## Demo database

```bash
./server/docker/reset-test-db.sh demo
docker compose --profile demo run --rm flyway-demo
docker compose --profile demo run --rm flyway-demo info
```

- Demo database: `shijiangtai_demo` (`shijiangtai` / `shijiangtai_dev`).
- All demo accounts use the password `Demo1234!`.
- Example usernames: `admin.a`, `teacher.a1`, `teacher.a2`, `student.a01` ... `student.a10`, `admin.b`, `teacher.b1`, `student.b01`.
- The demo seed is only loaded from `src/main/resources/db/demo`; it is never part of the normal test/production locations.
- The demo seed references MinIO object keys. `./server/docker/seed-demo-assets.sh` uploads small generated sample media when `ffmpeg` is available; otherwise it logs a skip.

## Demo data shape

- 2 schools, 16 accounts, 3 majors, 3 classes, 4 courses.
- 5 tasks: 3 published (free / scenario / interview), 1 draft, 1 closed.
- 12 task assignments, 7 virtual students.
- 6 sessions (5 submitted task sessions + 1 selected training session), 5 submissions.
- Recordings, recording streams, session texts, frames, and events.
- 5 evaluations covering completed / pending / partial / failed; 1 teacher score; 5 final scores covering completed / pending_ai / pending_teacher / ai_partial / ai_failed.
- 1 training template / meta / record / material; 1 ops cleanup job.

## Notes

- Migrations are forward-only. Reset the test/demo database before re-running an edited migration during authoring.
- `V10__foreign_keys.sql` adds all cross-schema FKs; `V11__indexes.sql` adds indexes and partial unique indexes.
- `V12__seed_account_types_and_permissions.sql` seeds 3 account types, 108 route permission rows, and the role matrix.
