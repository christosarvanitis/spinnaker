package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
)

const (
	// instanceHeader is what the edge reads via auth_request_set to pick an upstream.
	instanceHeader = "X-Spinnaker-Instance"

	// modeHeader lets the edge tell us whether it is serving a browser navigation or an
	// API call, so we never have to guess from Accept/Sec-Fetch heuristics. A navigation
	// can be redirected to the picker; an API call must not be.
	modeHeader = "X-Fleet-Mode"

	modeNavigate = "navigate"
	modeAPI      = "api"
)

// Server holds everything the handlers need. No mutable state: the assignment lives
// entirely in the caller's cookie, so the service is trivially horizontally scalable and
// restart-safe.
type Server struct {
	cfg *Config
}

func NewServer(cfg *Config) *Server { return &Server{cfg: cfg} }

func (s *Server) Routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/assign", s.handleAssign)
	mux.HandleFunc("/choose", s.handleChoose)
	mux.HandleFunc("/reset", s.handleReset)
	mux.HandleFunc("/instances", s.handleInstances)
	mux.HandleFunc("/healthz", s.handleHealthz)
	mux.HandleFunc("/", s.handleChooser)
	return mux
}

// handleAssign is the edge's auth_request endpoint. It is consulted only when the browser
// has no SESSION_INST_* cookie, so it is off the hot path for every authenticated user.
//
//	204 + X-Spinnaker-Instance  a valid choice exists
//	401                         nothing chosen yet and this is a navigation -> the edge
//	                            turns this into a redirect to the picker
//	204 + default instance      nothing chosen yet but this is an API call, which must
//	                            never be redirected to an HTML page
func (s *Server) handleAssign(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if instance, ok := s.chosenInstance(r); ok {
		w.Header().Set(instanceHeader, instance.ID)
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if strings.EqualFold(r.Header.Get(modeHeader), modeAPI) {
		w.Header().Set(instanceHeader, s.cfg.DefaultInstance)
		w.WriteHeader(http.StatusNoContent)
		return
	}

	// 401 rather than 403: nginx's auth_request treats both as "denied", and 401 is the
	// conventional trigger for an error_page redirect to a chooser/login page.
	w.WriteHeader(http.StatusUnauthorized)
}

// chosenInstance reads the choice cookie and resolves it against the configured instances.
// An unknown or forged value is treated as "no choice" and is never echoed back -- the
// value we return selects an nginx upstream, so it must always be one we configured.
func (s *Server) chosenInstance(r *http.Request) (Instance, bool) {
	cookie, err := r.Cookie(s.cfg.CookieName)
	if err != nil || cookie.Value == "" {
		return Instance{}, false
	}
	return s.cfg.Lookup(cookie.Value)
}

// handleChoose records the user's pick and sends them back where they came from.
func (s *Server) handleChoose(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "malformed form", http.StatusBadRequest)
		return
	}

	instance, ok := s.cfg.Lookup(r.PostFormValue("instance"))
	if !ok {
		http.Error(w, "unknown instance", http.StatusBadRequest)
		return
	}

	target, ok := sanitizeReturnPath(r.PostFormValue("return"))
	if !ok {
		http.Error(w, "invalid return path", http.StatusBadRequest)
		return
	}

	http.SetCookie(w, s.choiceCookie(instance.ID, 0))
	log.Printf("assigned instance=%s return=%s", instance.ID, target)
	http.Redirect(w, r, target, http.StatusFound)
}

// handleReset clears the choice so the next navigation shows the picker again. Note this
// does NOT move an already-authenticated user: their SESSION_INST_* cookie still routes
// them to the instance that holds their session, which is deliberate.
func (s *Server) handleReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	http.SetCookie(w, s.choiceCookie("", -1))

	target, ok := sanitizeReturnPath(r.PostFormValue("return"))
	if !ok {
		target = "/"
	}
	http.Redirect(w, r, target, http.StatusFound)
}

func (s *Server) handleInstances(w http.ResponseWriter, r *http.Request) {
	type payload struct {
		Instances []Instance `json:"instances"`
		Default   string     `json:"default"`
		Chosen    string     `json:"chosen,omitempty"`
	}

	body := payload{Instances: s.cfg.Instances, Default: s.cfg.DefaultInstance}
	if instance, ok := s.chosenInstance(r); ok {
		body.Chosen = instance.ID
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("failed to encode /instances response: %v", err)
	}
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = w.Write([]byte("ok\n"))
}

func (s *Server) choiceCookie(value string, maxAge int) *http.Cookie {
	return &http.Cookie{
		Name:  s.cfg.CookieName,
		Value: value,
		Path:  "/",
		// Scoped to the shared parent domain so the choice is visible at the global URL
		// as well as at any instance hostname.
		Domain:   s.cfg.CookieDomain,
		HttpOnly: true,
		Secure:   s.cfg.CookieSecure,
		// Lax keeps a cross-site form post from pinning someone to an instance, which is
		// the only meaningful abuse of this endpoint.
		SameSite: http.SameSiteLaxMode,
		MaxAge:   maxAge,
	}
}

// sanitizeReturnPath accepts only a same-origin path, never an absolute URL. That removes
// the open-redirect class outright: the picker is always served on the same origin as the
// global URL, so a relative target is all that is ever needed.
//
// An empty value is valid and means "/".
func sanitizeReturnPath(raw string) (string, bool) {
	if raw == "" {
		return "/", true
	}
	// Reject control characters outright (header/response splitting).
	for _, r := range raw {
		if r < 0x20 || r == 0x7f {
			return "", false
		}
	}
	// Must be rooted, and must not be scheme-relative ("//host" is treated as an absolute
	// URL by browsers). Backslashes are rejected because several browsers normalise "\" to
	// "/", making "/\evil.com" a scheme-relative URL.
	if !strings.HasPrefix(raw, "/") || strings.HasPrefix(raw, "//") || strings.Contains(raw, `\`) {
		return "", false
	}
	// A colon before the first slash would make this parse as a scheme; since we already
	// require a leading "/", reject any explicit scheme separator for belt and braces.
	if strings.Contains(raw, "://") {
		return "", false
	}
	return raw, true
}
