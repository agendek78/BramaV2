idf_component_register(SRCS "ble.c" "store.c"
                       INCLUDE_DIRS "include"
                       REQUIRES brama_ports brama_proto brama_crypto brama_session brama_auth brama_actor brama_ota bt
                                esp_timer esp_system esp_app_format freertos log)
