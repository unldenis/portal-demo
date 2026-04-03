# Portal Demo

A full-stack reference implementation showing how to integrate Portal into a web application — authentication, payments, subscriptions, Cashu tokens, and profile management.

**Live demo:** [demo.getportal.cc](https://demo.getportal.cc/) · **Stack:** Kotlin + Javalin backend · Svelte frontend · [Portal Java SDK](https://github.com/PortalTechnologiesInc/java-sdk)

---

## What it demonstrates

- **Authentication** — Nostr key handshake flow: generate a QR code, user scans with Portal app, session established
- **Single payments** — request a one-time Lightning payment in sats or fiat
- **Subscriptions** — request and manage recurring payments
- **Cashu** — mint, send, and burn ecash tokens
- **Profiles** — fetch and display Nostr profiles

---

## Prerequisites

- Docker and Docker Compose
- [Portal REST service](https://github.com/PortalTechnologiesInc/lib) (`getportal/sdk-daemon`) running and reachable
- [Portal app](https://getportal.cc) on your phone

---

## Running with Docker

**Option A — portal-rest on the same host (recommended):**

```bash
docker compose --profile host up
```

Portal demo at `http://localhost:7070`. Connects to portal-rest at `http://localhost:3000`.

**Option B — portal-rest reachable via network:**

```bash
docker compose up
```

portal-rest must listen on `0.0.0.0:3000` (not `127.0.0.1`) so the container can reach it via `host.docker.internal`.

---

## Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REST_URL` | HTTP URL of the running portal-rest service | `http://host.docker.internal:3000` |
| `REST_TOKEN` | Auth token (must match `PORTAL__AUTH__AUTH_TOKEN` on portal-rest) | `my-static-token` |
| `DB_PATH` | Directory for the local SQLite database | `/home/user/portal/demo` |

Edit the values in `docker-compose.yml` or pass them via environment before running.

---

## Running portal-rest

If you don't have portal-rest running yet:

```bash
docker run -d -p 3000:3000 \
  -e PORTAL__AUTH__AUTH_TOKEN=my-static-token \
  -e PORTAL__NOSTR__PRIVATE_KEY=your-nostr-private-key-hex \
  getportal/sdk-daemon:0.4.1
```

> Generate a Nostr private key with `nak generate` or any Nostr tool.

---

## Running from source

```bash
# Backend (requires JDK 17+, Maven)
mvn package -DskipTests
java -jar target/portal-demo-1.0-SNAPSHOT.jar

# Frontend (in a separate terminal)
cd frontend
npm install
npm run dev
```

Set `REST_URL`, `REST_TOKEN`, and `DB_PATH` as environment variables before starting the backend.

---

## Related

- [Portal lib & REST service](https://github.com/PortalTechnologiesInc/lib) — source for `getportal/sdk-daemon` and the TypeScript SDK
- [Java SDK](https://github.com/PortalTechnologiesInc/java-sdk) — used by this demo (v0.4.1)
- [Documentation](https://portaltechnologiesinc.github.io/lib/)
