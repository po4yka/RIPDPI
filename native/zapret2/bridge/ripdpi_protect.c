#define _GNU_SOURCE
#include "ripdpi_protect.h"
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define PROTECT_TIMEOUT_MS 2000

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static bool wait_io(int fd, short events, int64_t deadline)
{
    for (;;) {
        int64_t now = monotonic_ms();
        if (now < 0) return false;
        if (now >= deadline) { errno = ETIMEDOUT; return false; }
        struct pollfd pfd = { .fd = fd, .events = events };
        int result = poll(&pfd, 1, (int)(deadline - now));
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) return false;
        if (!result) { errno = ETIMEDOUT; return false; }
        if (pfd.revents & events) return true;
        errno = ECONNRESET;
        return false;
    }
}

bool ripdpi_protect_socket(int fd)
{
    const char *path = getenv("RIPDPI_PROTECT_PATH");
    if (!path) return true;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(path);
    if (!length || length >= sizeof(address.sun_path)) { errno = EINVAL; return false; }
    memcpy(address.sun_path, path, length + 1);
    int64_t now = monotonic_ms();
    if (now < 0) return false;
    int64_t deadline = now + PROTECT_TIMEOUT_MS;
    int channel = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (channel < 0) return false;
    bool success = false;
    if (connect(channel, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + length + 1)) {
        if (errno != EINPROGRESS || !wait_io(channel, POLLOUT, deadline)) goto done;
        int error = 0;
        socklen_t size = sizeof(error);
        if (getsockopt(channel, SOL_SOCKET, SO_ERROR, &error, &size)) goto done;
        if (error) { errno = error; goto done; }
    }
    unsigned char byte = 0;
    struct iovec iov = { .iov_base = &byte, .iov_len = 1 };
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    for (;;) {
        if (!wait_io(channel, POLLOUT, deadline)) goto done;
        ssize_t sent = sendmsg(channel, &message, MSG_NOSIGNAL);
        if (sent < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (sent != 1) { if (sent >= 0) errno = EIO; goto done; }
        break;
    }
    for (;;) {
        if (!wait_io(channel, POLLIN, deadline)) goto done;
        ssize_t received = recv(channel, &byte, 1, 0);
        if (received < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (received != 1) { if (received >= 0) errno = ECONNRESET; goto done; }
        if (byte) { errno = EACCES; goto done; }
        success = true;
        break;
    }
done:
    {
        int saved_errno = errno;
        close(channel);
        errno = saved_errno;
    }
    return success;
}
