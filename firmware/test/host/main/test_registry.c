#include "test_registry.h"

void run_all_tests(void)
{
    run_hello_tests();
    run_crypto_tests();
    run_ecjpake_tests();
    run_proto_tests();
    run_session_tests();
    run_negative_tests();
    run_auth_tests();
    run_rule_tests();
    run_actor_tests();
    run_ota_tests();
    run_button_tests();
    run_boot_tests();
}
