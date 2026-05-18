#!/usr/bin/env python3
import os
import socket
import sys


MAX_DATAGRAM_SIZE = 64 * 1024


def daemonize(pid_file: str, log_file: str) -> None:
    first_pid = os.fork()
    if first_pid > 0:
        os._exit(0)

    os.setsid()
    second_pid = os.fork()
    if second_pid > 0:
        os._exit(0)

    os.chdir("/")
    os.umask(0o022)
    log_fd = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    null_fd = os.open(os.devnull, os.O_RDONLY)
    os.dup2(null_fd, sys.stdin.fileno())
    os.dup2(log_fd, sys.stdout.fileno())
    os.dup2(log_fd, sys.stderr.fileno())
    os.close(null_fd)
    os.close(log_fd)
    with open(pid_file, "w", encoding="utf-8") as pid_output:
        pid_output.write(f"{os.getpid()}\n")


def main() -> int:
    args = sys.argv[1:]
    if args:
        if len(args) != 5 or args[0] != "--daemonize" or args[1] != "--pid-file" or args[3] != "--log-file":
            raise SystemExit("usage: host-udp-echo.py [--daemonize --pid-file PATH --log-file PATH]")
        pid_file = args[2]
        log_file = args[4]
        daemonize(pid_file, log_file)

    host = os.environ.get("RIPDPI_UDP_ECHO_HOST", "0.0.0.0")
    port = int(os.environ.get("RIPDPI_UDP_ECHO_PORT", "9001"))
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((host, port))
        print(f"host udp echo listening on {host}:{port}", flush=True)
        while True:
            data, source = sock.recvfrom(MAX_DATAGRAM_SIZE)
            print(f"echo source={source[0]}:{source[1]} size={len(data)}", flush=True)
            sock.sendto(data, source)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(0)
    except Exception as error:
        print(f"host udp echo failed: {error}", file=sys.stderr, flush=True)
        raise
