#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/if.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

enum result_index {
    RESULT_OK = 0,
    RESULT_RESPONSE = 1,
    RESULT_BOUND_DEVICE = 2,
    RESULT_FAILURE_KIND = 3,
    RESULT_ERRNO = 4,
    RESULT_LOCAL_PORT = 5,
    RESULT_FAILURE_STAGE = 6,
    RESULT_LOCAL_ADDRESS = 7,
    RESULT_WIDTH = 8,
};

static const char *failure_kind_for_errno(int error_number) {
    switch (error_number) {
        case ECONNRESET:
            return "CONNECTION_RESET";
        case EPIPE:
            return "BROKEN_PIPE";
        case ETIMEDOUT:
        case EAGAIN:
            return "TIMEOUT";
        default:
            return "ERRNO";
    }
}

struct deadline {
    int64_t monotonic_nanoseconds;
};

static bool set_result_string(JNIEnv *env, jobjectArray result, enum result_index index, const char *value) {
    if (value == NULL) {
        return true;
    }
    jstring string_value = (*env)->NewStringUTF(env, value);
    if (string_value == NULL || (*env)->ExceptionCheck(env)) {
        return false;
    }
    (*env)->SetObjectArrayElement(env, result, index, string_value);
    if ((*env)->ExceptionCheck(env)) {
        return false;
    }
    (*env)->DeleteLocalRef(env, string_value);
    return true;
}

static jobjectArray new_result(JNIEnv *env) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL || (*env)->ExceptionCheck(env)) {
        return NULL;
    }
    jobjectArray result = (*env)->NewObjectArray(env, RESULT_WIDTH, string_class, NULL);
    if (result == NULL || (*env)->ExceptionCheck(env)) {
        return NULL;
    }
    (*env)->DeleteLocalRef(env, string_class);
    if (!set_result_string(env, result, RESULT_OK, "false")) {
        return NULL;
    }
    return result;
}

static bool set_result_error(
    JNIEnv *env,
    jobjectArray result,
    const char *stage,
    int error_number,
    const char *bound_device) {
    char errno_text[16];
    (void)snprintf(errno_text, sizeof(errno_text), "%d", error_number);
    return set_result_string(env, result, RESULT_FAILURE_STAGE, stage) &&
           set_result_string(env, result, RESULT_FAILURE_KIND, failure_kind_for_errno(error_number)) &&
           set_result_string(env, result, RESULT_ERRNO, errno_text) &&
           set_result_string(env, result, RESULT_BOUND_DEVICE, bound_device);
}

static bool copy_device(JNIEnv *env, jbyteArray device_utf8, char device[IFNAMSIZ]) {
    if (device_utf8 == NULL) {
        errno = EINVAL;
        return false;
    }
    const jsize length = (*env)->GetArrayLength(env, device_utf8);
    if ((*env)->ExceptionCheck(env)) {
        errno = EFAULT;
        return false;
    }
    if (length <= 0 || length >= IFNAMSIZ) {
        errno = EINVAL;
        return false;
    }
    memset(device, 0, IFNAMSIZ);
    (*env)->GetByteArrayRegion(env, device_utf8, 0, length, (jbyte *)device);
    if ((*env)->ExceptionCheck(env)) {
        errno = EFAULT;
        return false;
    }
    return true;
}

static int64_t monotonic_nanoseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

static int make_deadline(int timeout_ms, struct deadline *deadline) {
    if (timeout_ms < 0) {
        errno = EINVAL;
        return -1;
    }
    const int64_t now = monotonic_nanoseconds();
    if (now < 0) {
        return -1;
    }
    deadline->monotonic_nanoseconds = now + (int64_t)timeout_ms * 1000000LL;
    return 0;
}

static int remaining_milliseconds(const struct deadline *deadline) {
    const int64_t now = monotonic_nanoseconds();
    if (now < 0) {
        return -1;
    }
    const int64_t remaining_nanoseconds = deadline->monotonic_nanoseconds - now;
    if (remaining_nanoseconds <= 0) {
        errno = ETIMEDOUT;
        return -1;
    }
    const int64_t rounded_up_milliseconds = (remaining_nanoseconds + 999999LL) / 1000000LL;
    return rounded_up_milliseconds > INT_MAX ? INT_MAX : (int)rounded_up_milliseconds;
}

