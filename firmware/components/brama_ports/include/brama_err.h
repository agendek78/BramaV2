#pragma once
// Wspólne kody wyników czystej logiki (0 = sukces).

#define BRAMA_OK 0
#define BRAMA_ERR_AUTH (-1)      // zły tag/HMAC/ZKP — bez ujawniania szczegółów
#define BRAMA_ERR_ARG (-2)       // zły argument wywołania
#define BRAMA_ERR_FORMAT (-3)    // zły format danych z łącza
#define BRAMA_ERR_CRYPTO (-4)    // błąd biblioteki kryptograficznej
#define BRAMA_ERR_STATE (-5)     // operacja niedozwolona w bieżącym stanie
#define BRAMA_ERR_STORAGE (-6)   // błąd NVS/flash
#define BRAMA_ERR_NO_SPACE (-7)  // brak miejsca/zasobów
