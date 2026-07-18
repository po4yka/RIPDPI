cmake_minimum_required(VERSION 3.22.1)
project(test_socket_binder C)

add_library(test_socket_binder SHARED test_socket_binder.c)
target_compile_options(test_socket_binder PRIVATE -Wall -Wextra -Werror)
