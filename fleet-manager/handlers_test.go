package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func testServer(t *testing.T) *Server {
	t.Helper()
	cfg, err := LoadConfig(fakeEnv(map[string]string{
		"FLEET_INSTANCES":     "inst-1=Instance One,inst-2=Instance Two",
		"FLEET_COOKIE_DOMAIN": ".spinnaker.example.com",
		"FLEET_COOKIE_SECURE": "false",
	}))
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	return NewServer(cfg)
}

// do issues a request against the full mux, so routing is exercised alongside handlers.
func do(t *testing.T, s *Server, req *http.Request) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	s.Routes().ServeHTTP(rec, req)
	return rec
}

func chose(instance string) *http.Cookie {
	return &http.Cookie{Name: "SPIN_FLEET_CHOICE", Value: instance}
}

// ---------------------------------------------------------------------------
// /assign -- the edge's auth_request endpoint
// ---------------------------------------------------------------------------

func TestAssignWithValidChoice(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/assign", nil)
	req.AddCookie(chose("inst-2"))

	rec := do(t, s, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rec.Code)
	}
	if got := rec.Header().Get(instanceHeader); got != "inst-2" {
		t.Errorf("%s = %q, want inst-2", instanceHeader, got)
	}
}

func TestAssignNavigateWithoutChoiceIs401(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/assign", nil)
	req.Header.Set(modeHeader, modeNavigate)

	rec := do(t, s, req)

	// 401 is what the edge converts into a redirect to the picker.
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if got := rec.Header().Get(instanceHeader); got != "" {
		t.Errorf("must not assign an instance on 401, got %q", got)
	}
}

func TestAssignApiModeWithoutChoiceFallsBackToDefault(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/assign", nil)
	req.Header.Set(modeHeader, modeAPI)

	rec := do(t, s, req)

	// An XHR must never be bounced to an HTML picker, so API mode always resolves.
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rec.Code)
	}
	if got := rec.Header().Get(instanceHeader); got != "inst-1" {
		t.Errorf("%s = %q, want the default instance", instanceHeader, got)
	}
}

func TestAssignApiModeIsCaseInsensitive(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/assign", nil)
	req.Header.Set(modeHeader, "API")

	if rec := do(t, s, req); rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", rec.Code)
	}
}

// A forged cookie must never be reflected into the header that picks an nginx upstream.
func TestAssignRejectsForgedCookieValues(t *testing.T) {
	forged := []string{
		"inst-3",
		"INST-1",
		"../../evil",
		"inst-1; evil",
		"deck_inst_1",
		"",
	}

	for _, value := range forged {
		t.Run(value, func(t *testing.T) {
			s := testServer(t)
			req := httptest.NewRequest(http.MethodGet, "/assign", nil)
			req.Header.Set(modeHeader, modeNavigate)
			req.AddCookie(chose(value))

			rec := do(t, s, req)

			if rec.Code != http.StatusUnauthorized {
				t.Errorf("status = %d, want 401 for forged value %q", rec.Code, value)
			}
			if got := rec.Header().Get(instanceHeader); got != "" {
				t.Errorf("forged value %q leaked into %s as %q", value, instanceHeader, got)
			}
		})
	}
}

