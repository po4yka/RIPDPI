cmake_minimum_required(VERSION 3.22.1)

project(ripdpi_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(ripdpi SHARED ${BYE_DPI_SRC} native-lib.c utils.c)
target_include_directories(ripdpi PRIVATE byedpi)

set(RIPDPI_WARNING_FLAGS
    -Wall
    -Wextra
    -Wformat=2
    -Werror=implicit-function-declaration
    -Werror=incompatible-pointer-types
    -Werror=int-conversion
    -Werror=return-type
    -Wno-unused-parameter
)

target_compile_options(ripdpi PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500 ${RIPDPI_WARNING_FLAGS})
target_compile_definitions(ripdpi PRIVATE ANDROID_APP)
target_link_options(ripdpi PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

target_link_libraries(ripdpi PRIVATE android log)