static int wait_for_fd(int fd, short events, const struct deadline *deadline) {
    struct pollfd descriptor = {.fd = fd, .events = events, .revents = 0};
    for (;;) {
        const int timeout_ms = remaining_milliseconds(deadline);
        if (timeout_ms < 0) {
            return -1;
        }
        descriptor.revents = 0;
        const int poll_result = poll(&descriptor, 1, timeout_ms);
        if (poll_result < 0 && errno == EINTR) {
            continue;
        }
        if (poll_result == 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (poll_result < 0) {
            return -1;
        }
        if ((descriptor.revents & events) != 0) {
            return 0;
        }
        if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            int socket_error = 0;
            socklen_t error_length = sizeof(socket_error);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_length) == 0 && socket_error != 0) {
                errno = socket_error;
            } else if ((descriptor.revents & POLLHUP) != 0) {
                errno = ECONNRESET;
            } else {
                errno = EIO;
            }
            return -1;
        }
        errno = EIO;
        return -1;
    }
}

static int connect_with_timeout(int fd, const struct sockaddr *address, socklen_t length, int timeout_ms) {
    struct deadline deadline;
    if (make_deadline(timeout_ms, &deadline) != 0) {
        return -1;
    }
    const int original_flags = fcntl(fd, F_GETFL, 0);
    if (original_flags < 0 || fcntl(fd, F_SETFL, original_flags | O_NONBLOCK) != 0) {
        return -1;
    }
    int connect_result = connect(fd, address, length);
    if (connect_result != 0 && errno == EINPROGRESS) {
        connect_result = wait_for_fd(fd, POLLOUT, &deadline);
        if (connect_result == 0) {
            int socket_error = 0;
            socklen_t error_length = sizeof(socket_error);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_length) != 0) {
                connect_result = -1;
            } else if (socket_error != 0) {
                errno = socket_error;
                connect_result = -1;
            }
        }
    }
    const int saved_errno = errno;
    if (fcntl(fd, F_SETFL, original_flags) != 0 && connect_result == 0) {
        return -1;
    }
    errno = saved_errno;
    return connect_result;
}

static int open_bound_socket(
    JNIEnv *env,
    jstring host,
    jint port,
    int socket_type,
    int connect_timeout_ms,
    jbyteArray device_utf8,
    char device[IFNAMSIZ],
    const char **failure_stage) {
    memset(device, 0, IFNAMSIZ);
    if (!copy_device(env, device_utf8, device)) {
        *failure_stage = "bind";
        return -1;
    }

    if (host == NULL) {
        device[0] = '\0';
        errno = EINVAL;
        *failure_stage = "resolve";
        return -1;
    }
    const char *host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    if (host_chars == NULL || (*env)->ExceptionCheck(env)) {
        device[0] = '\0';
        errno = EFAULT;
        *failure_stage = "resolve";
        return -1;
    }
    char port_text[8];
    (void)snprintf(port_text, sizeof(port_text), "%d", port);
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_socktype = socket_type, .ai_flags = AI_NUMERICHOST};
    struct addrinfo *addresses = NULL;
    const int resolve_result = getaddrinfo(host_chars, port_text, &hints, &addresses);
    (*env)->ReleaseStringUTFChars(env, host, host_chars);
    if (resolve_result != 0 || addresses == NULL) {
        device[0] = '\0';
        errno = EADDRNOTAVAIL;
        *failure_stage = "resolve";
        return -1;
    }

    int socket_fd = socket(addresses->ai_family, addresses->ai_socktype, addresses->ai_protocol);
    if (socket_fd < 0) {
        device[0] = '\0';
        *failure_stage = "socket";
        freeaddrinfo(addresses);
        return -1;
    }
    const size_t device_length = strnlen(device, IFNAMSIZ - 1) + 1;
    if (setsockopt(socket_fd, SOL_SOCKET, SO_BINDTODEVICE, device, (socklen_t)device_length) != 0) {
        const int saved_errno = errno;
        close(socket_fd);
        freeaddrinfo(addresses);
        device[0] = '\0';
        errno = saved_errno;
        *failure_stage = "bind";
        return -1;
    }

    *failure_stage = "connect";
    const int connect_result =
        socket_type == SOCK_STREAM
            ? connect_with_timeout(socket_fd, addresses->ai_addr, addresses->ai_addrlen, connect_timeout_ms)
            : connect(socket_fd, addresses->ai_addr, addresses->ai_addrlen);
    const int saved_errno = errno;
    freeaddrinfo(addresses);
    if (connect_result != 0) {
        close(socket_fd);
        errno = saved_errno;
        return -1;
    }
    return socket_fd;
}