func TestAssignRejectsNonGet(t *testing.T) {
	s := testServer(t)
	rec := do(t, s, httptest.NewRequest(http.MethodPost, "/assign", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

// ---------------------------------------------------------------------------
// /choose
// ---------------------------------------------------------------------------

func postForm(t *testing.T, s *Server, path string, form url.Values) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return do(t, s, req)
}

func TestChooseSetsCookieAndRedirects(t *testing.T) {
	s := testServer(t)

	rec := postForm(t, s, "/choose", url.Values{
		"instance": {"inst-2"},
		"return":   {"/applications"},
	})

	if rec.Code != http.StatusFound {
		t.Fatalf("status = %d, want 302", rec.Code)
	}
	if got := rec.Header().Get("Location"); got != "/applications" {
		t.Errorf("Location = %q", got)
	}

	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("expected exactly one cookie, got %d", len(cookies))
	}
	c := cookies[0]
	if c.Name != "SPIN_FLEET_CHOICE" || c.Value != "inst-2" {
		t.Errorf("cookie = %s=%s", c.Name, c.Value)
	}
	if !c.HttpOnly {
		t.Error("cookie must be HttpOnly")
	}
	if c.SameSite != http.SameSiteLaxMode {
		t.Error("cookie must be SameSite=Lax to blunt cross-site pinning")
	}
	// Note the leading dot is gone. net/http strips it when serializing, which is correct
	// per RFC 6265: a Domain attribute always covers subdomains, so ".example.com" and
	// "example.com" are equivalent and the dotted form is legacy. Configuring
	// FLEET_COOKIE_DOMAIN=".spinnaker.example.com" still produces a cookie that the global
	// URL and every instance hostname can see -- which is the property the fleet needs.
	// Don't "fix" this back to the dotted form.
	if c.Domain != "spinnaker.example.com" {
		t.Errorf("cookie Domain = %q, want the shared parent", c.Domain)
	}
	if c.Path != "/" {
		t.Errorf("cookie Path = %q", c.Path)
	}
	if c.Secure {
		t.Error("Secure should follow FLEET_COOKIE_SECURE=false in this fixture")
	}
	if raw := rec.Header().Get("Set-Cookie"); !strings.Contains(raw, "Domain=spinnaker.example.com") {
		t.Errorf("Set-Cookie header = %q", raw)
	}
}

func TestChooseDefaultsEmptyReturnToRoot(t *testing.T) {
	s := testServer(t)
	rec := postForm(t, s, "/choose", url.Values{"instance": {"inst-1"}})
	if got := rec.Header().Get("Location"); got != "/" {
		t.Errorf("Location = %q, want /", got)
	}
}

func TestChooseRejectsUnknownInstance(t *testing.T) {
	s := testServer(t)
	rec := postForm(t, s, "/choose", url.Values{"instance": {"inst-9"}, "return": {"/"}})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
	if len(rec.Result().Cookies()) != 0 {
		t.Error("must not set a cookie for an unknown instance")
	}
}

// The open-redirect surface. Every one of these must be refused.
func TestChooseRejectsOffOriginReturnTargets(t *testing.T) {
	hostile := []string{
		"https://evil.example.com/",
		"http://evil.example.com/",
		"//evil.example.com/",
		"/\\evil.example.com",
		`/\/evil.example.com`,
		"javascript:alert(1)",
		"applications",            // not rooted
		"/foo\r\nSet-Cookie: x=1", // response splitting
		"/foo\nLocation: http://evil",
	}

	for _, target := range hostile {
		t.Run(target, func(t *testing.T) {
			s := testServer(t)
			rec := postForm(t, s, "/choose", url.Values{
				"instance": {"inst-1"},
				"return":   {target},
			})

			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400 for return=%q (Location=%q)",
					rec.Code, target, rec.Header().Get("Location"))
			}
		})
	}
}

func TestChooseAcceptsRootedPathsWithQuery(t *testing.T) {
	for _, target := range []string{"/", "/applications", "/applications?q=a&b=c", "/#/deep"} {
		t.Run(target, func(t *testing.T) {
			s := testServer(t)
			rec := postForm(t, s, "/choose", url.Values{"instance": {"inst-1"}, "return": {target}})
			if rec.Code != http.StatusFound {
				t.Fatalf("status = %d, want 302 for %q", rec.Code, target)
			}
			if got := rec.Header().Get("Location"); got != target {
				t.Errorf("Location = %q, want %q", got, target)
			}
		})
	}
}

