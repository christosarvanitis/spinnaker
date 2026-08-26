package main

import (
	"html/template"
	"log"
	"net/http"
)

// chooserTemplate renders the picker. html/template escapes every interpolation, so
// operator-supplied instance labels cannot inject markup.
//
// Form actions are deliberately RELATIVE ("choose", not "/choose"): the edge serves this
// page under a path prefix (/_fleet/), and a relative action inherits that prefix without
// the service needing to know or be told what it is.
var chooserTemplate = template.Must(template.New("chooser").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Choose a Spinnaker instance</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f6f7f9; }
  main { background: #fff; padding: 2rem 2.25rem; border-radius: 10px; max-width: 34rem;
         box-shadow: 0 1px 3px rgba(0,0,0,.12), 0 8px 24px rgba(0,0,0,.08); }
  h1 { font-size: 1.25rem; margin: 0 0 .35rem; }
  p.sub { margin: 0 0 1.25rem; color: #5b6472; font-size: .9rem; line-height: 1.45; }
  ul { list-style: none; padding: 0; margin: 0 0 1rem; display: grid; gap: .6rem; }
  button.pick { width: 100%; text-align: left; padding: .8rem 1rem; font-size: .95rem;
                border: 1px solid #d3d8de; border-radius: 8px; background: #fff; cursor: pointer; }
  button.pick:hover { border-color: #1a73e8; background: #f3f8ff; }
  button.pick[aria-current="true"] { border-color: #1a73e8; box-shadow: inset 0 0 0 1px #1a73e8; }
  .id { display: block; color: #6b7480; font-size: .78rem; margin-top: .15rem; }
  .current { font-size: .82rem; color: #5b6472; margin: 0 0 1rem; }
  footer { border-top: 1px solid #eceff3; margin-top: 1.25rem; padding-top: .85rem; }
  button.link { background: none; border: 0; padding: 0; color: #1a73e8; cursor: pointer;
                font-size: .82rem; text-decoration: underline; }
  @media (prefers-color-scheme: dark) {
    body { background: #14171a; } main { background: #1d2126; box-shadow: none; }
    button.pick { background: #23282e; border-color: #343b43; color: #e6e9ec; }
    p.sub, .id, .current { color: #9aa4b0; } footer { border-color: #2c3238; }
  }
</style>
</head>
<body>
<main>
  <h1>Choose a Spinnaker instance</h1>
  <p class="sub">
    This deployment runs several independent Spinnaker instances behind one address.
    Pick the one to work on &mdash; you will stay on it until your session ends.
  </p>

  {{if .Chosen}}<p class="current">Currently assigned: <strong>{{.Chosen}}</strong></p>{{end}}

  <ul>
    {{range .Instances}}
    <li>
      <form method="post" action="choose">
        <input type="hidden" name="instance" value="{{.ID}}">
        <input type="hidden" name="return" value="{{$.Return}}">
        <button class="pick" type="submit" {{if eq .ID $.Chosen}}aria-current="true"{{end}}>
          {{.Label}}<span class="id">{{.ID}}</span>
        </button>
      </form>
    </li>
    {{end}}
  </ul>

  {{if .Chosen}}
  <footer>
    <form method="post" action="reset">
      <input type="hidden" name="return" value="{{.Return}}">
      <button class="link" type="submit">Clear my assignment</button>
    </form>
  </footer>
  {{end}}
</main>
</body>
</html>
`))

type chooserView struct {
	Instances []Instance
	Chosen    string
	Return    string
}

// handleChooser serves the picker. It is reachable two ways, both intentional: the edge
// redirects here when nobody has chosen yet, and a user can visit it directly to switch
// instances.
func (s *Server) handleChooser(w http.ResponseWriter, r *http.Request) {
	// ServeMux's "/" pattern is a catch-all; anything we do not recognise is a 404 rather
	// than a surprise picker page.
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}

	returnTo, ok := sanitizeReturnPath(r.URL.Query().Get("return"))
	if !ok {
		// Don't fail the whole page on a bad return value -- just drop it, so a user who
		// followed a mangled link can still pick an instance.
		returnTo = "/"
	}

	view := chooserView{Instances: s.cfg.Instances, Return: returnTo}
	if instance, found := s.chosenInstance(r); found {
		view.Chosen = instance.ID
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// The picker reflects per-user cookie state, so it must never be cached by a shared
	// proxy or the browser.
	w.Header().Set("Cache-Control", "no-store")
	if err := chooserTemplate.Execute(w, view); err != nil {
		log.Printf("failed to render chooser: %v", err)
	}
}
