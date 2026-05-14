FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN go build -o /out/quic-lab-server .

FROM alpine:3.20
COPY --from=build /out/quic-lab-server /usr/local/bin/quic-lab-server
EXPOSE 9443/tcp 9443/udp
ENTRYPOINT ["/usr/local/bin/quic-lab-server"]
