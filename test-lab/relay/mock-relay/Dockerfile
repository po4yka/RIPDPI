FROM golang:1.27.4-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /mock-relay .

FROM alpine:3.24.4

RUN adduser -D -H -u 10002 mock-relay
USER mock-relay
EXPOSE 10080/tcp
COPY --from=build /mock-relay /mock-relay
ENTRYPOINT ["/mock-relay"]
