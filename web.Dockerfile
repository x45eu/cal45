FROM golang:1.27-alpine AS builder
WORKDIR /app
COPY go.mod ./
COPY cmd ./cmd
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /cal45-web ./cmd/cal45-web

FROM alpine:3.22
RUN addgroup -S cal45 && adduser -S -G cal45 cal45
COPY --from=builder /cal45-web /usr/local/bin/cal45-web
USER cal45
EXPOSE 8080
ENV CAL45_WEB_BIND=0.0.0.0:8080
ENTRYPOINT ["/usr/local/bin/cal45-web"]