func TestChooseRejectsNonPost(t *testing.T) {
	s := testServer(t)
	if rec := do(t, s, httptest.NewRequest(http.MethodGet, "/choose", nil)); rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

// ---------------------------------------------------------------------------
// /reset
// ---------------------------------------------------------------------------

func TestResetExpiresCookie(t *testing.T) {
	s := testServer(t)
	rec := postForm(t, s, "/reset", url.Values{"return": {"/"}})

	if rec.Code != http.StatusFound {
		t.Fatalf("status = %d, want 302", rec.Code)
	}
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("expected one cookie, got %d", len(cookies))
	}
	if cookies[0].MaxAge >= 0 {
		t.Errorf("MaxAge = %d, want negative so the browser drops it", cookies[0].MaxAge)
	}
	if cookies[0].Value != "" {
		t.Errorf("Value = %q, want empty", cookies[0].Value)
	}
}

func TestResetFallsBackToRootOnBadReturn(t *testing.T) {
	s := testServer(t)
	rec := postForm(t, s, "/reset", url.Values{"return": {"https://evil.example.com"}})
	if got := rec.Header().Get("Location"); got != "/" {
		t.Errorf("Location = %q, want / -- reset must never redirect off-origin", got)
	}
}

// ---------------------------------------------------------------------------
// /instances, /healthz
// ---------------------------------------------------------------------------

func TestInstancesJSON(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/instances", nil)
	req.AddCookie(chose("inst-2"))

	rec := do(t, s, req)

	var body struct {
		Instances []Instance `json:"instances"`
		Default   string     `json:"default"`
		Chosen    string     `json:"chosen"`
	}
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Instances) != 2 || body.Default != "inst-1" || body.Chosen != "inst-2" {
		t.Errorf("body = %+v", body)
	}
}

func TestHealthz(t *testing.T) {
	s := testServer(t)
	rec := do(t, s, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "ok") {
		t.Fatalf("status = %d body = %q", rec.Code, rec.Body.String())
	}
}

// ---------------------------------------------------------------------------
// The picker page
// ---------------------------------------------------------------------------

func TestChooserRendersAControlPerInstance(t *testing.T) {
	s := testServer(t)
	rec := do(t, s, httptest.NewRequest(http.MethodGet, "/?return=/applications", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{`value="inst-1"`, `value="inst-2"`, "Instance One", "Instance Two"} {
		if !strings.Contains(body, want) {
			t.Errorf("body missing %q", want)
		}
	}
	// Relative actions are what let the edge serve this under /_fleet/ without the
	// service knowing the prefix.
	if !strings.Contains(body, `action="choose"`) {
		t.Error(`form action must be relative ("choose"), not rooted`)
	}
	if !strings.Contains(body, `value="/applications"`) {
		t.Error("return path should be carried through the form")
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Error("picker is per-user; it must not be cacheable")
	}
}

func TestChooserEscapesLabels(t *testing.T) {
	cfg, err := LoadConfig(fakeEnv(map[string]string{
		"FLEET_INSTANCES": `inst-1=<script>alert(1)</script>`,
	}))
	if err != nil {
		t.Fatalf("config: %v", err)
	}

	rec := do(t, NewServer(cfg), httptest.NewRequest(http.MethodGet, "/", nil))

	if strings.Contains(rec.Body.String(), "<script>alert(1)</script>") {
		t.Error("instance label was not escaped")
	}
}

func TestChooserDropsHostileReturnInsteadOfFailing(t *testing.T) {
	s := testServer(t)
	rec := do(t, s, httptest.NewRequest(http.MethodGet, "/?return=https://evil.example.com", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 -- a mangled link should still let the user choose", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "evil.example.com") {
		t.Error("hostile return value must not reach the form")
	}
}

func TestChooserShowsCurrentAssignment(t *testing.T) {
	s := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(chose("inst-2"))

	body := do(t, s, req).Body.String()

	if !strings.Contains(body, "Currently assigned") || !strings.Contains(body, `action="reset"`) {
		t.Error("expected the current assignment and a clear-assignment control")
	}
}

func TestUnknownPathIs404(t *testing.T) {
	s := testServer(t)
	if rec := do(t, s, httptest.NewRequest(http.MethodGet, "/nope", nil)); rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}
