package main

import "testing"

// fakeEnv builds a getenv function over a map, so tests never touch the real environment.
func fakeEnv(vars map[string]string) func(string) string {
	return func(key string) string { return vars[key] }
}

func TestLoadConfigDefaults(t *testing.T) {
	cfg, err := LoadConfig(fakeEnv(map[string]string{
		"FLEET_INSTANCES": "inst-1=Instance One,inst-2=Instance Two",
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Listen != ":8080" {
		t.Errorf("Listen = %q, want :8080", cfg.Listen)
	}
	if cfg.CookieName != "SPIN_FLEET_CHOICE" {
		t.Errorf("CookieName = %q", cfg.CookieName)
	}
	if !cfg.CookieSecure {
		t.Error("CookieSecure should default to true; insecure cookies must be opt-in")
	}
	if cfg.DefaultInstance != "inst-1" {
		t.Errorf("DefaultInstance = %q, want the first instance", cfg.DefaultInstance)
	}
	if len(cfg.Instances) != 2 || cfg.Instances[1].Label != "Instance Two" {
		t.Errorf("Instances = %+v", cfg.Instances)
	}
}

func TestLoadConfigBareIdUsesIdAsLabel(t *testing.T) {
	cfg, err := LoadConfig(fakeEnv(map[string]string{"FLEET_INSTANCES": "inst-1"}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Instances[0].Label != "inst-1" {
		t.Errorf("Label = %q, want the id", cfg.Instances[0].Label)
	}
}

func TestLoadConfigCookieSecureOverride(t *testing.T) {
	cfg, err := LoadConfig(fakeEnv(map[string]string{
		"FLEET_INSTANCES":     "inst-1",
		"FLEET_COOKIE_SECURE": "false",
		"FLEET_COOKIE_DOMAIN": ".spinnaker.example.com",
		"FLEET_LISTEN":        ":9999",
		"FLEET_COOKIE_NAME":   "MY_CHOICE",
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.CookieSecure {
		t.Error("CookieSecure should be false when explicitly disabled")
	}
	if cfg.CookieDomain != ".spinnaker.example.com" || cfg.Listen != ":9999" || cfg.CookieName != "MY_CHOICE" {
		t.Errorf("overrides not applied: %+v", cfg)
	}
}

func TestLoadConfigRejectsBadInput(t *testing.T) {
	cases := map[string]map[string]string{
		"missing instances":     {},
		"empty instances":       {"FLEET_INSTANCES": "  "},
		"only separators":       {"FLEET_INSTANCES": ",,,"},
		"duplicate id":          {"FLEET_INSTANCES": "inst-1,inst-1"},
		"unknown default":       {"FLEET_INSTANCES": "inst-1", "FLEET_DEFAULT_INSTANCE": "nope"},
		"non-boolean secure":    {"FLEET_INSTANCES": "inst-1", "FLEET_COOKIE_SECURE": "yes-please"},
		"bad cookie name":       {"FLEET_INSTANCES": "inst-1", "FLEET_COOKIE_NAME": "bad name"},
		"id with space":         {"FLEET_INSTANCES": "inst 1"},
		"id with slash":         {"FLEET_INSTANCES": "inst/1"},
		"id with newline":       {"FLEET_INSTANCES": "inst-1\nX-Evil: 1"},
		"id starting with dash": {"FLEET_INSTANCES": "-inst"},
		"empty id with label":   {"FLEET_INSTANCES": "=Label"},
	}

	for name, env := range cases {
		t.Run(name, func(t *testing.T) {
			if cfg, err := LoadConfig(fakeEnv(env)); err == nil {
				t.Fatalf("expected an error, got config %+v", cfg)
			}
		})
	}
}

func TestConfigLookup(t *testing.T) {
	cfg, err := LoadConfig(fakeEnv(map[string]string{"FLEET_INSTANCES": "inst-1,inst-2"}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if _, ok := cfg.Lookup("inst-2"); !ok {
		t.Error("Lookup(inst-2) should succeed")
	}
	for _, missing := range []string{"", "inst-3", "INST-1", "../../etc/passwd"} {
		if _, ok := cfg.Lookup(missing); ok {
			t.Errorf("Lookup(%q) should fail", missing)
		}
	}
}
