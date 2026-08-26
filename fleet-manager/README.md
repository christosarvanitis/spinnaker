# fleet-manager

A minimal instance-assignment service for a **Spinnaker fleet** — several independent
Spinnaker instances presented to users as one URL. It answers the edge router's
"which instance should this user land on?" question, and serves a small page where the
user picks.

> **This is a validation fixture, not a production component.** It exists so the fleet
> routing in [`gate/docs/fleet.md`](../gate/docs/fleet.md) can be exercised end to end. A
> real fleet manager owns capacity, draining, blast-radius and rollout policy; this one
> just remembers a choice in a cookie. It is deliberately not wired into `settings.gradle`
> or any CI workflow.
>
> To build your own, the interface is specified in
> [The fleet manager contract](../gate/docs/fleet.md#the-fleet-manager-contract), and
> [What a real fleet manager should own](../gate/docs/fleet.md#what-a-real-fleet-manager-should-own)
> covers the operational surface this fixture deliberately omits. `k8s/` here is a reusable
> kustomize base you can point at your own image.

Pure Go standard library, no dependencies.

## The contract it implements

The edge (see [`gate/docs/fleet-example/`](../gate/docs/fleet-example/README.md)) consults
this service via nginx `auth_request`, and **only** when the browser has no
`SESSION_INST_*` cookie. Once a user has a Spinnaker session, the cookie name alone routes
them and this service is off the hot path entirely.

```
GET /assign
  X-Fleet-Mode: navigate | api
  Cookie: SPIN_FLEET_CHOICE=<instance-id>

204 + X-Spinnaker-Instance: <id>   a valid choice exists
401                                nothing chosen yet, and this is a navigation
                                   -> the edge turns this into a redirect to the picker
204 + X-Spinnaker-Instance: <default>
                                   nothing chosen yet, but this is an API call, which must
                                   never be answered with an HTML page
```

`auth_request` is a subrequest: its body is discarded and its `Set-Cookie` never reaches
the browser. That is why the picker cannot live inside `/assign`, and why the 401 exists —
it is the only way to hand control back to the browser. The full sequence:

```
1  browser -> global URL                (no cookies at all)
2  edge    -> /assign                   (auth_request)
3  /assign -> 401
4  edge    -> 302 /_fleet/?return=/     (error_page 401 = @fleet_chooser)
5  browser -> the picker
6  user picks inst-2 -> POST /_fleet/choose
7  /choose -> Set-Cookie SPIN_FLEET_CHOICE=inst-2, 302 back to return
8  browser -> global URL; /assign now answers 204 + X-Spinnaker-Instance: inst-2
9  edge routes to inst-2, which mints SESSION_INST_2
10 from here on the cookie name routes; this service is never consulted again
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/assign` | The `auth_request` endpoint described above. |
| GET | `/` | The picker. Also reachable directly to switch instances. |
| POST | `/choose` | `instance=`, `return=` → records the choice, redirects. |
| POST | `/reset` | Clears the choice. Note this does *not* move an already-authenticated user: their `SESSION_INST_*` cookie still routes them to the instance holding their session. |
| GET | `/instances` | JSON: configured instances, the default, and the current choice. |
| GET | `/healthz` | Liveness. |

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `FLEET_INSTANCES` | *(required)* | `inst-1=Instance One,inst-2=Instance Two`. A bare `inst-1` uses the id as its label. |
| `FLEET_DEFAULT_INSTANCE` | first entry | Used for `api`-mode requests with no choice. |
| `FLEET_LISTEN` | `:8080` | |
| `FLEET_COOKIE_NAME` | `SPIN_FLEET_CHOICE` | |
| `FLEET_COOKIE_DOMAIN` | *(none)* | Set to the **shared parent domain**, e.g. `.spinnaker.example.com`, so the choice is visible at the global URL and at every instance hostname. |
| `FLEET_COOKIE_SECURE` | `true` | **Must be `false` for a plain-HTTP local harness**, or the browser silently discards the cookie and the picker loops. Always `true` in a real deployment. |

Invalid configuration is fatal at startup rather than silently defaulted — a missing
`FLEET_INSTANCES` or an unknown `FLEET_DEFAULT_INSTANCE` exits immediately with the reason.

## Running it

```bash
go run . # with the env above
# or
docker build -t fleet-manager . && docker run --rm -p 8080:8080 \
  -e FLEET_INSTANCES='inst-1=Instance One,inst-2=Instance Two' \
  -e FLEET_COOKIE_SECURE=false fleet-manager
```

```bash
go test ./...   # 57 cases
go vet ./...
gofmt -l .
```

## End-to-end harness

`e2e/` brings up the **real** edge config plus this service against two stub instances, and
asserts the whole flow:

```bash
./e2e/run-e2e.sh      # needs docker + curl
```

It does not copy the edge config. `run-e2e.sh` generates it from
`gate/docs/fleet-example/nginx-fleet-edge.conf`, changing only the upstream addresses, so
the harness can never drift away from the documented configuration and quietly test
something else.

Two assertions are worth calling out:

- **The fleet manager is stopped mid-run** and traffic must continue unaffected. That is the
  unambiguous proof of step 10 above — if the edge still consulted it, `auth_request` would
  fail and the request would 500.
- **An upstream 401 must stay a 401**, not become a picker redirect. This relies on nginx's
  `proxy_intercept_errors` defaulting to off, so only the `auth_request` 401 is intercepted.
  Easy to regress, cheap to assert.

The harness was itself verified by mutation: removing `error_page 401` and inverting the
upstream map each produced exactly 4 targeted failures, confirming it detects regressions
rather than passing vacuously.

### Swapping in real Gates

Replace `e2e/nginx/stubs.conf` and repoint the `upstream` blocks. What stubs cannot cover is
Gate's own `FleetDirectAccessFilter`. Note that two real Gates with
`security.basicform.enabled` exercise the filter and real session cookies, but **cannot**
exercise the non-admin redirect unless Fiat is also running — `FiatPermissionEvaluator
.isAdmin()` returns `true` for everyone when Fiat is disabled, so the guardrail never fires.

## Security notes

Small service, but it writes a cookie that steers routing, so:

- **`return=` accepts a path only**, never an absolute URL. Anything with a scheme, a
  leading `//`, a backslash or a control character is rejected. This removes the
  open-redirect class outright, and is why the edge passes `return=$uri` rather than
  `$request_uri`. Consequence: the query string of a first visit is not preserved — the
  picker appears once, so everything afterwards keeps it.
- **A forged `SPIN_FLEET_CHOICE` is never echoed back.** The value ends up in
  `X-Spinnaker-Instance`, which selects an nginx upstream, so it is always resolved against
  the configured instance list first. nginx's `map` would fall back safely anyway; the
  service does not rely on that.
- **Instance ids are charset-restricted at config load** (`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$`),
  so header injection is impossible by construction.
- Cookies are `HttpOnly`, `SameSite=Lax`, `Path=/`.
- **No CSRF token on `/choose`**, deliberately. The worst a cross-site post achieves is
  pinning someone to an instance — routing changes, no privilege does — and `SameSite=Lax`
  already blocks it. A production fleet manager making real placement decisions should add
  one.
