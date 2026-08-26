# Modelled on spin/Dockerfile. No dependencies to fetch -- the service is pure stdlib.
FROM golang:1.26.5 AS build

WORKDIR /app
COPY ./ ./

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o fleet-manager .

FROM alpine:3.24

RUN addgroup -S -g 10111 spinnaker \
    && adduser -S -G spinnaker -u 10111 spinnaker

COPY --from=build /app/fleet-manager /usr/local/bin/fleet-manager

USER spinnaker
EXPOSE 8080

# FLEET_INSTANCES is required and has no sensible default, so the container exits
# immediately with a clear message if it is missing.
ENTRYPOINT ["fleet-manager"]
