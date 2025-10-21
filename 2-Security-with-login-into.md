# Spring Security - Podstawowa konfiguracja z logowaniem

## Dodanie modułu Spring Security

### Zależność w pom.xml

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Po dodaniu tej zależności Spring Security **automatycznie zabezpiecza całą aplikację**. Wszystkie endpointy wymagają autoryzacji.

## Co się dzieje po dodaniu Spring Security (bez konfiguracji)?

Gdy dodasz `spring-boot-starter-security` i uruchomisz aplikację **BEZ własnej konfiguracji**, Spring Security:

1. **Generuje domyślnego użytkownika** z:
   - Username: `user`
   - Losowe hasło generowane przy starcie (wyświetlane w konsoli)

```
Using generated security password: f8d12a4c-5e67-4b3a-9f2e-1a2b3c4d5e6f
```

2. **Zabezpiecza wszystkie endpointy** - każde żądanie wymaga logowania

3. **Włącza formularz logowania** - automatycznie dostępny pod `/login`

4. **Dodaje mechanizmy bezpieczeństwa**:
   - CSRF protection (token zaszyty w formularzu)
   - Session fixation protection (regenerowanie id sesji po zalogowaniu, sprawdź cookies przed zalogowaniem)
   - Security headers (X-Frame-Options, X-Content-Type-Options, itp.) (chroni przed atakami XSS, osadzaniem itp.)
   - Logout endpoint (`/logout`)

## Nasza konfiguracja - SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .permitAll()
                );

        return http.build();
    }
}
```

### Rozbicie kodu

#### `@Configuration` + `@EnableWebSecurity`
- `@Configuration` - klasa zawiera beany Spring
- `@EnableWebSecurity` - włącza Spring Security WebSecurityConfiguration

#### `SecurityFilterChain` Bean

**SecurityFilterChain** to konfiguracja **łańcucha filtrów bezpieczeństwa**.

Spring Security działa na bazie **FilterChain** (podobnie jak nasz `RequestLoggingFilter`), ale ma wiele wbudowanych filtrów:
- `UsernamePasswordAuthenticationFilter` - obsługuje logowanie formularzem
- `CsrfFilter` - chroni przed atakami CSRF
- `SessionManagementFilter` - zarządza sesjami
- `AuthorizationFilter` - sprawdza uprawnienia
- ...i wiele innych

`SecurityFilterChain` konfiguruje te filtry i określa, jak mają działać.

#### `.authorizeHttpRequests()`

```java
.authorizeHttpRequests(auth -> auth
    .anyRequest().authenticated()
)
```

Określa **reguły dostępu** do endpointów:
- `.anyRequest()` - każde żądanie HTTP
- `.authenticated()` - wymaga autentykacji (zalogowanego użytkownika)

Można to rozbudować, np.:
```java
.requestMatchers("/public/**").permitAll()  // dostęp bez logowania
.requestMatchers("/admin/**").hasRole("ADMIN")  // tylko dla adminów
.anyRequest().authenticated()  // reszta wymaga logowania
```

> Ważne! Kolejnosć reguł ma znaczenie! Najpierw twórz bardziej szczegółowe wzorce, na końcu regułę domyślną.

#### `.formLogin()`

```java
.formLogin(form -> form
    .permitAll()
)
```

Włącza **autentykację przez formularz logowania**:
- Spring dostarcza domyślny formularz pod `/login`
- `.permitAll()` - formularz logowania dostępny dla wszystkich (nie wymaga autoryzacji)

Można dostosować:
```java
.formLogin(form -> form
    .loginPage("/moj-login")           // własna strona logowania
    .loginProcessingUrl("/authenticate")  // endpoint do przetwarzania logowania
    .defaultSuccessUrl("/home")           // przekierowanie po zalogowaniu
    .permitAll()
)
```

## Co się dzieje pod spodem?

### 1. Uruchomienie aplikacji
Spring Security:
- Ładuje `SecurityFilterChain` z naszej konfiguracji
- Buduje łańcuch filtrów bezpieczeństwa
- Rejestruje je w ServletContext (przed naszym `RequestLoggingFilter`)

### 2. Żądanie HTTP do `/api/dummy`

```
Request
  ↓
SecurityFilterChain (filtry Spring Security):
  ↓
1. SessionManagementFilter - sprawdza sesję
  ↓
2. AuthorizationFilter - sprawdza, czy użytkownik zalogowany
  ↓
❌ NIE - użytkownik niezalogowany
  ↓
Przekierowanie do /login (formularz logowania)
```

### 3. Wysłanie formularza logowania

```
POST /login
  username=user
  password=<wpisane hasło>
  ↓
UsernamePasswordAuthenticationFilter
  ↓
Próba autentykacji:
  - Pobiera username i password z formularza
  - Szuka użytkownika (tutaj: domyślny "user" z losowym hasłem)
  - Sprawdza hasło
  ↓
✅ Sukces
  ↓
Tworzy sesję (JSESSIONID)
Przekierowuje z powrotem do /api/dummy
```

### 4. Ponowne żądanie do `/api/dummy` (z sesją)

```
Request + Cookie: JSESSIONID=...
  ↓
SecurityFilterChain:
  ↓
SessionManagementFilter - odczytuje sesję, ładuje użytkownika
  ↓
AuthorizationFilter - sprawdza, czy użytkownik zalogowany
  ↓
✅ TAK - użytkownik zalogowany
  ↓
RequestLoggingFilter (nasz filtr)
  ↓
DummyController - przetwarza żądanie
  ↓
Response
```

## Co dalej?

Obecnie używamy **domyślnego użytkownika z losowym hasłem**. To działa, ale jest niepraktyczne.

W następnym kroku dodamy **własnych użytkowników** za pomocą:
- `UserDetailsService` - interfejs do ładowania użytkowników
- `PasswordEncoder` - kodowanie haseł (np. BCrypt)
- `InMemoryUserDetailsManager` - przechowywanie użytkowników w pamięci (póżniej: baza danych)

Te beany rozbudują naszą konfigurację, pozwalając na pełną kontrolę nad użytkownikami i ich rolami.

## Testowanie

1. Uruchom aplikację
2. W konsoli znajdź hasło: `Using generated security password: ...`
3. Otwórz http://localhost:8080/api/dummy
4. Zostaniesz przekierowany do formularza logowania
5. Zaloguj się: `user` / `<hasło z konsoli>`
6. Po zalogowaniu zobaczysz odpowiedź z API

## Podsumowanie

- **Spring Security = FilterChain** na sterydach
- Domyślnie zabezpiecza całą aplikację
- `SecurityFilterChain` konfiguruje reguły dostępu
- `.formLogin()` włącza logowanie formularzem
- Bez własnej konfiguracji użytkowników → generowany domyślny user z losowym hasłem
- Autentykacja przechowywana w sesji (JSESSIONID)
