package main

import (
	"fmt"
	"os"
	"regexp"
	"strconv"
	"strings"
)

// Instance is one Spinnaker instance a user can be assigned to. ID is what the edge router
// keys on; Label is only ever shown in the picker UI.
type Instance struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// Config is the fully-validated runtime configuration. Build it with LoadConfig.
type Config struct {
	Listen          string
	Instances       []Instance
	DefaultInstance string
	CookieName      string
	CookieDomain    string
	CookieSecure    bool
}

// Instance IDs end up in the X-Spinnaker-Instance response header, which the edge maps
// straight to an upstream. Restricting the charset here keeps header injection and
// surprising nginx map behaviour out of reach by construction, rather than relying on
// downstream validation.
var instanceIDPattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$`)

// Cookie names are tokens per RFC 6265; be conservative rather than exhaustive.
var cookieNamePattern = regexp.MustCompile(`^[a-zA-Z0-9!#$%&'*+\-.^_` + "`" + `|~]+$`)

// LoadConfig reads configuration from the environment and validates it. It returns an
// error rather than falling back to defaults for anything that would be unsafe or
// ambiguous to guess.
func LoadConfig(getenv func(string) string) (*Config, error) {
	cfg := &Config{
		Listen:       envOr(getenv, "FLEET_LISTEN", ":8080"),
		CookieName:   envOr(getenv, "FLEET_COOKIE_NAME", "SPIN_FLEET_CHOICE"),
		CookieDomain: getenv("FLEET_COOKIE_DOMAIN"),
	}

	instances, err := parseInstances(getenv("FLEET_INSTANCES"))
	if err != nil {
		return nil, err
	}
	cfg.Instances = instances

	if !cookieNamePattern.MatchString(cfg.CookieName) {
		return nil, fmt.Errorf("FLEET_COOKIE_NAME %q is not a valid cookie name", cfg.CookieName)
	}

	// Secure cookies are the right default, but the local e2e harness runs over plain
	// HTTP, where a Secure cookie would silently never be stored.
	secure := true
	if raw := getenv("FLEET_COOKIE_SECURE"); raw != "" {
		secure, err = strconv.ParseBool(raw)
		if err != nil {
			return nil, fmt.Errorf("FLEET_COOKIE_SECURE %q is not a boolean: %w", raw, err)
		}
	}
	cfg.CookieSecure = secure

	cfg.DefaultInstance = getenv("FLEET_DEFAULT_INSTANCE")
	if cfg.DefaultInstance == "" {
		cfg.DefaultInstance = instances[0].ID
	} else if _, ok := cfg.Lookup(cfg.DefaultInstance); !ok {
		return nil, fmt.Errorf("FLEET_DEFAULT_INSTANCE %q is not one of the configured instances", cfg.DefaultInstance)
	}

	return cfg, nil
}

// parseInstances reads "id=Label,id2=Label 2". A bare "id" is allowed and uses the id as
// its own label.
func parseInstances(raw string) ([]Instance, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, fmt.Errorf("FLEET_INSTANCES is required, e.g. FLEET_INSTANCES='inst-1=Instance 1,inst-2=Instance 2'")
	}

	var instances []Instance
	seen := map[string]bool{}

	for _, entry := range strings.Split(raw, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}

		id, label, found := strings.Cut(entry, "=")
		id = strings.TrimSpace(id)
		if !found {
			label = id
		}
		label = strings.TrimSpace(label)

		if !instanceIDPattern.MatchString(id) {
			return nil, fmt.Errorf("instance id %q must match %s", id, instanceIDPattern)
		}
		if seen[id] {
			return nil, fmt.Errorf("instance id %q is listed more than once", id)
		}
		if label == "" {
			label = id
		}

		seen[id] = true
		instances = append(instances, Instance{ID: id, Label: label})
	}

	if len(instances) == 0 {
		return nil, fmt.Errorf("FLEET_INSTANCES did not contain any usable entries")
	}
	return instances, nil
}

// Lookup resolves a configured instance by id. Every path that turns caller-supplied input
// into a routing decision must go through this.
func (c *Config) Lookup(id string) (Instance, bool) {
	for _, instance := range c.Instances {
		if instance.ID == id {
			return instance, true
		}
	}
	return Instance{}, false
}

func envOr(getenv func(string) string, key, fallback string) string {
	if v := strings.TrimSpace(getenv(key)); v != "" {
		return v
	}
	return fallback
}

// OSGetenv adapts os.Getenv to the injectable signature LoadConfig takes, so tests can
// supply a fake environment.
func OSGetenv(key string) string { return os.Getenv(key) }
