#!/usr/bin/env bash
#
# End-to-end validation of the Spinnaker fleet flow: instance picker -> assignment ->
# session -> sticky routing, plus the negative cases that are easy to regress silently.
#
# Requires docker (with compose) and curl. Run from anywhere:
#     ./fleet-manager/e2e/run-e2e.sh
#
set -o errexit -o nounset -o pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

GLOBAL_HOST=spinnaker.example.com
INST1_HOST=inst-1.spinnaker.example.com
INST2_HOST=inst-2.spinnaker.example.com
PORT=18080
EDGE_CONF_SRC=../../gate/docs/fleet-example/nginx-fleet-edge.conf
JAR=$(mktemp -d)/cookies.txt
PASS=0
FAIL=0

cleanup() {
  docker compose down --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$(dirname "$JAR")"
}
trap cleanup EXIT

# --- assertion helpers -------------------------------------------------------

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "$2"; FAIL=$((FAIL + 1)); }

# assert_contains <description> <haystack> <needle>
assert_contains() {
  if [[ "$2" == *"$3"* ]]; then ok "$1"; else bad "$1" "expected to contain '$3', got: ${2//$'\n'/ }"; fi
}

# assert_equals <description> <actual> <expected>
assert_equals() {
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1" "expected '$3', got '$2'"; fi
}

# assert_not_contains <description> <haystack> <needle>
assert_not_contains() {
  if [[ "$2" != *"$3"* ]]; then ok "$1"; else bad "$1" "expected NOT to contain '$3', got: ${2//$'\n'/ }"; fi
}

# curl against a virtual host. --resolve (rather than -H 'Host:') is essential: curl keys
# its cookie jar on the real hostname, so with -H the Domain=.spinnaker.example.com cookies
# the stubs and fleet manager set would be rejected outright and nothing would work.
c() {
  local host=$1; shift
  curl -sS --resolve "$host:$PORT:127.0.0.1" \
       --resolve "$GLOBAL_HOST:$PORT:127.0.0.1" \
       --resolve "$INST1_HOST:$PORT:127.0.0.1" \
       --resolve "$INST2_HOST:$PORT:127.0.0.1" \
       "$@"
}

# --- bring the stack up ------------------------------------------------------

echo "==> Generating the edge config from $EDGE_CONF_SRC"
mkdir -p .generated
GENERATED=.generated/fleet-edge.conf
sed -E \
  -e 's#server deck-inst-1[^;]*;#server 127.0.0.1:19001;#' \
  -e 's#server deck-inst-2[^;]*;#server 127.0.0.1:19002;#' \
  -e 's#server gate-inst-1[^;]*;#server 127.0.0.1:19001;#' \
  -e 's#server gate-inst-2[^;]*;#server 127.0.0.1:19002;#' \
  -e 's#server fleet-manager[^;]*;#server fleet-manager:8080;#' \
  "$EDGE_CONF_SRC" > "$GENERATED"

# Rewrite the virtual hosts to this harness's own hostnames.
#
# This matters for durability: the edge config carries whatever real domain a deployment
# uses, so hardcoding hostnames in this script would silently break the whole suite the next
# time the fleet is pointed at a different domain -- every request would miss the virtual
# hosts, land on the default_server, and come back 404.
#
# Done in Python rather than sed because the rules have to be mutually exclusive: `_` (the
# default_server) must be left alone, and an instance host rewritten to a real-looking
# hostname must not then be caught by the global-host rule.
GLOBAL_HOST="$GLOBAL_HOST" INST1_HOST="$INST1_HOST" INST2_HOST="$INST2_HOST" \
GENERATED="$GENERATED" python3 - <<'PY'
import os, re

path = os.environ['GENERATED']
hosts = {'1': os.environ['INST1_HOST'], '2': os.environ['INST2_HOST']}

def rewrite(match):
    indent, host = match.group(1), match.group(2).strip()
    if host == '_':                       # the default_server catch-all
        return match.group(0)
    for n, replacement in hosts.items():
        if host.startswith(f'inst-{n}'):
            return f'{indent}server_name {replacement};'
    return f"{indent}server_name {os.environ['GLOBAL_HOST']};"

text = open(path).read()
text, n = re.subn(r'^([ \t]*)server_name ([^;]+);', rewrite, text, flags=re.M)
open(path, 'w').write(text)

