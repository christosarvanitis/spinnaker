// Command fleet-manager is a minimal instance-assignment service for validating a
// Spinnaker fleet end to end.
//
// It implements the edge router's auth_request contract (GET /assign -> 2xx plus
// X-Spinnaker-Instance) and serves a small page where a user picks which instance to land
// on. It is a validation fixture, NOT a production component -- see README.md.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)

	cfg, err := LoadConfig(OSGetenv)
	if err != nil {
		log.Fatalf("configuration error: %v", err)
	}

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           NewServer(cfg).Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ids := make([]string, 0, len(cfg.Instances))
	for _, instance := range cfg.Instances {
		ids = append(ids, instance.ID)
	}
	log.Printf("fleet-manager listening on %s instances=%v default=%s cookie=%s domain=%q secure=%t",
		cfg.Listen, ids, cfg.DefaultInstance, cfg.CookieName, cfg.CookieDomain, cfg.CookieSecure)

	// Shut down cleanly so the e2e harness can stop and restart us without leaking
	// half-open connections through the edge's keepalive pool.
	shutdown := make(chan os.Signal, 1)
	signal.Notify(shutdown, os.Interrupt, syscall.SIGTERM)

	go func() {
		<-shutdown
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Printf("graceful shutdown failed: %v", err)
		}
	}()

	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("server error: %v", err)
	}
	log.Print("fleet-manager stopped")
}
