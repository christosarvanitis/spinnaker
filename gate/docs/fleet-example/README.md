# Worked example: a 2-instance Spinnaker fleet

A complete, concrete configuration for two independent Spinnaker instances presented to
users as one URL. Read [../fleet.md](../fleet.md) first for the concepts and the request
flow; this directory is the "show me the files" companion.

```
https://spinnaker.example.com          global URL   -- every ordinary user
https://inst-1.spinnaker.example.com   instance 1   -- SAML ACS + admins
https://inst-2.spinnaker.example.com   instance 2   -- SAML ACS + admins
```

| File | What it is |
|---|---|
| `nginx-fleet-edge.conf` | The fleet edge router. Routes by session-cookie name. |
| `fleet-proxy-headers.conf` | Shared `proxy_set_header` block, included by the above. |
| `gate-local-inst-1.yml` | Gate config, instance 1. |
| `gate-local-inst-2.yml` | Gate config, instance 2 (differs only in the `PER-INSTANCE` values). |
| `deck-settings-fleet.js` | The three changes to Deck's `settings.js`. Excerpt, not a drop-in file. |
| `kubernetes.yml` | Edge Deployment/Service/ConfigMap, per-instance Services, and the Ingress. |
| `nginx.conf` | Top-level nginx config for the edge container. |

The `fleet_manager` upstream referenced by the edge is implemented in
[`fleet-manager/`](../../../fleet-manager/README.md) at the repo root. It also ships an
end-to-end harness that runs **this** edge config against two stub instances and asserts the
whole flow, including the instance picker:

```bash
./fleet-manager/e2e/run-e2e.sh      # needs docker + curl
```

The harness generates its edge config from `nginx-fleet-edge.conf` rather than keeping a
copy, so it cannot drift from what is documented here and quietly test something else.

Everything below assumes the two prerequisites from `../fleet.md`: a **shared parent DNS
domain**, and **one Redis per instance**.

## The two design choices that make this work

**1. Gate owns the path prefix.** Each Gate runs with
`server.servlet.context-path: /api/v1`, matching the convention already used in
`spinnaker-kustomize/overlays/config/files/gate-local.yml`. The edge therefore passes
URIs through completely untouched — no rewriting, no `X-Forwarded-Prefix`, no
`ProxyPassReverse` games. Deck and Gate also end up on a single origin, which removes
CORS from the picture entirely.

The alternative (edge strips a prefix and sends `X-Forwarded-Prefix`) works too, but it
*requires* `server.forward-headers-strategy: framework`, because only Spring's
`ForwardedHeaderFilter` understands that header — Tomcat's `RemoteIpValve` (the `native`
strategy) does not. Owning the prefix in Gate avoids that trap.

**2. The session cookie name is the routing key.** Instance 1 names its cookie
`SESSION_INST_1`, instance 2 `SESSION_INST_2`, and both scope it to
`Domain=.spinnaker.example.com`. The edge maps the cookie *name* to an upstream.

Two consequences worth internalising:

- Routing can never disagree with reality. The instance that authenticated a user is by
  definition the one that minted their cookie.
- Because the key is the name and not the value, **an expired session still routes
  home**, so re-authentication stays on the same instance instead of scattering the user
  across the fleet.

The cookie `Domain` must be the shared parent, not the instance hostname. That is what
lets a cookie minted during the SAML round trip — which happens on the instance
hostname — still be sent when the browser returns to the global URL. Get this wrong and
the fleet has no routing signal at all.

## Bringing it up

```bash
# 1. Edge config as a ConfigMap (keeps the files in this directory as the source of truth)
kubectl -n spinnaker create configmap spinnaker-fleet-edge \
  --from-file=fleet-edge.conf=nginx-fleet-edge.conf \
  --from-file=fleet-proxy-headers.conf=fleet-proxy-headers.conf \
  --from-file=nginx.conf=nginx.conf \
  --dry-run=client -o yaml | kubectl apply -f -

# 2. Edge Deployment/Service, per-instance Services, Ingress
kubectl apply -f kubernetes.yml

# 3. Per-instance Gate config
kubectl -n spinnaker create configmap gate-inst-1 --from-file=gate-local.yml=gate-local-inst-1.yml \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n spinnaker create configmap gate-inst-2 --from-file=gate-local.yml=gate-local-inst-2.yml \
  --dry-run=client -o yaml | kubectl apply -f -
```

Deploy each instance's Gate/Deck/Redis/Fiat with the label
`spinnaker.io/fleet-instance: inst-1` (or `inst-2`) so the per-instance Services in
`kubernetes.yml` select them.

Register **both** ACS URLs with your IdP as two separate service providers:

```
https://inst-1.spinnaker.example.com/api/v1/saml/SSO   entity id spinnaker-inst-1
https://inst-2.spinnaker.example.com/api/v1/saml/SSO   entity id spinnaker-inst-2
```

## Validating the edge before you ship it