# Four blocks: default_server, global, inst-1, inst-2. Fewer means the config changed shape
# and this rewrite silently missed one.
if n != 4:
    raise SystemExit(f'expected 4 server_name directives, rewrote {n} -- check {path}')
PY

# Fail loudly if the substitution missed anything, rather than starting nginx against
# unresolvable Kubernetes DNS names.
if grep -q 'svc.cluster.local' "$GENERATED"; then
  echo "ERROR: unsubstituted cluster DNS names remain in the generated config:" >&2
  grep -n 'svc.cluster.local' "$GENERATED" >&2
  exit 1
fi

# The real nginx.conf includes exactly one file by name, so the stub server blocks have to
# join it rather than arrive as a second conf.d entry.
cat nginx/stubs.conf >> "$GENERATED"

echo "==> Starting the stack"
# Tear down first, and force a recreate. Both matter: the edge's config arrives as a bind
# mount, and nginx only reads it at startup, so `up -d` on an already-running container
# would silently keep serving a STALE config while we regenerate the file underneath it.
# That makes the whole suite untrustworthy in both directions -- it once reported three
# failures against config that was already fixed.
docker compose down --remove-orphans >/dev/null 2>&1 || true
docker compose up -d --build --force-recreate --wait >/dev/null

echo "==> Waiting for the edge"
for _ in $(seq 1 30); do
  if curl -sS -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then break; fi
  sleep 1
done

rm -f "$JAR"

# =============================================================================
echo
echo "1. First visit with no cookies is sent to the instance picker"
# =============================================================================
body=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -L "http://$GLOBAL_HOST:$PORT/")
assert_contains "picker is rendered" "$body" "Choose a Spinnaker instance"
assert_contains "both instances offered" "$body" "Instance Two (us-east)"
assert_not_contains "no instance served the request yet" "$body" "served-by="

location=$(c "$GLOBAL_HOST" -o /dev/null -w '%{redirect_url}' "http://$GLOBAL_HOST:$PORT/applications")
assert_contains "redirect carries the original path as return" "$location" "/_fleet/?return=/applications"

# =============================================================================
echo
echo "2. Choosing inst-2 records the choice and returns the user"
# =============================================================================
code=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' \
  -X POST --data 'instance=inst-2&return=/' "http://$GLOBAL_HOST:$PORT/_fleet/choose")
assert_equals "POST /choose redirects" "$code" "302"
assert_contains "choice cookie stored for the parent domain" "$(cat "$JAR")" "SPIN_FLEET_CHOICE"
assert_contains "choice cookie holds inst-2" "$(cat "$JAR")" "inst-2"

# =============================================================================
echo
echo "3. The user is now routed to the chosen instance, which mints a session"
# =============================================================================
body=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" "http://$GLOBAL_HOST:$PORT/")
assert_contains "served by the chosen instance" "$body" "served-by=inst-2"
assert_contains "instance session cookie now present" "$(cat "$JAR")" "SESSION_INST_2"

body=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" "http://$GLOBAL_HOST:$PORT/api/v1/auth/user")
assert_contains "API calls reach the same instance" "$body" '"instance":"inst-2"'

# =============================================================================
echo
echo "4. A fleet-manager outage degrades, it does not take the fleet down"
# =============================================================================
docker compose stop fleet-manager >/dev/null 2>&1

# 4a. The load-bearing assertion: an established session is untouched, because
# /_fleet_assign short-circuits on the session cookie without any network call.
body=$(c "$GLOBAL_HOST" -b "$JAR" "http://$GLOBAL_HOST:$PORT/" || echo "REQUEST-FAILED")
assert_contains "existing session still routed with the fleet manager stopped" "$body" "served-by=inst-2"

# 4b. And a BRAND NEW visitor must still be served, not 500'd. auth_request fails in the
# access phase before proxy_pass, so without the error_page 5xx fallback every new user
# gets a 500 for the duration of the outage. This is the case that regressed unnoticed
# until it was actually tested.
code=$(c "$GLOBAL_HOST" -o /dev/null -w '%{http_code}' "http://$GLOBAL_HOST:$PORT/" || echo 000)
assert_equals "cookie-less visitor is served during the outage, not 500" "$code" "200"

