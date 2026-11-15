# API requests and flows

What the platform offers, how requests flow, how to try them in Postman.

## Documents

| Document | What it has |
|---|---|
| [Identity Service API](IDENTITY-API.md) | Calls, tokens, flows, requests |
| [Journey Service API](JOURNEY-API.md) | Calls, rules, scans, events, requests |
| [README](../../README.md) | Setup, variables, limits, observability |

## At a glance

| Service | Address | Gateway path |
|---|---|---|
| api-gateway | `http://localhost:8080` | clients call this |
| identity-service | `http://localhost:1112` | `/api/v1/auth/**` |
| journey-service | `http://localhost:1113` | `/api/v1/bags/**`, `/api/v1/scans/**` |

Success answers look like `{"success":true,"data":...,"timestamp":...}`. Errors use the shared shape, see [response format](../../README.md#api-response-format).

```mermaid
flowchart LR
    C[Client] --> GW[api-gateway :8080]
    GW -- /api/v1/auth --> ID[identity-service :1112]
    GW -- /api/v1/bags and /scans --> J[journey-service :1113]
    GW --> RD[(Redis: rate limits)]
    ID --> PG[(PostgreSQL)]
    J --> PG
    J --> K[(Kafka)]
    GW -.- EU[Eureka registry]
    ID -.- EU
    J -.- EU
```

## Postman collection

Two files in `postman/`:

| File | Content |
|---|---|
| `AeroTrace.postman_collection.json` | Requests with test scripts |
| `AeroTrace-local.postman_environment.json` | Variables: URLs, accounts, tokens |

**Set up**

1. Start the stack with Docker Compose. The `dev` context creates dummy accounts.
2. Import both files into Postman.
3. Select the environment "AeroTrace - local".
4. Run a service folder top to bottom, 150 ms or more between requests.

**Identity Service folders**

| Folder | What it does |
|---|---|
| 1 Register | Passenger, staff, refusals |
| 2 Login | Passenger, staff, refusals |
| 3 Refresh token | Rotation and refusals |
| 4 Forgot password | Starts a reset |
| 5 Reset password | Resets, then logs in again |
| 6 Logout | Revokes the refresh token |
| 7 Gateway rules | Unexposed paths are refused |
| 8 Direct (port 1112) | Health and login via `identityUrl` |

**Journey Service folders** (one per actor, in run order)

| Folder | What it does |
|---|---|
| 1 Agent (check-in desk) | Sign in, register a bag, send scans, read |
| 2 Passenger (owner of the bag) | Sign in, staff calls refused, read own journey |
| 3 Other passenger | Sign in, cannot read the bag |
| 4 Service checks (port 1113) | Health and OpenAPI via `journeyUrl` |

**Notes**

| Topic | What to know |
|---|---|
| Tokens | Test scripts store them in the environment |
| Identity 1.2 | Needs `adminUsername` and `adminPassword` |
| Default admin | The dev dummy admin |
| First admin | By hand, see [README](../../README.md#first-administrator-without-the-dev-context) |
| Journey scans | `Idempotency-Key` generated per scan |
| Journey | Needs Compose with the `dev` context |
| Journey variables | `journeyAgentUsername`, `journeyPassengerUsername`, `journeyOtherPassengerUsername` |
| Journey passwords | `journeyAgentPassword`, `journeyPassengerPassword` |
| Journey reference | `journeyPassengerRef`, seeded `P-00000000000000000001` |
| Base URL | `baseUrl` is `http://localhost:8080` |

**With newman**

```bash
newman run postman/AeroTrace.postman_collection.json -e postman/AeroTrace-local.postman_environment.json --delay-request 150 --folder "Identity Service"
```

Use `--folder "Journey Service"` for the journey folder.

**Run order of the collection**

```mermaid
flowchart LR
    subgraph Identity Service
        I1[1 Register] --> I2[2 Login] --> I3[3 Refresh token] --> I4[4 Forgot password] --> I5[5 Reset password] --> I6[6 Logout] --> I7[7 Gateway rules] --> I8[8 Direct :1112]
    end
    subgraph Journey Service
        J1[1 Agent] --> J2[2 Passenger - owner] --> J3[3 Other passenger] --> J4[4 Service checks :1113]
    end
```

The tables of every request are in [Identity Service API](IDENTITY-API.md#every-request-in-the-postman-collection) and [Journey Service API](JOURNEY-API.md#every-request-in-the-postman-collection).
