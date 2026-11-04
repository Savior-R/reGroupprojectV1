# Server / Database

This directory currently contains the complete Flyway schema, system seed, and demo seed. The Spring Boot application scaffold is added by the M3A implementation plan.

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
