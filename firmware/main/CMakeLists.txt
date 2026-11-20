idf_component_register(SRCS "app_main.c" "service_console.c"
                       INCLUDE_DIRS "."
                       REQUIRES brama_ports brama_hw brama_ble brama_boot brama_actor brama_auth brama_rule brama_ota
                                brama_button brama_crypto console bootloader_support esp_timer esp_system
                                esp_app_format freertos log)
