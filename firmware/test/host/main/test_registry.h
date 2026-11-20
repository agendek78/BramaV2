#pragma once
// Każdy plik testów eksportuje run_<grupa>_tests(); test_registry.c wywołuje je wszystkie.
void run_all_tests(void);
void run_hello_tests(void);
void run_crypto_tests(void);
void run_ecjpake_tests(void);
void run_proto_tests(void);
void run_session_tests(void);
void run_negative_tests(void);
void run_auth_tests(void);
void run_rule_tests(void);
void run_actor_tests(void);
void run_ota_tests(void);
void run_button_tests(void);
void run_boot_tests(void);
