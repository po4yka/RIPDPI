#ifndef RIPDPI_PROTECT_H
#define RIPDPI_PROTECT_H
#include <stdbool.h>
/* An advertised protection server is mandatory; missing ACK fails closed. */
bool ripdpi_protect_socket(int fd);
#endif