static bool set_local_endpoint(JNIEnv *env, jobjectArray result, int fd) {
    struct sockaddr_storage local_address;
    socklen_t local_length = sizeof(local_address);
    if (getsockname(fd, (struct sockaddr *)&local_address, &local_length) != 0) {
        return true;
    }
    uint16_t port = 0;
    const void *address_bytes = NULL;
    if (local_address.ss_family == AF_INET) {
        const struct sockaddr_in *ipv4 = (const struct sockaddr_in *)&local_address;
        port = ntohs(ipv4->sin_port);
        address_bytes = &ipv4->sin_addr;
    } else if (local_address.ss_family == AF_INET6) {
        const struct sockaddr_in6 *ipv6 = (const struct sockaddr_in6 *)&local_address;
        port = ntohs(ipv6->sin6_port);
        address_bytes = &ipv6->sin6_addr;
    }
    char address_text[INET6_ADDRSTRLEN];
    if (address_bytes == NULL || inet_ntop(local_address.ss_family, address_bytes, address_text, sizeof(address_text)) == NULL) {
        return true;
    }
    char port_text[8];
    (void)snprintf(port_text, sizeof(port_text), "%u", port);
    return set_result_string(env, result, RESULT_LOCAL_PORT, port_text) &&
           set_result_string(env, result, RESULT_LOCAL_ADDRESS, address_text);
}

static bool copy_payload(JNIEnv *env, jbyteArray payload, uint8_t **bytes, jsize *length) {
    if (payload == NULL) {
        errno = EINVAL;
        return false;
    }
    *length = (*env)->GetArrayLength(env, payload);
    if ((*env)->ExceptionCheck(env)) {
        errno = EFAULT;
        return false;
    }
    *bytes = malloc((size_t)(*length > 0 ? *length : 1));
    if (*bytes == NULL) {
        errno = ENOMEM;
        return false;
    }
    if (*length > 0) {
        (*env)->GetByteArrayRegion(env, payload, 0, *length, (jbyte *)*bytes);
        if ((*env)->ExceptionCheck(env)) {
            free(*bytes);
            *bytes = NULL;
            errno = EFAULT;
            return false;
        }
    }
    return true;
}

