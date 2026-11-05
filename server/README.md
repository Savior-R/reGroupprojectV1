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

## Recording / replay smoke

A session must be `in_progress` before it can be ended and submitted. Start it through the
control WebSocket above: send `recording.ready` with camera/screen/audio `ready`, then
`session.start`. Uploads then use only REST plus the presigned MinIO part URLs.

```bash
API=http://localhost:8080/api/v1
TEACHER=(-H 'X-Account-Id: 00000000-0000-0000-0000-000000000010'
         -H 'X-School-Id: 00000000-0000-0000-0000-000000000001'
         -H 'X-Account-Type: TEACHER')
STUDENT=(-H 'X-Account-Id: 00000000-0000-0000-0000-000000000020'
         -H 'X-School-Id: 00000000-0000-0000-0000-000000000001'
         -H 'X-Account-Type: STUDENT')

# 1) Create/publish a free task with a parsed .txt material.
cat > free.json <<'JSON'
{"mode":"free","topic":"荷塘月色","textbook_version":"部编版",
 "virtual_students":[{"name":"林小雨","avatar":"avatar-01","personality_text":"性格外向",
 "activity":0.60,"question_rate":0.50,"ability":0.70,"attention_baseline":0.50,
 "discipline_probability":0.40,"seat_no":1,"sort_order":1}]}
JSON
printf 'M3C recording smoke reference material.\n' > material.txt
TASK=$(curl -fsS -X POST "$API/courses/00000000-0000-0000-0000-000000000100/tasks" \
  "${TEACHER[@]}" -H 'Content-Type: application/json' -d @free.json | jq -r .task_id)
curl -fsS -X POST "$API/tasks/$TASK/material" "${TEACHER[@]}" \
  -F 'file=@material.txt;type=text/plain' >/dev/null
curl -fsS -X POST "$API/tasks/$TASK/publish" "${TEACHER[@]}" >/dev/null

# 2) Create the student's session, start it through the WS (recording.ready + session.start).
SESSION=$(curl -fsS -X POST "$API/tasks/$TASK/sessions" "${STUDENT[@]}" | jq -r .session_id)

# 3) For each stream: multipart start -> part-url -> PUT part -> complete.
for stream in camera screen audio; do
  START=$(curl -fsS -X POST \
    "$API/sessions/$SESSION/recordings/$stream/multipart/start" "${STUDENT[@]}")
  PART_URL=$(curl -fsS -X POST \
    "$API/sessions/$SESSION/recordings/$stream/multipart/part-url" "${STUDENT[@]}" \
    -H 'Content-Type: application/json' -d '{"part_number":1}' | jq -r .url)
  printf 'M3C-%s' "$stream" > "/tmp/$stream.part"
  SIZE=$(stat -c%s "/tmp/$stream.part")
  ETAG=$(curl -fsS -X PUT "$PART_URL" --data-binary "@/tmp/$stream.part" \
    -D - -o /dev/null | tr -d '\r"' | awk 'tolower($1) == "etag:" {print $2}')
  MIME=video/webm; [ "$stream" = audio ] && MIME=audio/webm
  curl -fsS -X POST \
    "$API/sessions/$SESSION/recordings/$stream/multipart/complete" "${STUDENT[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"parts\":[{\"part_number\":1,\"etag\":\"$ETAG\"}],\"duration_ms\":5000,\"size\":$SIZE,\"mime\":\"$MIME\"}" \
    | jq '{stream_type,status,object_key,parts_uploaded,bytes_uploaded}'
done

# 4) End, fetch the replay manifest, then submit.
curl -fsS -X POST "$API/sessions/$SESSION/end" "${STUDENT[@]}" | jq '{status,duration_ms}'
curl -fsS "$API/sessions/$SESSION/replay" "${STUDENT[@]}" | jq
curl -fsS -X POST "$API/sessions/$SESSION/submit" "${STUDENT[@]}" | jq
```

- Part URLs and replay URLs are presigned for 15 minutes (900 s). They carry
  `X-Amz-Credential` (access key id + scope) and `X-Amz-Signature`; the secret is never in the URL.
- Object keys follow `recordings/{school_id}/task/{task_id}/{session_id}/{stream_type}.webm`.
  Check them in MinIO:
  `docker compose exec minio mc alias set local http://127.0.0.1:9000 minioadmin minioadmin`
  then `docker compose exec minio mc ls --recursive local/shijiangtai/recordings`.
- Submission/stream rows are in `m3_classroom.submissions`, `m3_classroom.recordings` and
  `m3_classroom.recording_streams`. A full transcript is in
  `docs/team/evidence/2026-11-04-m3c-recording-replay.md` §5.

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
