# Running a fleet of Spinnaker instances behind one global URL

A *fleet* is several independent Spinnaker instances presented to users as a single URL. An edge
router (the "fleet manager") assigns each user to an instance and proxies their traffic there, so
users only ever see the global URL and never address an instance directly.

Each instance keeps its own Gate, Redis and SAML registration. Nothing is shared between instances,
so this is not clustering — it is many small Spinnakers behind one door.

A complete, validated 2-instance configuration — edge router, both Gate configs, the Deck
settings changes, and Kubernetes manifests — lives in
[`fleet-example/`](fleet-example/README.md).

There are two ways to deploy this:

- **[Mode A: full](#mode-a-full)** — configuration plus the `fleet.*` guardrail in Gate and the
  origin guard in Deck. Instance URLs are actively kept out of users' hands.
- **[Mode B: configuration only](#mode-b-configuration-only)** — the same URL masking with stock
  Spinnaker and no code, accepting three documented gaps.

Both require the same prerequisites.

## Prerequisites

### A shared parent DNS domain

The global URL and every instance URL must share a registrable parent domain:

```
https://spinnaker.example.com          <- global URL (what users see)
https://inst-1.spinnaker.example.com   <- instance URLs (users never navigate here)
https://inst-2.spinnaker.example.com
https://inst-3.spinnaker.example.com
```

This is **not optional**. The session cookie is scoped to the parent domain
(`Domain=.spinnaker.example.com`) so that a cookie minted while the browser was on an instance
hostname — which happens during SAML login — is still sent when the browser returns to the global
URL. Without a shared parent there is no routing signal at all and none of this works.

### Routing by session cookie name

Each instance names its session cookie uniquely (`SESSION_INST_1`, `SESSION_INST_2`, ...). The edge
maps the cookie name to an upstream. Routing therefore *is* the session: the instance that
authenticated a user is by definition the one that minted their cookie, so routing can never
disagree with where the session lives.

A useful consequence: because the routing key is the cookie *name* and not its value, an expired
session still routes home, so re-authentication stays on the same instance instead of scattering the
user across the fleet.

## Request flow

### First login

```
BR=browser  NG=edge  FM=fleet-manager  D1=Deck@inst-1  G1=Gate@inst-1  IDP=SAML IdP

 1  BR->NG   GET https://spinnaker.example.com/          Cookie: (none)
 2  NG->FM   no SESSION_INST_* -> ask for an assignment
 3  FM->NG   "inst-1"  (pinned for the whole cookie-less window)
 4  NG->D1   proxy /   + X-Forwarded-Host: spinnaker.example.com
 5  D1->BR   index.html + settings.js
 6  BR->NG   GET /gate/auth/user                         Cookie: (none)
 7  NG->G1   FM-pinned -> inst-1  (+ X-Forwarded-Prefix: /gate)
 8  G1->BR   200 null    (permitAll; anonymous context is not persisted, so no session yet)
 9  BR->NG   GET /gate/auth/redirect?to=https://spinnaker.example.com/#/...
10  NG->G1   FM-pinned -> inst-1
11  G1       /auth/redirect requires auth -> Spring Security entry point
             - creates the session ==> Set-Cookie: SESSION_INST_1;
                                       Domain=.spinnaker.example.com; Secure
             - HttpSessionRequestCache saves the request. Because ForwardedHeaderFilter has
               applied X-Forwarded-*, the saved request records the GLOBAL host and the /gate
               prefix rather than inst-1. This is what keeps the instance URL hidden at step 20.
12  G1->BR   302 https://spinnaker.example.com/gate/saml2/authenticate/inst-1
13  BR->NG   GET that URL                                Cookie: SESSION_INST_1
14  NG->G1   cookie map -> inst-1 (the fleet-manager is not consulted again)
15  G1->BR   302 to the IdP; AuthnRequest ACS =
             https://inst-1.spinnaker.example.com/gate/saml/SSO
16  BR->IDP  user authenticates
17  IDP->BR  form-POST to the instance ACS
18  BR->G1   POST https://inst-1.spinnaker.example.com/gate/saml/SSO    *** the one direct hop ***
             - the cookie is sent because Domain is the shared parent
             - FleetDirectAccessFilter exempts the SAML paths, so a non-admin is not bounced
               out of the middle of their own login
19  G1       validates the assertion, populates the SecurityContext, reads the saved request
20  G1->BR   302 https://spinnaker.example.com/gate/auth/redirect?to=...
21  BR->NG   -> inst-1 via the cookie
22  G1->BR   302 https://spinnaker.example.com/#/...
23  BR->NG   -> inst-1 -> Deck boots, /auth/user returns the user, the origin guard no-ops
```

The instance URL is visible to the browser for exactly one hop: step 18. That is inherent to a
per-instance ACS — the IdP posts the assertion through the user's browser, so the browser must be
able to reach the instance.

### Returning user

```
 1  BR->NG   GET https://spinnaker.example.com/       Cookie: SESSION_INST_1
 2  NG       cookie map -> inst_1. Fleet-manager not consulted.
 3  NG->D1   proxy / + X-Forwarded-Host: spinnaker.example.com
 4  D1->BR   Deck assets
 5  BR->NG   GET /gate/auth/user                      Cookie: SESSION_INST_1
 6  NG->G1   -> inst-1
 7  G1->BR   200 {username, roles, isAdmin}
 8  BR       Deck boots. No SAML, no IdP, no redirect. Zero instance-URL exposure.
```

If the session has expired, step 7 returns `null`, Deck restarts the login flow, and because the
cookie name still routes to inst-1 the user re-authenticates on the same instance.

## Edge configuration

The fleet manager is external to Spinnaker. This is the contract it must satisfy, shown as NGINX:

```nginx
map $http_cookie $spin_instance {
    default                     "";           # -> fleet-manager assignment path
    "~(^|;\s*)SESSION_INST_1="  inst_1;
    "~(^|;\s*)SESSION_INST_2="  inst_2;
    "~(^|;\s*)SESSION_INST_3="  inst_3;
}

location / {
    proxy_set_header X-Forwarded-Host  $host;     # overwrite, never append
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_pass http://$spin_instance;
}

location /gate/ {
    proxy_set_header X-Forwarded-Host   $host;
    proxy_set_header X-Forwarded-Proto  $scheme;
    proxy_set_header X-Forwarded-Prefix /gate;
    proxy_pass http://$spin_instance/;            # trailing slash strips the /gate prefix
}
```

Three things matter here:

1. **Anchor the cookie regex.** An unanchored `~*SESSION_INST_1` also matches a cookie *value* that
   happens to contain that text.
2. **Overwrite `X-Forwarded-*`, don't append.** `proxy_set_header` replaces whatever the client
   sent, which is what makes the header trustworthy for edge detection.
3. **Route cookie-less requests deterministically.** Between the first Deck asset load and the
   request that creates the session (step 11 above) there is no cookie. Ask the fleet manager for a
   *stable* assignment — e.g. via `auth_request` plus
   `auth_request_set $inst $upstream_http_x_spinnaker_instance;` — or, failing that, use
   `hash $remote_addr consistent`. Plain round-robin here is a latent source of intermittent login
   failures.

If a browser somehow accumulates two `SESSION_INST_*` cookies (a reassignment, or an admin who
visited another instance directly), `map` first-match wins arbitrarily. Decide the tiebreak
deliberately rather than inheriting map ordering.

Adding or draining an instance requires regenerating the `map` and reloading the edge. That is the
main operational cost of routing by cookie name.

### Letting the user choose (optional)

The fleet manager can simply pick an instance, or it can ask. If it asks, note that
`auth_request` is a subrequest: its body is discarded and its `Set-Cookie` never reaches the
browser, so the picker cannot live inside the assignment endpoint. The workable shape is the
oauth2-proxy one — the assignment endpoint answers **401** when nothing has been chosen, and the
edge converts that into a redirect:

```nginx
location / {
    auth_request /_fleet_assign;
    error_page 401 = @fleet_chooser;      # nothing chosen yet -> show the picker
    ...
}

location @fleet_chooser {
    return 302 /_fleet/?return=$uri;      # $uri, not $request_uri -- see below
}

location /_fleet/ {                       # NO auth_request here, or it loops forever
    proxy_pass http://fleet_manager/;
}
```

Three things bite here, all of them found by actually running it:

- **`absolute_redirect off`** (set at `http` level). nginx's default builds absolute redirects from
  `$host` plus its *own* listening port, so behind an ingress terminating 443 and forwarding to 8080
  the picker redirect lands on `http://host:8080/...` — wrong scheme, wrong port, unreachable.
- **`return=$uri`, not `$request_uri`.** nginx has no URL-encoding primitive, so a query string
  would smuggle `?`/`&` into this query. First-visit query strings are therefore lost; everything
  afterwards keeps them, since the picker appears once.
- **Don't apply the redirect to API paths.** An XHR must never be answered with an HTML picker; give
  API traffic a default instance instead. Gate's own 401s are unaffected either way, because
  `proxy_intercept_errors` defaults to off — only the `auth_request` 401 is intercepted.

A working implementation, plus an end-to-end harness that asserts all of the above, lives in
[`fleet-manager/`](../../fleet-manager/README.md). **That one is a validation fixture, not a
production component** — it just remembers a choice in a cookie. For a real deployment, implement
the contract below.

## The fleet manager contract

The fleet manager is **not part of Spinnaker** — you supply it. This is the whole interface it has
to satisfy. Anything that speaks HTTP will do.

### `GET /assign` — required

Called by the edge via `auth_request`, and **only when the browser has no `SESSION_INST_*`
cookie**. Once a user has a Spinnaker session, the cookie name alone routes them and this endpoint
is never called again, so it is off the hot path for essentially all traffic.

The edge sends:

| Header | Meaning |
|---|---|
| `Cookie` | The full inbound cookie header, so you can read your own assignment state |
| `X-Fleet-Mode` | `navigate` for a browser navigation, `api` for an XHR |
| `X-Original-URI` | The URI the user asked for |
| `X-Forwarded-For` | Client address chain |

There is no request body (`proxy_pass_request_body off`).

You must respond:

| Response | Meaning |
|---|---|
| `2xx` + `X-Spinnaker-Instance: <id>` | Route this user to `<id>`. The id must be one the edge's cookie map knows, or it will select no upstream |
| `401` | No assignment yet. Only valid when `X-Fleet-Mode: navigate` — the edge turns this into a redirect to your picker |
| anything else | Treated as a failure; the edge falls back to its default instance (see below) |

Two rules that matter more than they look:

- **Never echo unvalidated input into `X-Spinnaker-Instance`.** That header picks an nginx upstream.
  Resolve whatever you read from a cookie or header against your configured instance list first.
- **Never answer `401` for `X-Fleet-Mode: api`.** An XHR cannot render a picker; return a default
  instance instead. Deck would otherwise surface an HTML page as a failed API call.

### The picker — optional

Only needed if you let users choose rather than assigning silently. It must be reachable by the
browser (the example serves it through the edge at `/_fleet/`), and must **not** sit behind
`auth_request`, or the redirect that sends users to it will loop.

`return=` is passed as a **path**, not an absolute URL. Accept only rooted paths and reject
anything with a scheme, a leading `//`, or a backslash, or you have an open redirect.

### Failure behaviour

`auth_request` runs in nginx's access phase, *before* `proxy_pass`. So if your fleet manager is
unreachable, nginx fails the request with a 500 and the `$instance` map's default never gets a
chance to apply. The example config therefore carries explicit fallbacks:

```nginx
error_page 500 502 503 504 = @fleet_fallback_deck;   # and @fleet_fallback_gate for /api/v1/
```

With those, an outage is a **degradation** — every new user lands on the default instance — rather
than an outage of its own. Without them, every cookie-less visitor gets a 500 for the duration.
Existing sessions are unaffected either way.

This is asserted by `fleet-manager/e2e/run-e2e.sh` (section 4), including that a *genuine* upstream
5xx from Deck or Gate still reaches the client rather than being masked by the fallback.

### What a real fleet manager should own

The bundled fixture does none of this. A production one is where the following belongs:

- **Assignment policy.** Capacity and headroom per instance, tenant or team affinity, blast-radius
  limits, and canarying a new Spinnaker version to a subset of users. This is the actual reason to
  run a fleet manager instead of `split_clients`.
- **Draining.** Stop assigning new users to an instance without evicting the ones already on it.
  Note the asymmetry: you control *new* assignments, but existing users are pinned by their session
  cookie until it expires, so draining is eventually-consistent by nature. Plan for the tail.
- **Moving a user deliberately.** Clearing your own assignment is not enough — the
  `SESSION_INST_*` cookie still routes them to the instance holding their session. A real move
  means expiring that cookie too, which logs the user out. Decide whether that is acceptable before
  you need it at 3am.
- **Observability.** Assignments per instance, assignment latency, and the fallback rate. A rising
  fallback rate is the signal that the fleet manager is failing while everything still looks up,
  because the edge is quietly serving the default instance.
- **Availability.** Run at least two replicas. The service should be stateless — keep assignment
  state in the caller's cookie or a shared store, never in process memory — so a restart cannot
  strand users.

### If you would rather not run one at all

You do not have to. Replace the `auth_request` with a deterministic hash and delete the fleet
manager entirely:

```nginx
split_clients "${remote_addr}" $fallback_instance {
    50%  "inst-1";
    *    "inst-2";
}
```

You lose assignment policy, draining and the picker; you gain one less moving part with no
availability story to own. For a two-instance fleet that is often the right trade. What you must
**not** do is plain round-robin for cookie-less requests — see the third point under
[Edge configuration](#edge-configuration).

## Mode A: full

### Gate, per instance

```yaml
fleet:
  enabled: true
  global-base-url: https://spinnaker.example.com
  instance-id: inst-1

server:
  # MUST be "framework", not "native" -- see the note below.
  forward-headers-strategy: framework
  servlet:
    session:
      cookie:
        name: SESSION_INST_1                 # unique per instance; the edge routes on this
        domain: .spinnaker.example.com       # the shared parent, so the global URL sees it
        secure: true

services:
  deck:
    # The global URL, used by AuthController.validDeckRedirect and the CORS OriginValidator.
    base-url: https://spinnaker.example.com
    # Must also allow instance hosts, otherwise an admin working against an instance directly gets
    # "400 Requested redirect address not recognized" from /auth/redirect. When set, this pattern
    # replaces the base-url comparison entirely.
    redirect-host-pattern: '^(spinnaker|inst-\d+\.spinnaker)\.example\.com$'

spring:
  security:
    saml2:
      relyingparty:
        registration:
          inst-1:
            # Per-instance ACS. Note the /gate prefix -- it must match how the instance is fronted.
            acs:
              location: https://inst-1.spinnaker.example.com/gate/saml/SSO
            entity-id: ...
            assertingparty:
              metadata-uri: ...
```

### Where the path prefix comes from

Exposing Gate under a path on the global host makes everything same-origin and removes CORS
entirely. There are two ways to arrange it, and the choice determines your
`forward-headers-strategy`:

1. **Gate owns the prefix** — `server.servlet.context-path: /api/v1`, and the edge passes URIs
   through untouched. This is the approach used in `spinnaker-kustomize` and in
   [`fleet-example/`](fleet-example/README.md). Nothing needs `X-Forwarded-Prefix`, so either
   `framework` or `native` works. Recommended: fewer moving parts, and no dependence on a header
   being set correctly.
2. **The edge owns the prefix** — it strips the prefix and sends `X-Forwarded-Prefix`. Here
   `forward-headers-strategy` **must be `framework`**, which selects Spring's
   `ForwardedHeaderFilter` — the only option that understands that header. The `native` strategy
   (Tomcat's `RemoteIpValve`) handles `X-Forwarded-For/Proto/Host` but has no notion of a path
   prefix, so Gate would emit `Location: /saml2/authenticate/inst-1` with no prefix, the edge would
   route it to Deck, and login would dead-end at step 12.

Either way, prefer `framework`: it is also what makes `ForwardedHeaderFilter` record the **global**
host in the SAML saved request at step 11, which is what keeps the instance URL out of the
post-login redirect at step 20.

### Deck, per instance

```
API_HOST=/gate                                  # relative: same-origin, so CORS drops out entirely
FLEET_ENABLED=true
FLEET_GLOBAL_URL=https://spinnaker.example.com
FLEET_INSTANCE_ID=inst-1
```

### Fleet manager, one per fleet

Not per instance — one deployment for the whole fleet, implementing
[the contract above](#the-fleet-manager-contract). The edge's `upstream fleet_manager` block must
resolve to it:

```nginx
upstream fleet_manager { server fleet-manager.spinnaker.svc.cluster.local:8080; keepalive 8; }
```

Two deployment notes that catch people out:

- **nginx resolves upstream hostnames once, at startup, and refuses to start if one does not
  resolve.** So the fleet manager's Service must exist before the edge does, even if it has no
  endpoints yet. The same applies to the per-instance Services.
- **Instance ids have to agree in three places**: the `SESSION_INST_*` cookie names in the edge's
  `map`, the ids the fleet manager returns in `X-Spinnaker-Instance`, and each Gate's
  `fleet.instance-id`. A mismatch fails as "no upstream selected", which reads like a routing bug
  rather than a config typo.

[`fleet-example/`](fleet-example/README.md) ships a kustomize build covering all of this —
edge, fleet manager, per-instance Services and Ingress:

```bash
kubectl apply -k gate/docs/fleet-example/
```

It composes [`fleet-manager/k8s/`](../../fleet-manager/k8s/), which is a reusable base for the
bundled fixture. To run your own implementation instead, override the image and keep everything
else:

```yaml
images:
- name: fleet-manager
  newName: registry.example.com/my-fleet-manager
  newTag: "1.4.0"
```

### What the guardrail does

`FleetDirectAccessFilter` (gate-core) redirects (302) a **session-authenticated non-admin** who
reaches an instance hostname directly to the same path on the global URL. Admins are left alone so
they can always work against an instance directly.

It only fires when all of the following hold:

| Condition | Why |
|---|---|
| `fleet.enabled` | Opt-in; off by default |
| Not an `OPTIONS` request | Preflight must never be redirected |
| Path not in `fleet.exempt-paths` | See below |
| Effective host is not `global-base-url` | Otherwise the request came through the edge |
| The request carries a session | See below. Machine clients legitimately target a specific instance; URL discipline is a browser concern |
| Authenticated and not anonymous | Never pre-empt the login flow |
| Fiat says not an admin | Admins are exempt. Note Fiat-disabled deployments report everyone as admin, so the guardrail correctly never fires without Fiat |

The default `exempt-paths` is derived from the `permitAll` set already in `AuthConfig`, plus the
SAML endpoints. Every entry is therefore *already* reachable unauthenticated, so exempting it grants
a non-admin nothing new — while the SAML exemptions are what allow step 18 to complete. The
configured `saml.login-processing-url` is always exempt on top of the list, so customising it does
not require editing the defaults.

The session-only condition is what keeps Kubernetes probes (unauthenticated, hitting the pod IP with
no `X-Forwarded-Host`) and inbound webhooks working. A 302 would fail a probe, and most webhook
senders either ignore redirects or re-issue a POST as a GET.

### Which auth mechanisms it covers

The guardrail keys on **the presence of a session**, not on a particular `Authentication` type,
because Gate's browser logins do not agree on one:

| Mechanism | `Authentication` produced | Guardrail applies |
|---|---|---|
| `gate-basic` (basic / form) | `UsernamePasswordAuthenticationToken` | Yes |
| `gate-ldap` | `UsernamePasswordAuthenticationToken` | Yes |
| `gate-saml` | `PreAuthenticatedAuthenticationToken` | Yes |
| `gate-oauth2` | `OAuth2AuthenticationToken` | Yes |
| `gate-iap` | `PreAuthenticatedAuthenticationToken` | Yes, if a session is created |
| `gate-x509` | `PreAuthenticatedAuthenticationToken` | No — stateless machine client |
| `gate-header` | `PreAuthenticatedAuthenticationToken` | No — session creation disabled |
| API tokens | any | No — excluded explicitly |

Note the trap: `gate-saml`'s `ResponseAuthenticationConverter` returns a
`PreAuthenticatedAuthenticationToken`, the *same class* `gate-x509` and `gate-header` use. So the
token type alone cannot separate a SAML browser user from a machine client, which is why the
condition is "does this request carry a session" — the same cookie the edge routes on — plus an
explicit exclusion list for API tokens and x509.

Do not narrow this to `AuthTypeResolver.TYPE_SESSION`. That constant means "username/password
token" and exists as a low-cardinality *metrics* tag; using it here silently disables the guardrail
for SAML, OAuth2 and IAP. `FleetDirectAccessFilterTest` has a case per mechanism to prevent exactly
that regression.

### Deck's origin guard

`FleetOriginGuard.enforce()` runs in `bootstrapDeck` immediately after authentication resolves — it
needs `isAdmin`, which only exists once `/auth/user` has returned. If a non-admin has Deck loaded
from an instance origin, it redirects to the same path on `FLEET_GLOBAL_URL` and aborts the boot.
Admins stay, silently and with no UI change.

This matters because Gate cannot protect Deck's static assets, and because Deck builds shareable
links from `window.location.origin` (see `MetadataComponents.tsx`). Without the guard, one user
landing on an instance origin can propagate that URL to others by copy-paste.

### This is not a security boundary

Say so out loud in your own runbooks. A per-instance ACS requires every user's browser to be able to
reach every instance host, so instance ports cannot be restricted to the edge, and a determined
caller can forge `X-Forwarded-Host` to look like edge traffic.

Nothing is gained by doing so. Fiat still authorizes every request against the caller's real
permissions; the only thing bypassed is *which URL* they are on. Treat `fleet.*` as URL and routing
hygiene, not access control.

If you want to raise the bar for casual bypass, have the edge inject a shared secret header and
require it — the same trust model `gate-header` already uses for `X-SPINNAKER-USER`. That is
hardening, not a boundary.

## Mode B: configuration only

Everything under [Prerequisites](#prerequisites), [Request flow](#request-flow) and
[Edge configuration](#edge-configuration) works on stock Spinnaker with no code changes. Use the
Mode A configuration minus `fleet.*` and the `FLEET_*` Deck variables. Note that Deck's
`ISpinnakerSettings` has an index signature, so a `fleet` block in `settings.js` is harmless even on
a build without the typed setting.

Three things are given up.

**1. There is no enforcement; the rule becomes a convention.** No configuration expresses
"session-authenticated non-admin on an instance host → redirect", and the edge cannot decide it
either: that needs `isAdmin` out of a JSON response body, which stock NGINX cannot parse without
njs or Lua. Restricting instance hosts to an admin-only network is also unavailable, because step 18
requires user browsers to reach them. A non-admin who learns an instance URL gets full Deck and API
access there — still fully Fiat-authorized, so no privilege escalation, but unenforced.

**2. The mask erodes.** A user who lands on an instance origin stays there, and shared links carry
that origin into tickets and chat. The masking degrades over time instead of self-correcting.

**3. No decoupling from the IdP domain assumption.** See the next section.

### The plugin escape hatch

If you cannot take upstream changes but want Mode A behaviour, both pieces can ship as plugins: Gate
has a PF4J plugin framework (`gate-plugins`, `PluginsAutoConfiguration`) and Deck has `pluginsdk`.
For the cookie behaviour specifically, Spring Boot's `SessionAutoConfiguration.cookieSerializer` is
`@ConditionalOnMissingBean(CookieSerializer.class)`, so a plugin that supplies its own
`CookieSerializer` bean makes the auto-configuration back off entirely and bypasses gate-saml's
customizer.

## SameSite and where your IdP lives

`SameSite` is compared by *registrable domain* (eTLD+1), not by hostname. This decides whether step
18 needs anything special:

- **IdP on the same registrable domain** (e.g. `sso.example.com` with
  `inst-1.spinnaker.example.com`): step 18 is a **same-site** POST. `SameSite=Lax` only restricts
  *cross*-site sending, so the cookie is delivered regardless of method, consistently across
  Chrome, Firefox and Safari. Nothing further is needed.
- **IdP on a different registrable domain** (a hosted Okta/Azure AD/Ping tenant): step 18 is
  **cross-site**. A `Lax` cookie is not sent on a cross-site POST. Set:

  ```yaml
  server:
    servlet:
      session:
        cookie:
          same-site: none
          secure: true      # required: browsers reject SameSite=None without Secure
  ```

Historically `saml.*` deployments got away with this because Gate cleared the attribute entirely and
Chrome's "Lax+POST" mitigation sends `SameSite`-less cookies on top-level cross-site POSTs for the
first two minutes of their life — which happens to cover the SAML window. That is a browser
heuristic, not a guarantee, and other browsers differ.

`gate-saml` still clears the attribute by default, so existing deployments are unchanged, but the
property is now honoured when you set it explicitly. The other SSO modules (`gate-oauth2`,
`gate-ldap`, `gate-basic`) still clear it unconditionally.

## Known gaps

- **Logout is instance-local.** `/auth/logout` and `/auth/deleteSessionCache` affect one instance.
  There is no fleet-wide logout.
- **The edge couples to fleet topology.** Adding or draining an instance needs a `map` regeneration
  and reload.
- **Draining has a long tail.** A fleet manager can stop *assigning* an instance immediately, but
  users already on it are pinned by their `SESSION_INST_*` cookie until it expires. Forcing them off
  means expiring that cookie, which logs them out. There is no graceful in-place migration.
- **No fleet manager ships with Spinnaker.** The contract is specified above and
  [`fleet-manager/`](../../fleet-manager/README.md) implements it, but that is a validation fixture
  with no assignment policy. A production deployment supplies its own.
- **Deck's `/gate` path mode is lightly travelled.** Nothing in Gate or Deck sets
  `X-Forwarded-Prefix` or `server.servlet.context-path` by default, and
  `deck/docker/spinnaker.conf.{gen,ssl}` rely on `ProxyPassReverse` to rewrite `Location` — which
  does not work in combination with the `ProxyPreserveHost On` those files also set, because the
  backend then emits a global-host `Location` that no longer matches the proxied backend URL and the
  prefix is silently dropped. If you front instances with that image rather than your own proxy, set
  `X-Forwarded-Prefix` there too. Verify the `/gate`-prefixed SAML round trip end to end before
  rolling out.