The routing logic is the part most likely to be subtly wrong, and it is easy to test
without any Spinnaker at all — point the upstreams at stub servers that echo their own
name, then curl with different cookies:

```bash
curl -s -H 'Host: spinnaker.example.com' -H 'Cookie: SESSION_INST_1=x' http://edge:8080/
# -> instance 1

curl -s -H 'Host: spinnaker.example.com' -H 'Cookie: SESSION_INST_2=x' http://edge:8080/
# -> instance 2

curl -s -H 'Host: spinnaker.example.com' http://edge:8080/
# -> whatever the fleet manager assigned

curl -s -H 'Host: spinnaker.example.com' -H 'Cookie: OTHER=SESSION_INST_2' http://edge:8080/
# -> must NOT go to instance 2: the cookie NAME is absent, only its text appears in a value

curl -s http://edge:8080/healthz          # -> 200 ok, no Host header needed
curl -s -H 'Host: nope.example.com' http://edge:8080/   # -> 404
```

The fourth case is why the map patterns are anchored with `(^|;\s*)`. An unanchored
`~*SESSION_INST_2` matches a cookie whose *value* merely contains that text, silently
routing the user to the wrong instance.

Then verify end to end, with a real browser:

1. A non-admin completes SAML and lands back on `https://spinnaker.example.com`.
2. `document.cookie` shows exactly **one** `SESSION_INST_*` cookie.
3. A non-admin hitting `https://inst-1.spinnaker.example.com` is redirected to the
   global URL. An admin is not.
4. `curl -H 'Host: ...' https://inst-1.../api/v1/health` still returns 200 — probes and
   webhooks must never be redirected.

## Why not do this with ingress-nginx annotations?

Because "route to a different Service depending on which session cookie **name** is
present" is not expressible there:

- `nginx.ingress.kubernetes.io/affinity: cookie` pins a client to a **pod within one
  Service**. Fleet instances are separate Services, so it cannot pick between them.
- `canary-by-cookie` only triggers on a cookie whose **value** is `always`/`never`, not
  on the presence of a name.
- Defining a `map` and doing `proxy_pass http://$variable` needs `http-snippet` /
  `configuration-snippet`, and snippet annotations are disabled by default in recent
  ingress-nginx (`allow-snippet-annotations: false`, after CVE-2021-25742). Turning them
  back on is a cluster-wide security decision.

A ~150-line NGINX Deployment you fully control is the smaller price. The Ingress in
`kubernetes.yml` stays deliberately dumb: terminate TLS, preserve `Host`, forward
everything to the edge.

## Operational notes

- **Adding or draining an instance means editing the edge `map` and reloading.** That is
  the main ongoing cost of routing by cookie name.
- **nginx resolves `upstream` hostnames once, at startup, and refuses to start if one
  does not resolve.** In a fleet that means a missing Service can stop the whole edge
  from coming up. Keep the per-instance Services present even while an instance is
  scaled to zero.
- **A fleet-manager outage only degrades because of the `error_page 5xx` handlers.** `auth_request`
  runs before `proxy_pass`, so without `@fleet_fallback_deck` / `@fleet_fallback_gate` every
  cookie-less visitor gets a 500 for the duration — the `$instance` map's default is never reached.
  Established sessions are fine regardless, since `/_fleet_assign` short-circuits on the session
  cookie. Both cases are asserted in `run-e2e.sh` section 4, along with the fact that a *genuine*
  upstream 5xx still reaches the client rather than being masked.
- **`absolute_redirect off` is load-bearing, not cosmetic.** nginx's default builds absolute
  redirects from `$host` plus its **own** listening port, so behind an ingress terminating
  443 and forwarding to 8080 the picker redirect goes to
  `http://spinnaker.example.com:8080/...` — wrong scheme, wrong port, unreachable. It is set
  in `nginx.conf`; the e2e harness caught this the first time it ran.
- **Never put `auth_request` on `location /_fleet/`.** The picker is what the edge redirects
  to when assignment is missing, so guarding it with the check that just failed loops
  forever.
- **Route cookie-less requests deterministically.** There is a window between the first
  Deck asset load and the request that creates the session where there is no cookie. The
  example asks the fleet manager via `auth_request`, and short-circuits to `204` when a
  cookie already exists so steady-state traffic has no dependency on it. Plain
  round-robin in that window causes intermittent login failures. If you would rather not
  run a fleet manager at all, `split_clients "${remote_addr}" $fallback { 50% "inst-1"; * "inst-2"; }`
  is a stateless, deterministic substitute.
- **If a browser accumulates two `SESSION_INST_*` cookies**, `map` first-match wins
  arbitrarily. Decide that tiebreak deliberately.
- **The Gate guardrail is not a security boundary.** A per-instance ACS requires every
  user's browser to reach every instance host, so instance ports cannot be restricted to
  the edge and `X-Forwarded-Host` can be forged. Nothing is gained by forging it — Fiat
  still authorizes every request — but do not mistake this for access control. See
  `../fleet.md`.
