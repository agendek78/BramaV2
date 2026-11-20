idf_component_register(SRCS "brama_crypto.c" "brama_ecjpake.c"
                       INCLUDE_DIRS "include"
                       REQUIRES brama_ports mbedtls)