static int send_all(int fd, const uint8_t *bytes, size_t length, int timeout_ms) {
    struct deadline deadline;
    if (make_deadline(timeout_ms, &deadline) != 0) {
        return -1;
    }
    size_t sent = 0;
    while (sent < length) {
        if (wait_for_fd(fd, POLLOUT, &deadline) != 0) {
            return -1;
        }
        const ssize_t result = send(fd, bytes + sent, length - sent, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        if (result <= 0) {
            if (result == 0) {
                errno = EPIPE;
            }
            return -1;
        }
        sent += (size_t)result;
    }
    return 0;
}

static ssize_t receive_exact(int fd, uint8_t *bytes, size_t length, int timeout_ms) {
    struct deadline deadline;
    if (make_deadline(timeout_ms, &deadline) != 0) {
        return -1;
    }
    size_t received = 0;
    while (received < length) {
        if (wait_for_fd(fd, POLLIN, &deadline) != 0) {
            return -1;
        }
        const ssize_t result = recv(fd, bytes + received, length - received, MSG_DONTWAIT);
        if (result < 0 && errno == EINTR) {
            continue;
        }
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        if (result <= 0) {
            if (result == 0) {
                errno = ECONNRESET;
            }
            return -1;
        }
        received += (size_t)result;
    }
    return (ssize_t)received;
}

static int send_datagram(
    int fd,
    const uint8_t *bytes,
    size_t length,
    const struct deadline *deadline) {
    for (;;) {
        if (wait_for_fd(fd, POLLOUT, deadline) != 0) {
            return -1;
        }
        const ssize_t sent = send(fd, bytes, length, MSG_DONTWAIT);
        if (sent < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        if (sent < 0) {
            return -1;
        }
        if ((size_t)sent != length) {
            errno = EIO;
            return -1;
        }
        return 0;
    }
}

static ssize_t receive_datagram(
    int fd,
    uint8_t *bytes,
    size_t capacity,
    const struct deadline *deadline) {
    for (;;) {
        if (wait_for_fd(fd, POLLIN, deadline) != 0) {
            return -1;
        }
        const ssize_t received = recv(fd, bytes, capacity, MSG_DONTWAIT);
        if (received < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        return received;
    }
}

static bool set_success(
    JNIEnv *env,
    jobjectArray result,
    const char *device,
    const uint8_t *response,
    size_t length) {
    char *response_text = malloc(length + 1);
    if (response_text == NULL) {
        return set_result_error(env, result, "decode", ENOMEM, device);
    }
    memcpy(response_text, response, length);
    response_text[length] = '\0';
    const bool success = set_result_string(env, result, RESULT_OK, "true") &&
                         set_result_string(env, result, RESULT_RESPONSE, response_text) &&
                         set_result_string(env, result, RESULT_BOUND_DEVICE, device);
    free(response_text);
    return success;
}

static bool set_success_hex(
    JNIEnv *env,
    jobjectArray result,
    const char *device,
    const uint8_t *response,
    size_t length) {
    static const char hex[] = "0123456789abcdef";
    if (length > (SIZE_MAX - 1) / 2) {
        return set_result_error(env, result, "decode", EOVERFLOW, device);
    }
    char *response_text = malloc(length * 2 + 1);
    if (response_text == NULL) {
        return set_result_error(env, result, "decode", ENOMEM, device);
    }
    for (size_t index = 0; index < length; index++) {
        response_text[index * 2] = hex[(response[index] >> 4) & 0x0f];
        response_text[index * 2 + 1] = hex[response[index] & 0x0f];
    }
    response_text[length * 2] = '\0';
    const bool success = set_result_string(env, result, RESULT_OK, "true") &&
                         set_result_string(env, result, RESULT_RESPONSE, response_text) &&
                         set_result_string(env, result, RESULT_BOUND_DEVICE, device);
    free(response_text);
    return success;
}

JNIEXPORT jobjectArray JNICALL
Java_com_poyka_ripdpi_e2e_TestSocketBinder_nativeTcpRoundTrip(
    JNIEnv *env,
    jobject receiver,
    jstring host,
    jint port,
    jbyteArray payload,
    jint connect_timeout_ms,
    jint read_timeout_ms,
    jbyteArray device_utf8) {
    (void)receiver;
    jobjectArray result = new_result(env);
    if (result == NULL) {
        return NULL;
    }
    uint8_t *payload_bytes = NULL;
    jsize payload_length = 0;
    if (!copy_payload(env, payload, &payload_bytes, &payload_length)) {
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, "payload", errno, NULL) ? result : NULL;
    }
    char device[IFNAMSIZ];
    const char *failure_stage = "socket";
    int socket_fd =
        open_bound_socket(env, host, port, SOCK_STREAM, connect_timeout_ms, device_utf8, device, &failure_stage);
    if (socket_fd < 0) {
        const int saved_errno = errno;
        free(payload_bytes);
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, failure_stage, saved_errno, device[0] == '\0' ? NULL : device)
                   ? result
                   : NULL;
    }
    if (!set_local_endpoint(env, result, socket_fd)) {
        close(socket_fd);
        free(payload_bytes);
        return NULL;
    }
    if (send_all(socket_fd, payload_bytes, (size_t)payload_length, connect_timeout_ms) != 0) {
        if (!set_result_error(env, result, "send", errno, device)) {
            close(socket_fd);
            free(payload_bytes);
            return NULL;
        }
    } else {
        uint8_t *response = malloc((size_t)(payload_length > 0 ? payload_length : 1));
        if (response == NULL) {
            if (!set_result_error(env, result, "receive", ENOMEM, device)) {
                close(socket_fd);
                free(payload_bytes);
                return NULL;
            }
        } else {
            const ssize_t received = receive_exact(socket_fd, response, (size_t)payload_length, read_timeout_ms);
            if (received < 0) {
                if (!set_result_error(env, result, "receive", errno, device)) {
                    free(response);
                    close(socket_fd);
                    free(payload_bytes);
                    return NULL;
                }
            } else {
                if (!set_success(env, result, device, response, (size_t)received)) {
                    free(response);
                    close(socket_fd);
                    free(payload_bytes);
                    return NULL;
                }
            }
            free(response);
        }
    }
    close(socket_fd);
    free(payload_bytes);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_poyka_ripdpi_e2e_TestSocketBinder_nativeDnsRoundTrip(
    JNIEnv *env,
    jobject receiver,
    jstring host,
    jint port,
    jbyteArray query,
    jint timeout_ms,
    jbyteArray device_utf8) {
    (void)receiver;
    jobjectArray result = new_result(env);
    if (result == NULL) {
        return NULL;
    }
    uint8_t *query_bytes = NULL;
    jsize query_length = 0;
    if (!copy_payload(env, query, &query_bytes, &query_length)) {
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, "payload", errno, NULL) ? result : NULL;
    }
    char device[IFNAMSIZ];
    const char *failure_stage = "socket";
    int socket_fd = open_bound_socket(env, host, port, SOCK_DGRAM, timeout_ms, device_utf8, device, &failure_stage);
    if (socket_fd < 0) {
        const int saved_errno = errno;
        free(query_bytes);
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, failure_stage, saved_errno, device[0] == '\0' ? NULL : device)
                   ? result
                   : NULL;
    }
    if (!set_local_endpoint(env, result, socket_fd)) {
        close(socket_fd);
        free(query_bytes);
        return NULL;
    }
    struct deadline deadline;
    if (make_deadline(timeout_ms, &deadline) != 0 ||
        send_datagram(socket_fd, query_bytes, (size_t)query_length, &deadline) != 0) {
        if (!set_result_error(env, result, "send", errno, device)) {
            close(socket_fd);
            free(query_bytes);
            return NULL;
        }
    } else {
        uint8_t response[4096];
        const ssize_t received = receive_datagram(socket_fd, response, sizeof(response), &deadline);
        if (received < 0) {
            if (!set_result_error(env, result, "receive", errno, device)) {
                close(socket_fd);
                free(query_bytes);
                return NULL;
            }
        } else {
            if (!set_success_hex(env, result, device, response, (size_t)received)) {
                close(socket_fd);
                free(query_bytes);
                return NULL;
            }
        }
    }
    close(socket_fd);
    free(query_bytes);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_poyka_ripdpi_e2e_TestSocketBinder_nativeUdpRoundTrip(
    JNIEnv *env,
    jobject receiver,
    jstring host,
    jint port,
    jbyteArray payload,
    jint timeout_ms,
    jbyteArray device_utf8) {
    (void)receiver;
    jobjectArray result = new_result(env);
    if (result == NULL) {
        return NULL;
    }
    uint8_t *payload_bytes = NULL;
    jsize payload_length = 0;
    if (!copy_payload(env, payload, &payload_bytes, &payload_length)) {
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, "payload", errno, NULL) ? result : NULL;
    }
    char device[IFNAMSIZ];
    const char *failure_stage = "socket";
    int socket_fd = open_bound_socket(env, host, port, SOCK_DGRAM, timeout_ms, device_utf8, device, &failure_stage);
    if (socket_fd < 0) {
        const int saved_errno = errno;
        free(payload_bytes);
        if ((*env)->ExceptionCheck(env)) {
            return NULL;
        }
        return set_result_error(env, result, failure_stage, saved_errno, device[0] == '\0' ? NULL : device)
                   ? result
                   : NULL;
    }
    if (!set_local_endpoint(env, result, socket_fd)) {
        close(socket_fd);
        free(payload_bytes);
        return NULL;
    }
    struct deadline deadline;
    if (make_deadline(timeout_ms, &deadline) != 0 ||
        send_datagram(socket_fd, payload_bytes, (size_t)payload_length, &deadline) != 0) {
        if (!set_result_error(env, result, "send", errno, device)) {
            close(socket_fd);
            free(payload_bytes);
            return NULL;
        }
    } else {
        uint8_t response[4096];
        const ssize_t received = receive_datagram(socket_fd, response, sizeof(response), &deadline);
        if (received < 0) {
            if (!set_result_error(env, result, "receive", errno, device)) {
                close(socket_fd);
                free(payload_bytes);
                return NULL;
            }
        } else {
            if (!set_success(env, result, device, response, (size_t)received)) {
                close(socket_fd);
                free(payload_bytes);
                return NULL;
            }
        }
    }
    close(socket_fd);
    free(payload_bytes);
    return result;
}
