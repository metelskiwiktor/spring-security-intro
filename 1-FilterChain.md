# FilterChain w Spring Boot

## Czym jest FilterChain?

**FilterChain** to mechanizm w aplikacjach webowych (Java Servlet API), który pozwala na przetwarzanie żądań HTTP przez szereg filtrów przed dotarciem do kontrolera.

Filtry są wykonywane **sekwencyjnie** w określonej kolejności, tworząc "łańcuch" (chain). Każdy filtr może:
- Wykonać operacje **przed** przekazaniem żądania dalej
- Przekazać żądanie do następnego filtra/kontrolera wywołując `chain.doFilter()`
- Wykonać operacje **po** przetworzeniu żądania przez resztę łańcucha

## Schemat działania

```
Request → Filter 1 → Filter 2 → Filter N → Controller
            ↓          ↓          ↓            ↓
         (przed)    (przed)    (przed)     (logika)
            ↑          ↑          ↑            ↑
         (po)       (po)       (po)        Response
```

## Implementacja w projekcie

### RequestLoggingFilter

W projekcie został zaimplementowany `RequestLoggingFilter`, który loguje szczegóły każdego przychodzącego żądania HTTP.

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/filter/RequestLoggingFilter.java`

```java
@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            logRequestDetails(httpRequest);  // Operacja PRZED
        }

        chain.doFilter(request, response);  // Przekazanie do następnego filtra/kontrolera

        // Tutaj mogłyby być operacje PO przetworzeniu żądania
    }
}
```

### Kluczowe elementy:

1. **`@Component`** - rejestruje filtr w kontenerze Spring (automatycznie dodawany do FilterChain)

2. **`implements Filter`** - interfejs z pakietu `jakarta.servlet.*`

3. **`doFilter()`** - główna metoda wykonywana dla każdego żądania HTTP
    - `request` - dane przychodzącego żądania
    - `response` - obiekt odpowiedzi (może być modyfikowany)
    - `chain` - obiekt reprezentujący łańcuch filtrów

4. **`chain.doFilter(request, response)`** - **KLUCZOWE!** Przekazuje żądanie dalej. Bez tego wywołania żądanie nigdy nie dotrze do kontrolera.

## Przykład działania

Gdy wysyłasz żądanie:
```
GET http://localhost:8080/api/dummy?param1=test
```

FilterChain działa następująco:

```
1. Żądanie trafia do RequestLoggingFilter
2. RequestLoggingFilter loguje szczegóły:
   === REQUEST DETAILS ===
   Method: GET
   URL: http://localhost:8080/api/dummy
   Query String: param1=test
   Headers: ...
   Parameters: param1: test
   ========================
3. Wywołuje chain.doFilter() → żądanie trafia do kontrolera
4. DummyController przetwarza żądanie i zwraca odpowiedź
5. Odpowiedź wraca przez FilterChain
6. RequestLoggingFilter kończy działanie
```

## Zastosowania FilterChain

- **Logowanie** - jak w tym projekcie (RequestLoggingFilter)
- **Autentykacja/Autoryzacja** - sprawdzanie tokenów JWT, sesji
- **Walidacja** - sprawdzanie poprawności nagłówków, parametrów
- **Modyfikacja żądań/odpowiedzi** - dodawanie nagłówków (np. CORS)
- **Obsługa błędów** - globalna obsługa wyjątków
- **Monitorowanie** - mierzenie czasu wykonania, metryki

## Kolejność filtrów

Spring automatycznie zarządza kolejnością filtrów. Można ją kontrolować za pomocą:
- `@Order(1)` - niższy numer = wcześniejsze wykonanie
- `FilterRegistrationBean` - programatyczna konfiguracja

```java
@Component
@Order(1)
public class FirstFilter implements Filter { ... }

@Component
@Order(2)
public class SecondFilter implements Filter { ... }
```

## Podsumowanie

FilterChain to potężny mechanizm pozwalający na **cross-cutting concerns** - operacje wspólne dla wielu endpointów bez duplikowania kodu w każdym kontrolerze.

**Pamiętaj:** Zawsze wywołuj `chain.doFilter()`, chyba że celowo chcesz przerwać przetwarzanie żądania!