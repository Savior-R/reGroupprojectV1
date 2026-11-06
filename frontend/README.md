# M3 Frontend (Vue 3 + TypeScript + Vite)

M3 frontend slice: shared app shell, student task entry, classroom / interview rooms with
MediaRecorder upload, and the review / replay page with synchronized multi-stream playback.

The backend (M3A/M3B/M3C) lives in `server/`; start it with `docker compose up -d --build`
from the repository root before using the pages.

## Requirements

- Node.js 22.22+ (developed on v22.22.1) and npm 9+
- A running backend at `http://localhost:8080` (API + control WebSocket)
- Chrome or Edge for recording / replay; other browsers show a blocking message

## Commands

From the repository root:

```bash
npm --prefix frontend install
npm --prefix frontend run dev -- --host 127.0.0.1   # http://127.0.0.1:5173
npm --prefix frontend run test -- --run              # Vitest (jsdom)
npm --prefix frontend run build                      # vue-tsc -b && vite build -> frontend/dist
npm --prefix frontend run preview -- --host 127.0.0.1
```

The same scripts work from `frontend/` (`npm run dev`, `npm run test -- --run`,
`npm run build`, `npm run preview`).

## Dev proxy

`vite.config.ts` proxies `/api` to `http://localhost:8080` and `/ws` to
`ws://localhost:8080` (WebSocket upgrade). If the backend is not running, pages render
their error state with `HTTP_502`.

## Dev identity (M1 JWT not landed)

`src/stores/auth.ts` injects `X-Account-Id`, `X-School-Id`, and `X-Account-Type` from a
dev preset. The default is student; open the app with `?dev_role=teacher` on first load
to use the teacher identity:

```text
http://127.0.0.1:5173/student/tasks?dev_role=teacher
```

- Student: `00000000-0000-0000-0000-000000000020` / `STUDENT`
- Teacher: `00000000-0000-0000-0000-000000000010` / `TEACHER`
- School: `00000000-0000-0000-0000-000000000001`

The selected role is persisted in `sessionStorage` under `m3.dev-identity`; the query
parameter is only honoured when no role is stored yet. Clear that key to switch roles.
These are development placeholders; M1 replaces them with JWT authentication.

## Routes

- `/student/tasks` — student task list
- `/student/tasks/:taskId` — task detail and session start / resume
- `/student/sessions/:sessionId/classroom` — classroom room (camera + screen + audio)
- `/student/sessions/:sessionId/interview` — interview room (camera + audio)
- `/student/sessions/:sessionId/review` — review, synchronized replay, and submit
- `/403`, plus a catch-all 404

## Tests and build evidence

Current slice: 14 Vitest files / 111 tests passing; production build passing with only the
pre-existing Element Plus chunk-size warning. Full results, browser-smoke blockers, and
known gaps are recorded in
`docs/team/evidence/2026-11-04-m3-frontend-classroom-replay.md`.