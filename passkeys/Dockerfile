FROM golang:1.27.1-alpine AS builder
WORKDIR /app
COPY passkeys/go.mod passkeys/go.sum ./
RUN go mod download
COPY passkeys/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /cal45-auth ./cmd/cal45-auth-server

FROM alpine:3.22
RUN addgroup -S cal45 && adduser -S -G cal45 cal45
RUN mkdir -p /var/lib/cal45 && chown cal45:cal45 /var/lib/cal45
COPY --from=builder /cal45-auth /usr/local/bin/cal45-auth
USER cal45
EXPOSE 8082
ENTRYPOINT ["/usr/local/bin/cal45-auth"]
