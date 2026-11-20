idf_component_register(SRCS "outputs.c" "misc_hw.c" "nvs_hw.c" "ota_hw.c"
                       INCLUDE_DIRS "include"
                       REQUIRES brama_ports esp_driver_gpio esp_timer nvs_flash app_update esp_hw_support
                                esp_system esp_app_format)