code=$(c "$GLOBAL_HOST" -o /dev/null -w '%{http_code}' "http://$GLOBAL_HOST:$PORT/api/v1/health" || echo 000)
assert_equals "cookie-less API call is served during the outage, not 500" "$code" "200"

docker compose start fleet-manager >/dev/null 2>&1
# Wait for an actual 200, not merely a completed request: curl exits 0 on a 502 too, so a
# naive check here would let later assertions run while the fleet manager is still down.
for _ in $(seq 1 30); do
  ready=$(c "$GLOBAL_HOST" -o /dev/null -w '%{http_code}' \
    "http://$GLOBAL_HOST:$PORT/_fleet/" 2>/dev/null || echo 000)
  [[ "$ready" == "200" ]] && break
  sleep 1
done
if [[ "${ready:-000}" != "200" ]]; then
  echo "  (warning: fleet manager did not come back; later assertions may be unreliable)" >&2
fi

# =============================================================================
echo
echo "5. Clearing the assignment does not move an authenticated user"
# =============================================================================
c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -o /dev/null -X POST --data 'return=/' \
  "http://$GLOBAL_HOST:$PORT/_fleet/reset"
body=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" "http://$GLOBAL_HOST:$PORT/")
# The session cookie outranks the choice cookie -- routing follows where the session lives.
assert_contains "session still pins the user to inst-2" "$body" "served-by=inst-2"

# =============================================================================
echo
echo "6. A brand new visitor can choose the other instance"
# =============================================================================
rm -f "$JAR"
c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -o /dev/null -X POST --data 'instance=inst-1&return=/' \
  "http://$GLOBAL_HOST:$PORT/_fleet/choose"
body=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" "http://$GLOBAL_HOST:$PORT/")
assert_contains "honours inst-1, so it is a real choice not a fixed default" "$body" "served-by=inst-1"

# =============================================================================
echo
echo "7. Negative cases"
# =============================================================================
rm -f "$JAR"

# An upstream 401 must reach the client as a 401, NOT be converted into a picker redirect.
# This is what proxy_intercept_errors=off buys us, and it is easy to regress.
code=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' \
  "http://$GLOBAL_HOST:$PORT/api/v1/auth/user")
assert_equals "unauthenticated API call returns Gate's own 401" "$code" "401"

# The 5xx fallback handlers must not swallow real backend failures. A genuine upstream 500
# has to reach the client, or a broken Gate would masquerade as a healthy one.
code=$(c "$GLOBAL_HOST" -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' \
  "http://$GLOBAL_HOST:$PORT/api/v1/boom")
assert_equals "a genuine upstream 500 is passed through, not masked" "$code" "500"

# API traffic must never be redirected to an HTML picker.
rm -f "$JAR"
code=$(c "$GLOBAL_HOST" -o /dev/null -w '%{http_code}' "http://$GLOBAL_HOST:$PORT/api/v1/health")
assert_equals "API path with no cookies is served, not redirected" "$code" "200"

# Forged choice cookies must not steer routing.
body=$(c "$GLOBAL_HOST" -H 'Cookie: SPIN_FLEET_CHOICE=deck_inst_2' -L "http://$GLOBAL_HOST:$PORT/")
assert_contains "forged choice cookie falls back to the picker" "$body" "Choose a Spinnaker instance"

# Instance hostnames stay reachable -- the per-instance SAML ACS depends on it.
body=$(c "$INST1_HOST" "http://$INST1_HOST:$PORT/")
assert_contains "instance host serves its own instance" "$body" "served-by=inst-1"

# Infrastructure endpoints.
code=$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/healthz")
assert_equals "/healthz needs no Host header" "$code" "200"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: nope.example.com' "http://127.0.0.1:$PORT/")
assert_equals "unknown Host is refused" "$code" "404"

# The picker itself must never be guarded by the check that sends users to it.
code=$(c "$GLOBAL_HOST" -o /dev/null -w '%{http_code}' "http://$GLOBAL_HOST:$PORT/_fleet/")
assert_equals "picker is reachable without an assignment (no redirect loop)" "$code" "200"

# =============================================================================
echo
printf 'Results: \033[32m%d passed\033[0m, ' "$PASS"
if ((FAIL > 0)); then
  printf '\033[31m%d failed\033[0m\n' "$FAIL"
  exit 1
fi
printf '0 failed\n'
