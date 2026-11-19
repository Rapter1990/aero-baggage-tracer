# Identity Service API

[← API overview](API.md) · [Journey Service API](JOURNEY-API.md) · [README](../../README.md)

Registers users, signs them in, issues tokens.

## Contents

| Section | What it covers |
|---|---|
| [At a glance](#at-a-glance) | Addresses, database, limits |
| [Calls](#calls) | The six auth requests |
| [Tokens and keys](#tokens-and-keys) | Token rules, your own key pair |
| [Flows](#flows) | Sequence diagrams |
| [Every request](#every-request-in-the-postman-collection) | Postman requests, expected status |

## At a glance

| Topic | Value |
|---|---|
| Through the gateway | `http://localhost:8080/api/v1/auth` |
| Directly | `http://localhost:1112/api/v1/auth` |
| Database | PostgreSQL, schema `identity` |
| Success answer | `{"success":true,"data":...,"timestamp":...}` |
| Errors | Shared shape, see [response format](../../README.md#api-response-format) |
| Limits and lockout | See [README rate limits](../../README.md#rate-limits-and-lockout-of-the-authentication-endpoints) |

## Calls

| Call | Token | Body | What it does |
|---|---|---|---|
| `POST /register` | none (staff role: ADMIN) | `username`, `email`, `password`, `fullName`, `role` (optional) | Creates an account (201). `createdBy` in the answer is the id of the admin who registered it, or `anonymous` for a self-registration |
| `POST /login` | none | `usernameOrEmail`, `password` | Returns tokens, expiry times, roles |
| `POST /refresh-token` | none | `refreshToken` | Returns a new token pair |
| `POST /forgot-password` | none | `usernameOrEmail` | Starts a password reset |
| `POST /reset-password` | none | `resetToken`, `newPassword` | Sets a new password |
| `POST /logout` | none | `refreshToken` | Revokes the refresh token |

Rules per call:

| Call | Rule |
|---|---|
| `register` | Without a token: `PASSENGER` only |
| `register` | `AGENT`, `DISPATCHER`, `ADMIN` need an ADMIN token |
| `refresh-token` | A refresh token works once |
| `refresh-token` | Reusing a used one ends all sessions |
| `forgot-password` | Same answer for unknown accounts |
| `reset-password` | Reset token works once, for 15 minutes |
| `reset-password` | Ends every session of the user |
| `logout` | Always answers 200 |

## Tokens and keys

| Topic | Rule |
|---|---|
| Passwords | 12 to 72 bytes |
| Access token | Valid 15 minutes |
| Refresh token | Valid 7 days |
| Token type | RS256 JWT, same issuer |
| Audience | Refresh and access tokens differ |
| Cross-use | Refused in both directions |
| Storage | Refresh and reset tokens: SHA-256 hashes only |
| Development key | Ships with identity-service |
| Development key | Private key is in this repository |
| Development key | Anyone can create valid tokens |
| Development key | Local stack only. Warned at every start |
| Key set | No JWKS endpoint, no issuer setting |

To use your own key pair:

1. Create it:

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
   openssl rsa -in private.pem -pubout -out public.pem
   ```

2. Mount the files into the containers.
3. Set the paths (for example `file:/run/secrets/private.pem`):

| Service | Variables |
|---|---|
| identity-service | `IDENTITY_JWT_PRIVATE_KEY_LOCATION`, `IDENTITY_JWT_PUBLIC_KEY_LOCATION` |
| api-gateway | `JWT_PUBLIC_KEY_LOCATION` (public key only) |
| journey-service | `IDENTITY_JWT_PUBLIC_KEY_LOCATION` (public key only) |

## Flows

The diagrams are Mermaid. GitHub and IntelliJ render them.

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

A refresh token that was already used (more than 10 seconds ago) and is presented again counts as theft. Every session of that user ends.

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

## Every request in the Postman collection

Generated from `postman/AeroTrace.postman_collection.json`.

| Column | Meaning |
|---|---|
| Step | Number before the request name |
| Call | Path via gateway, unless marked direct |
| Expect | Status the test script checks |

### 1. Register

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

### 2. Login

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

### 3. Refresh token

| Step | Request | Call | Expect |
|---|---|---|---|
| 3.1 | Refresh token (rotation) | `POST /api/v1/auth/refresh-token` | 200 |
| 3.2 | Refresh token again with the used-up token (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 3.3 | Refresh with an unknown token | `POST /api/v1/auth/refresh-token` | 401 |
| 3.4 | Refresh with an empty body | `POST /api/v1/auth/refresh-token` | 400 |
| 3.5 | Refresh with an access token (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 3.6 | Use refresh token as access token (refused) | `POST /api/v1/auth/register` | 401 |

### 4. Forgot password

| Step | Request | Call | Expect |
|---|---|---|---|
| 4.1 | Forgot password (existing account) | `POST /api/v1/auth/forgot-password` | 200 |
| 4.2 | Forgot password for an unknown account | `POST /api/v1/auth/forgot-password` | 200 |
| 4.3 | Forgot password with an empty body | `POST /api/v1/auth/forgot-password` | 400 |

### 5. Reset password

| Step | Request | Call | Expect |
|---|---|---|---|
| 5.1 | Reset password with a weak new password | `POST /api/v1/auth/reset-password` | 400 |
| 5.2 | Reset password | `POST /api/v1/auth/reset-password` | 200 |
| 5.3 | Reset password again with the used token | `POST /api/v1/auth/reset-password` | 400 |
| 5.4 | Reset password with an unknown token | `POST /api/v1/auth/reset-password` | 400 |
| 5.5 | Refresh after the reset (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 5.6 | Login with the old password (refused) | `POST /api/v1/auth/login` | 401 |
| 5.7 | Login with the new password | `POST /api/v1/auth/login` | 200 |

### 6. Logout

| Step | Request | Call | Expect |
|---|---|---|---|
| 6.1 | Logout | `POST /api/v1/auth/logout` | 200 |
| 6.2 | Logout again (still 200) | `POST /api/v1/auth/logout` | 200 |
| 6.3 | Refresh after logout (refused) | `POST /api/v1/auth/refresh-token` | 401 |
| 6.4 | Logout with an unknown token (still 200) | `POST /api/v1/auth/logout` | 200 |

### 7. Gateway rules

| Step | Request | Call | Expect |
|---|---|---|---|
| 7.1 | A path that is not exposed (GET /me) | `GET /api/v1/auth/me` | 401 |

### 8. Identity service direct (port 1112)

| Step | Request | Call | Expect |
|---|---|---|---|
| 8.1 | Health | `GET /actuator/health` (direct :1112) | 200 |
| 8.2 | Login directly | `POST /api/v1/auth/login` (direct :1112) | 401 |
