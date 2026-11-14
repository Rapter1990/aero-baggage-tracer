# API requests and flows

Every call the platform answers so far, how they fit together and how to try them. All calls go through the api-gateway (`http://localhost:8080`), which routes `/api/v1/auth/**` to the identity-service and `/api/v1/bags/**` and `/api/v1/scans/**` to the journey-service. Success answers are wrapped as `{"success":true,"data":...,"timestamp":...}`; failures use the shared error shape (see [API response format](../README.md#api-response-format)). Limits and lockout of the authentication endpoints are in [the README](../README.md#rate-limits-and-lockout-of-the-authentication-endpoints).

Contents: [Identity Service API](#identity-service-api) · [Journey Service API](#journey-service-api) · [Flows](#flows) · [Postman collection](#postman-collection) · [Every request in the collection](#every-request-in-the-collection)

## Identity Service API

### Calls

The identity-service listens on port 1112 (published by Compose, so its own endpoints, such as `/actuator/health` and `/v3/api-docs`, can be called directly). The authentication API (all under `http://localhost:8080/api/v1/auth` through the gateway, or `http://localhost:1112/api/v1/auth` directly, answers wrapped as `{"success":true,"data":...,"timestamp":...}`):

| Call | What it does |
|---|---|
| `POST /register` `{username,email,password,fullName[,role]}` | Creates an account (201). Only `PASSENGER` without a token; `AGENT`, `DISPATCHER` or `ADMIN` need an ADMIN access token |
| `POST /login` `{usernameOrEmail,password}` | Returns `accessToken`, `refreshToken`, `tokenType`, the expiry times and the roles |
| `POST /refresh-token` `{refreshToken}` | Exchanges a refresh token for a new pair. A refresh token works once; presenting a used one ends every session of that user |
| `POST /forgot-password` `{usernameOrEmail}` | Starts a password reset. The answer is the same whether or not the account exists |
| `POST /reset-password` `{resetToken,newPassword}` | Sets a new password with a reset token (once, 15 minutes) and ends every session of the user |
| `POST /logout` `{refreshToken}` | Revokes the refresh token; always succeeds |

### Tokens and keys

Passwords are 12 to 72 bytes. A refresh token is a signed JWT like the access token (RS256, same issuer) but with its own audience, so it is refused as an access token and the other way round; refresh and reset tokens are stored only as SHA-256 hashes.

Tokens are signed with the development key pair that ships inside identity-service unless you provide your own. Its private key is in this repository, so anyone can mint tokens that the platform accepts: fine for a throwaway local stack, never for anything else (identity-service logs a warning at every start while it is in use). To use your own pair:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
```

then mount the files into the containers and set `IDENTITY_JWT_PRIVATE_KEY_LOCATION` and `IDENTITY_JWT_PUBLIC_KEY_LOCATION` (identity-service) and `JWT_PUBLIC_KEY_LOCATION` (api-gateway, public key only) to their paths (for example `file:/run/secrets/private.pem`). The gateway verifies the tokens with that public key; there is no key-set (JWKS) endpoint and no issuer setting.

## Journey Service API


The journey-service (port 1113, schema `journey` in PostgreSQL) registers bags with an itinerary snapshot, ingests scans and answers the journey of a bag. Through the gateway the calls are under `/api/v1/bags` and `/api/v1/scans`; all need a bearer token and answer `{"success":true,"data":...,"timestamp":...}`.

| Call | Roles | What it does |
|---|---|---|
| `POST /api/v1/bags` `{bagTag,passengerRef,legs[]}` | AGENT, ADMIN | Registers a bag (201). `bagTag` is 10 digits and unique (`409 BAG_TAG_DUPLICATE`); airports are 3-letter IATA codes; legs must be chained, in order and respect the minimum connection time (`JOURNEY_MIN_CONNECTION_TIME`, default `45m`) |
| `GET /api/v1/bags/{bagTag}/journey` | all roles | The latest accepted state and the immutable scan history. A passenger sees only a bag registered to their own `passengerRef`; any other bag answers `404`, like an unknown one |
| `POST /api/v1/scans` `{protocol,version?,payload}` with an `Idempotency-Key` header | AGENT, DISPATCHER, ADMIN | Accepts a scan (202 `QUEUED`) and queues `baggage.scan.received.v1`. `protocol` is `BARCODE`, `RFID` or `CONVEYOR`, each with its own payload format (see the handlers in `journey-service/.../scan`). The same key again returns the original scan and creates nothing new |

Times are stored in UTC with the zone the device reported. A scan older than `JOURNEY_LATE_EVENT_GRACE` (default `10m`) on arrival is flagged `lateEvent` (BR-06); it is kept and published, but never replaces a newer state. Events leave through a transactional outbox: a relay (every `JOURNEY_OUTBOX_RELAY_DELAY`, default `200ms`) publishes them to Kafka at least once, keyed by bag tag. One relay runs at a time across all replicas (a PostgreSQL advisory lock), so the events of a bag leave in order; an event the broker refuses for good (for example too large) is parked and counted in `journey.outbox.parked`, a temporary broker problem stops the run and is retried.

Known limitations: scans must come from a staff account (no separate device identity yet); the relay is a single stream (throughput is one relay, sending in order); the journey lists at most the newest 500 scans (`historyTruncated` says so); a reused idempotency key for a different scan is a `409`; tokens are checked against one static identity public key (no key set or rotation yet), and the bundled development key is used when none is configured.

## Flows

The diagrams are Mermaid; GitHub and IntelliJ render them.

### 1. Passenger: register, sign in, call the API, refresh, sign out

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant GW as api-gateway :8080
    participant ID as identity-service :1112
    participant DB as PostgreSQL (identity)

    C->>GW: POST /api/v1/auth/register {username, email, password, fullName}
    GW->>ID: route to identity-service
    ID->>DB: insert user (BCrypt hash, passengerRef)
    ID-->>C: 201 account, role PASSENGER

    C->>GW: POST /api/v1/auth/login {usernameOrEmail, password}
    GW->>ID: route
    ID->>DB: check password, write audit event
    ID-->>C: 200 accessToken (15 min), refreshToken (7 days), roles

    Note over C,GW: Every other call carries Authorization: Bearer accessToken.<br/>The gateway checks signature (RS256), issuer, audience and expiry.

    C->>GW: POST /api/v1/auth/refresh-token {refreshToken}
    GW->>ID: route
    ID->>DB: revoke the used token, store the hash of the new one
    ID-->>C: 200 new pair (a refresh token works once)

    C->>GW: POST /api/v1/auth/logout {refreshToken}
    ID->>DB: revoke the refresh token
    ID-->>C: 200 (always)
```

Presenting a refresh token that was already used (more than 10 seconds ago) is treated as theft: every session of that user ends.

### 2. Staff accounts and password reset

```mermaid
sequenceDiagram
    autonumber
    actor A as Administrator
    actor U as User
    participant GW as api-gateway
    participant ID as identity-service

    A->>GW: POST /auth/login (admin credentials)
    GW->>ID: route
    ID-->>A: accessToken with role ADMIN
    A->>GW: POST /auth/register {..., role: AGENT} with the ADMIN token
    GW->>ID: route
    ID-->>A: 201 staff account (without a token or with a passenger token: 401 / 403)

    U->>GW: POST /auth/forgot-password {usernameOrEmail}
    GW->>ID: route
    ID-->>U: 200 resetToken (same answer for unknown accounts)
    U->>GW: POST /auth/reset-password {resetToken, newPassword}
    ID-->>U: 200, every refresh token of the user is revoked
    U->>GW: POST /auth/login (old password refused, new one works)
```

### 3. Journey: register a bag

```mermaid
sequenceDiagram
    autonumber
    actor AG as Agent (check-in)
    participant GW as api-gateway
    participant J as journey-service :1113
    participant DB as PostgreSQL (journey)

    AG->>GW: POST /api/v1/bags {bagTag, passengerRef, legs[]} + Bearer token
    GW->>J: route (journey-service)
    J->>J: role AGENT or ADMIN? (else 403, no token 401)
    J->>J: validate formats, IATA codes, chained legs in order, minimum connection time
    J->>DB: insert baggage, itinerary and legs in one transaction
    alt the tag already exists (also under parallel requests)
        DB-->>J: unique constraint on bag_tag
        J-->>AG: 409 BAG_TAG_DUPLICATE
    else
        J-->>AG: 201 bagTag and legs
    end
```

### 4. Journey: ingest a scan (idempotent) and publish the event

```mermaid
sequenceDiagram
    autonumber
    actor D as Scanner (agent token)
    participant GW as api-gateway
    participant J as journey-service
    participant DB as PostgreSQL (journey)
    participant R as outbox relay
    participant K as Kafka

    D->>GW: POST /api/v1/scans + Idempotency-Key {protocol, version, payload}
    GW->>J: route
    J->>J: handler for protocol and version (BARCODE, RFID, CONVEYOR) turns the payload into one scan, time in UTC
    J->>DB: lock the bag row (one scan of a bag at a time, in any replica)
    J->>DB: record the Idempotency-Key in processed_event
    alt the key was seen before
        J-->>D: 202 original answer, duplicate = true (409 if it was another scan)
    else new scan
        J->>DB: insert scan_audit (append only), flag LATE_EVENT if older than the grace
        J->>DB: move the bag's latest state forward, only if the scan is newer
        J->>DB: insert outbox_event (baggage.scan.received.v1)
        J-->>D: 202 QUEUED
    end

    loop every 200 ms, one relay at a time (advisory lock)
        R->>DB: claim the oldest pending events
        R->>K: send, key = bagTag, wait for the broker's acknowledgement
        R->>DB: mark published (a temporary failure stops the run and is retried, a refused event is parked)
    end
```

### 5. Journey: what happens to a scan

```mermaid
flowchart TD
    A[POST /scans] --> B{Agent, dispatcher or admin token?}
    B -- no token --> E401[401]
    B -- other role --> E403[403]
    B -- yes --> C{Idempotency-Key present and valid?}
    C -- no --> E400[400]
    C -- yes --> D{payload valid for protocol and version?}
    D -- no --> E400
    D -- yes --> F{bag registered?}
    F -- no --> E404[404]
    F -- yes --> G{event time in the future beyond the skew?}
    G -- yes --> E400
    G -- no --> H{key seen before?}
    H -- same scan --> R[202 original answer, duplicate]
    H -- another scan --> E409[409]
    H -- new --> I[store in scan_audit, queue the event]
    I --> J{older than the late-event grace on arrival?}
    J -- yes --> K[flag LATE_EVENT]
    J -- no --> L[not late]
    K --> M{newer than the current state?}
    L --> M
    M -- yes, or same time with a higher sequence --> N[becomes the latest state]
    M -- no --> O[stays in the history only]
    N --> P[202 QUEUED]
    O --> P
```

### 6. Journey: who sees which bag

```mermaid
flowchart TD
    A[GET /bags/tag/journey] --> B{valid token?}
    B -- no --> E401[401]
    B -- yes --> C{tag is 10 digits?}
    C -- no --> E400[400]
    C -- yes --> D{bag exists?}
    D -- no --> E404[404]
    D -- yes --> E{role}
    E -- agent, dispatcher, admin --> OK[200 latest state, itinerary, history]
    E -- passenger --> F{passengerRef of the token equals the bag's?}
    F -- yes --> OK
    F -- no --> E404b[404, same answer as an unknown bag]
```

## Postman collection


`postman/AeroTrace.postman_collection.json` and `postman/AeroTrace-local.postman_environment.json` call the platform through the gateway (`baseUrl` defaults to `http://localhost:8080`). The collection `AeroTrace` has one folder per service: **Identity Service** (1 Register, 2 Login, 3 Refresh token, 4 Forgot password, 5 Reset password, 6 Logout, 7 Gateway rules, 8 the identity-service directly at `identityUrl`, default `http://localhost:1112`) and **Journey Service** (1 Sign in, 2 Register a bag, 3 Scans, 4 Journey, 5 the journey-service directly at `journeyUrl`, default `http://localhost:1113`). Import both files into Postman, select the "AeroTrace - local" environment and run a service folder top to bottom with a delay of 150 ms or more between requests (the gateway allows about 10 requests per second per address).

In the Identity Service folder, 1 Register walks a passenger through registration and the later folders log in, refresh, reset the password and log out; the test scripts store the tokens in the environment. The refusals (wrong password, validation, duplicate, unknown tokens) are in the same folders. Registering staff (1.2) needs `adminUsername` and `adminPassword` in the environment (the dev dummy admin by default, or the first admin made by hand, see [the README](../README.md#running-the-stack-with-docker-compose)).

The Journey Service folder (see [Journey Service API](#journey-service-api)) signs in the dev dummy agent and two dummy passengers (`journeyAgentUsername`, `journeyPassengerUsername`, `journeyOtherPassengerUsername`, passwords in `journeyAgentPassword` and `journeyPassengerPassword`), registers a bag, sends barcode, RFID and conveyor scans (an `Idempotency-Key` is generated per scan), reads the journey as staff, as the owner and as another passenger, and checks the refusals. It needs the compose stack with the `dev` Liquibase context, like Identity Service 1.2.

With newman: `newman run postman/AeroTrace.postman_collection.json -e postman/AeroTrace-local.postman_environment.json --delay-request 150 --folder "Identity Service"` (or `--folder "Journey Service"`).

Run order of the collection:

```mermaid
flowchart LR
    subgraph Identity Service
        I1[1 Register] --> I2[2 Login] --> I3[3 Refresh token] --> I4[4 Forgot password] --> I5[5 Reset password] --> I6[6 Logout] --> I7[7 Gateway rules] --> I8[8 Direct :1112]
    end
    subgraph Journey Service
        J1[1 Sign in] --> J2[2 Register a bag] --> J3[3 Scans] --> J4[4 Journey] --> J5[5 Direct :1113]
    end
```

## Every request in the collection

Generated from `postman/AeroTrace.postman_collection.json`; the step number is the name of the request in Postman. Paths are through the gateway unless marked direct. "Expect" is the status the request's test script asserts.

### Identity Service

#### 1. Register

**1.1 Passenger (public, no token)**

| Step | Request | Call | Expect |
|---|---|---|---|
| 1.1.1 | Register passenger | `POST /api/v1/auth/register` | 201 |
| 1.1.2 | Register passenger with the PASSENGER role spelled out | `POST /api/v1/auth/register` | 201 |

**1.2 Staff (needs an ADMIN token)**

| Step | Request | Call | Expect |
|---|---|---|---|
| 1.2.1 | Register agent | `POST /api/v1/auth/register` | 201 |
| 1.2.2 | Register dispatcher | `POST /api/v1/auth/register` | 201 |
| 1.2.3 | Register administrator | `POST /api/v1/auth/register` | 201 |

**1.3 Refusals**

| Step | Request | Call | Expect |
|---|---|---|---|
| 1.3.1 | Register with a weak password | `POST /api/v1/auth/register` | 400 |
| 1.3.2 | Register with empty fields | `POST /api/v1/auth/register` | 400 |
| 1.3.3 | Register with an invalid email | `POST /api/v1/auth/register` | 400 |
| 1.3.4 | Register with an unknown role | `POST /api/v1/auth/register` | 400 |
| 1.3.5 | Register a duplicate username | `POST /api/v1/auth/register` | 409 |
| 1.3.6 | Register a duplicate email | `POST /api/v1/auth/register` | 409 |
| 1.3.7 | Register a staff role without a token | `POST /api/v1/auth/register` | 401 |
| 1.3.8 | Register a staff role with a passenger token | `POST /api/v1/auth/register` | 403 |

#### 2. Login

**2.1 Passenger**

| Step | Request | Call | Expect |
|---|---|---|---|
| 2.1.1 | Login | `POST /api/v1/auth/login` | 200 |
| 2.1.2 | Login with the email address | `POST /api/v1/auth/login` | 200 |

**2.2 Staff**

| Step | Request | Call | Expect |
|---|---|---|---|
| 2.2.1 | Login as the agent | `POST /api/v1/auth/login` | 200 |
| 2.2.2 | Login as the dispatcher | `POST /api/v1/auth/login` | 200 |
| 2.2.3 | Login as the new administrator | `POST /api/v1/auth/login` | 200 |
| 2.2.4 | Login as the configured administrator | `POST /api/v1/auth/login` | 200 |

**2.3 Refusals**

| Step | Request | Call | Expect |
|---|---|---|---|
| 2.3.1 | Login with a wrong password | `POST /api/v1/auth/login` | 401 |
| 2.3.2 | Login as an unknown user | `POST /api/v1/auth/login` | 401 |
| 2.3.3 | Login with empty fields | `POST /api/v1/auth/login` | 400 |

#### 3. Refresh token

| Step | Request | Call | Expect |
|---|---|---|---|
| 3.1 | Refresh token (rotation) | `POST /api/v1/auth/refresh-token` | 200 |
| 3.2 | Refresh token again with the used-up token (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 3.3 | Refresh with an unknown token | `POST /api/v1/auth/refresh-token` | 401 |
| 3.4 | Refresh with an empty body | `POST /api/v1/auth/refresh-token` | 400 |
| 3.5 | Refresh with an access token (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 3.6 | Use the refresh token as an access token (refused) | `POST /api/v1/auth/register` | 401 |

#### 4. Forgot password

| Step | Request | Call | Expect |
|---|---|---|---|
| 4.1 | Forgot password (existing account) | `POST /api/v1/auth/forgot-password` | 200 |
| 4.2 | Forgot password for an unknown account | `POST /api/v1/auth/forgot-password` | 200 |
| 4.3 | Forgot password with an empty body | `POST /api/v1/auth/forgot-password` | 400 |

#### 5. Reset password

| Step | Request | Call | Expect |
|---|---|---|---|
| 5.1 | Reset password with a weak new password | `POST /api/v1/auth/reset-password` | 400 |
| 5.2 | Reset password | `POST /api/v1/auth/reset-password` | 200 |
| 5.3 | Reset password again with the used token | `POST /api/v1/auth/reset-password` | 400 |
| 5.4 | Reset password with an unknown token | `POST /api/v1/auth/reset-password` | 400 |
| 5.5 | Refresh after the reset (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 5.6 | Login with the old password (refused) | `POST /api/v1/auth/login` | 401 |
| 5.7 | Login with the new password | `POST /api/v1/auth/login` | 200 |

#### 6. Logout

| Step | Request | Call | Expect |
|---|---|---|---|
| 6.1 | Logout | `POST /api/v1/auth/logout` | 200 |
| 6.2 | Logout again (still 200) | `POST /api/v1/auth/logout` | 200 |
| 6.3 | Refresh after logout (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 6.4 | Logout with an unknown token (still 200) | `POST /api/v1/auth/logout` | 200 |

#### 7. Gateway rules

| Step | Request | Call | Expect |
|---|---|---|---|
| 7.1 | A path that is not exposed (GET /me) | `GET /api/v1/auth/me` | 401 |

#### 8. Identity service direct (port 1112)

| Step | Request | Call | Expect |
|---|---|---|---|
| 8.1 | Health | `GET /actuator/health` (direct :1112) | 200 |
| 8.2 | Login directly | `POST /api/v1/auth/login` (direct :1112) | 401 |

### Journey Service

#### 1. Sign in (dev dummy accounts)

| Step | Request | Call | Expect |
|---|---|---|---|
| 1.1 | Agent | `POST /api/v1/auth/login` | 200 |
| 1.2 | Passenger (owner of the bag) | `POST /api/v1/auth/login` | 200 |
| 1.3 | Other passenger | `POST /api/v1/auth/login` | 200 |

#### 2. Register a bag

| Step | Request | Call | Expect |
|---|---|---|---|
| 2.1 | Register a bag | `POST /api/v1/bags` | 201 |
| 2.2 | Register the same tag again (refused) | `POST /api/v1/bags` | 409 |
| 2.3 | Invalid tag (refused) | `POST /api/v1/bags` | 400 |
| 2.4 | Connection shorter than the minimum (refused) | `POST /api/v1/bags` | 400 |
| 2.5 | As a passenger (refused) | `POST /api/v1/bags` | 403 |
| 2.6 | Without a token (refused) | `POST /api/v1/bags` | 401 |

#### 3. Scans

| Step | Request | Call | Expect |
|---|---|---|---|
| 3.1 | Barcode scan (CHECK_IN) | `POST /api/v1/scans` | 202 |
| 3.2 | Same Idempotency-Key again (original answer) | `POST /api/v1/scans` | 202 |
| 3.3 | Same Idempotency-Key for another scan (refused) | `POST /api/v1/scans` | 409 |
| 3.4 | RFID scan (SORTING) | `POST /api/v1/scans` | 202 |
| 3.5 | Conveyor scan (LOADED) | `POST /api/v1/scans` | 202 |
| 3.6 | A late scan (flagged LATE_EVENT, state unchanged) | `POST /api/v1/scans` | 202 |
| 3.7 | Without an Idempotency-Key (refused) | `POST /api/v1/scans` | 400 |
| 3.8 | A bag that is not registered (refused) | `POST /api/v1/scans` | 404 |
| 3.9 | As a passenger (refused) | `POST /api/v1/scans` | 403 |
| 3.10 | Without a token (refused) | `POST /api/v1/scans` | 401 |

#### 4. Journey

| Step | Request | Call | Expect |
|---|---|---|---|
| 4.1 | As the agent | `GET /api/v1/bags/{journeyBagTag}/journey` | 200 |
| 4.2 | As the passenger who owns the bag | `GET /api/v1/bags/{journeyBagTag}/journey` | 200 |
| 4.3 | As another passenger (404, like an unknown bag) | `GET /api/v1/bags/{journeyBagTag}/journey` | 404 |
| 4.4 | Without a token (refused) | `GET /api/v1/bags/{journeyBagTag}/journey` | 401 |
| 4.5 | A malformed bag tag (refused) | `GET /api/v1/bags/12345/journey` | 400 |

#### 5. Journey service direct (port 1113)

| Step | Request | Call | Expect |
|---|---|---|---|
| 5.1 | Health | `GET /actuator/health` (direct :1113) | 200 |
| 5.2 | OpenAPI definition | `GET /v3/api-docs` (direct :1113) | 200 |
