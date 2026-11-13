FROM golang:1.27.4-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /udp-echo .

FROM alpine:3.24.4

RUN adduser -D -H -u 10001 udp-echo
USER udp-echo
EXPOSE 9001/udp
ENTRYPOINT ["/udp-echo"]
COPY --from=build /udp-echo /udp-echo
