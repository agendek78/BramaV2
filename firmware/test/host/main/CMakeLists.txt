idf_component_register(SRCS "test_main.c" "test_registry.c" "test_util.c" "test_hello.c" "test_crypto.c" "test_vectors.c" "test_ecjpake.c" "test_proto.c" "test_session.c" "test_negative.c" "fake_nvs.c" "test_auth.c" "test_rule.c" "test_actor.c" "test_ota.c" "test_button.c" "test_boot.c"
                       INCLUDE_DIRS "."
                       REQUIRES unity brama_ports brama_crypto brama_proto brama_session brama_auth brama_rule brama_actor brama_ota brama_button brama_boot mbedtls)

# IDF v6.0.1, target linux: libmbed-builtin odwołuje się do esp_mbedtls_mem_* z libtfpsacrypto, która jest
# linkowana wcześniej — wymuszamy wciągnięcie esp_mem.o przy pierwszym przebiegu.
target_link_libraries(${COMPONENT_LIB} INTERFACE "-Wl,-u,esp_mbedtls_mem_calloc" "-Wl,-u,esp_mbedtls_mem_free")

target_compile_definitions(${COMPONENT_LIB} PRIVATE BRAMA_RES_DIR="${CMAKE_CURRENT_LIST_DIR}/../../../../protocol/src/test/resources")
