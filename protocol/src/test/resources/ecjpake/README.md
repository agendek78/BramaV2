# Wektory EC-JPAKE (spec 11.4)

Pliki `klucz=hex`; wiersze `#` to komentarze.

- `mbedtls_selftest.txt` — wektory z self-testu `mbedtls/library/ecjpake.c` (mbedTLS 3.6.2 z ESP-IDF 5.4), licencja Apache-2.0. Sprawdzają, że Kotlin w obu rolach z tymi samymi skalarami wylicza `pms` mbedTLS.
- `interop.txt` — klient Kotlin (`EcJpakeInteropTest.deterministicScalars`, PIN `482915`) ↔ serwer mbedTLS (`harness.c`). mbedTLS zweryfikował obie rundy klienta (`read_round_one/two` == 0), `pms` z mbedTLS == `S` Kotlina.

## Ponowna generacja `interop.txt`

mbedTLS z ESP-IDF nie buduje się `make` bez submodułu `framework`, więc kompiluje się tylko potrzebne pliki:

```sh
M=<esp-idf>/components/mbedtls/mbedtls
cat > ecj_config.h <<'H'
#define MBEDTLS_BIGNUM_C
#define MBEDTLS_ECP_C
#define MBEDTLS_ECP_DP_SECP256R1_ENABLED
#define MBEDTLS_ECJPAKE_C
#define MBEDTLS_MD_C
#define MBEDTLS_SHA256_C
H
gcc -DMBEDTLS_CONFIG_FILE='"ecj_config.h"' -I. -I$M/include -I$M/library harness.c \
  $M/library/{ecjpake,ecp,ecp_curves,bignum,bignum_core,constant_time,md,sha256,platform_util}.c -o harness

./gradlew :protocol:test --tests '*EcJpakeInteropTest.generateClientRounds' -PecjpakeGen=start --rerun-tasks
./harness 482915 <cli_one z protocol/build/ecjpake/client.txt> > srv.txt
./gradlew :protocol:test --tests '*EcJpakeInteropTest.generateClientRounds' -PecjpakeGen=$PWD/srv.txt --rerun-tasks
./harness 482915 <cli_one> <cli_two> > srv2.txt   # kod wyjścia 0 = mbedTLS przyjął rundy klienta
```

`interop.txt` = `pin`, `cli_one`, `cli_two` z `client.txt` + zawartość `srv2.txt`.
