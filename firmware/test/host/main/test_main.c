#include <stdlib.h>
#include "unity.h"
#include "test_registry.h"

void setUp(void) {}
void tearDown(void) {}

void app_main(void)
{
    UNITY_BEGIN();
    run_all_tests();
    exit(UNITY_END());
}
