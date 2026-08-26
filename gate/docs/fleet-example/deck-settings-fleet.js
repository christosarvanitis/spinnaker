// Fleet-relevant portion of Deck's settings.js, for instance 1 of 2.
//
// This is an EXCERPT, not a drop-in file. In Kubernetes, Deck's settings.js is mounted
// from a ConfigMap at /opt/spinnaker/config/settings.js and copied over
// /opt/deck/html/settings.js at container start (see deck/docker/run-apache2.sh). Start
// from your existing settings.js -- for example spinnaker-kustomize/base/deck/files/
// settings.js -- and apply the three changes below.
//
// Note the API_HOST env var does NOT feed this file; it only rewrites the apache
// ProxyPass line in the Deck image, which a fleet does not use because the edge proxies
// Gate itself. Set these values here, in the ConfigMap.

// ---------------------------------------------------------------------------
// 1. Point Deck at Gate through the same origin.
// ---------------------------------------------------------------------------
// Relative, so it resolves against whatever origin the browser is on. Deck's
// ApiService resolves request URLs with `new URL(url, window.location.origin)`, so a
// relative gateUrl works unchanged -- and it means Deck served from the global URL
// talks to Gate through the global URL, keeping the instance hostname out of the
// browser. It also puts Deck and Gate on one origin, so CORS never comes up.
//
// /api/v1 must match Gate's server.servlet.context-path.
var gateHost = '/api/v1';
var authEndpoint = gateHost + '/auth/user';

window.spinnakerSettings = {
  // ... your existing settings ...

  authEnabled: true,
  authEndpoint: authEndpoint,
  gateUrl: gateHost,

  // -------------------------------------------------------------------------
  // 2. Declare fleet mode.
  // -------------------------------------------------------------------------
  // When enabled, Deck redirects a non-admin who has somehow loaded the UI from an
  // instance origin back to globalUrl, preserving path/query/hash. Admins are left
  // alone. This runs in bootstrapDeck immediately after authentication resolves,
  // because it needs isAdmin from /auth/user.
  //
  // It matters because Gate cannot protect Deck's static assets, and because Deck
  // builds shareable links from window.location.origin -- without this, one user
  // landing on an instance origin propagates that URL to everyone they paste it to.
  fleet: {
    enabled: true,
    // Same on every instance. Must be a full origin, not a path.
    globalUrl: 'https://spinnaker.example.com',
    // PER-INSTANCE: 'inst-2' on the other instance. Diagnostics only.
    instanceId: 'inst-1',
  },

  // -------------------------------------------------------------------------
  // 3. Make sure Fiat is on, so isAdmin is meaningful.
  // -------------------------------------------------------------------------
  feature: {
    // ... your existing feature flags ...
    fiatEnabled: true,
  },
};
