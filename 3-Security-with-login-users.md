# Spring Security - Własni użytkownicy i kontrola dostępu

## Co zostało dodane?

### 1. UserDetailsService Bean

```java
@Bean
public UserDetailsService userDetailsService() {
    UserDetails user = User.builder()
            .username("user")
            .password(passwordEncoder().encode("password"))
            .roles("USER")
            .build();

    UserDetails admin = User.builder()
            .username("admin")
            .password(passwordEncoder().encode("admin"))
            .roles("ADMIN")
            .build();

    return new InMemoryUserDetailsManager(user, admin);
}
```

#### Czym jest UserDetailsService?

**UserDetailsService** to interfejs, który Spring Security używa do **ładowania danych użytkownika** podczas logowania.

Ma jedną metodę:
```java
UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
```

Gdy użytkownik próbuje się zalogować:
1. Spring Security wywołuje `loadUserByUsername("user")`
2. Zwracany jest obiekt `UserDetails` z danymi użytkownika (hasło, role, uprawnienia)
3. Spring Security porównuje hasło z formularza z hasłem z `UserDetails`
4. Jeśli się zgadza → użytkownik zalogowany

#### InMemoryUserDetailsManager

To implementacja `UserDetailsService`, która przechowuje użytkowników **w pamięci** (nie w bazie danych).

Użyteczne do:
- Testów
- Prototypów
- Prostych aplikacji

W produkcji zazwyczaj używa się bazy danych (np. przez `JpaUserDetailsManager` lub własną implementację).

#### User.builder()

Builder pattern do tworzenia użytkowników:
- `.username("user")` - nazwa użytkownika
- `.password(...)` - hasło (zakodowane!)
- `.roles("USER")` - role użytkownika

**WAŻNE:** Role w Spring Security są automatycznie poprzedzone prefiksem `ROLE_`.
- `.roles("USER")` → wewnętrznie przechowywane jako `ROLE_USER`
- `.roles("ADMIN")` → wewnętrznie przechowywane jako `ROLE_ADMIN`

Dlatego w konfiguracji używamy `.hasRole("ADMIN")` bez prefiksu.

### 2. PasswordEncoder Bean

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

#### Czym jest PasswordEncoder?

**PasswordEncoder** to interfejs do **hashowania haseł**.

**Dlaczego nie przechowujemy haseł w plain text?**
- Gdyby ktoś zdobył dostęp do bazy danych, miałby wszystkie hasła
- BCrypt to algorytm hashujący z "solą" (salt) - to samo hasło daje różne hashe

#### Przykład działania BCrypt:

```java
BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
String hash1 = encoder.encode("password"); // $2a$10$abcd1234...
String hash2 = encoder.encode("password"); // $2a$10$efgh5678... (inny hash!)

encoder.matches("password", hash1); // true
encoder.matches("wrong", hash1);    // false
```

#### Bez PasswordEncoder?

Jeśli nie dodasz beana `PasswordEncoder`, Spring Security będzie wymagać prefiksu w haśle:
```java
.password("{noop}password")  // {noop} = no operation (plain text)
.password("{bcrypt}$2a$10...") // {bcrypt} = zakodowane BCryptem
```

Z beanem `PasswordEncoder` Spring automatycznie używa BCrypt i nie wymaga prefiksu.

### 3. Rozbudowana autoryzacja w SecurityFilterChain

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/public").permitAll()
    .requestMatchers("/api/admin").hasRole("ADMIN")
    .requestMatchers("/api/user").hasAnyRole("USER", "ADMIN")
    .anyRequest().authenticated()
)
```

#### requestMatchers() - dopasowywanie ścieżek

Określa, do których endpointów stosuje się dana reguła:
```java
.requestMatchers("/api/public")       // dokładna ścieżka
.requestMatchers("/api/admin/**")     // wildcard (admin i wszystkie podścieżki)
.requestMatchers("/api/*/data")       // * = jeden segment
.requestMatchers(HttpMethod.POST, "/api/data") // tylko POST
```

#### Metody autoryzacji

- **`.permitAll()`** - dostęp dla wszystkich (nawet niezalogowani)
- **`.authenticated()`** - wymaga logowania (dowolna rola)
- **`.hasRole("ADMIN")`** - wymaga konkretnej roli
- **`.hasAnyRole("USER", "ADMIN")`** - wymaga jednej z ról
- **`.hasAuthority("WRITE")`** - wymaga konkretnego uprawnienia (bez automatycznego prefiksu `ROLE_`)
- **`.denyAll()`** - dostęp zabroniony dla wszystkich

#### Kolejność ma znaczenie!

Spring Security sprawdza reguły **od góry do dołu** i stosuje **pierwszą pasującą**.

```java
// ❌ ŹLE - "/api/admin" nigdy nie zadziała
.anyRequest().authenticated()
.requestMatchers("/api/admin").hasRole("ADMIN")

// ✅ DOBRZE - konkretne reguły najpierw
.requestMatchers("/api/admin").hasRole("ADMIN")
.anyRequest().authenticated()
```

### 4. Nowe endpointy do testowania

```java
@GetMapping("/api/public")  // dostęp dla wszystkich
@GetMapping("/api/user")    // USER lub ADMIN
@GetMapping("/api/admin")   // tylko ADMIN
```

## Dodatkowa kontrola dostępu - @PreAuthorize

Oprócz konfiguracji w `SecurityFilterChain`, możemy dodać **adnotacje na metodach kontrolera**.

### Włączenie Method Security

Dodaj do `SecurityConfig`:
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // <- NOWA ADNOTACJA
public class SecurityConfig {
    // ...
}
```

### Użycie @PreAuthorize

```java
@GetMapping("/api/public")
@PreAuthorize("hasRole('ADMIN')")  // <- NADPISUJE SecurityConfig!
public ResponseEntity<?> publicEndpoint() {
    // Teraz wymaga ADMIN mimo .permitAll() w SecurityConfig
}
```

**WAŻNE:** `@PreAuthorize` ma **pierwszeństwo** przed `SecurityFilterChain`!

Jeśli:
- `SecurityConfig` mówi: `.permitAll()`
- Kontroler mówi: `@PreAuthorize("hasRole('ADMIN')")`

**Wygrywa @PreAuthorize** - endpoint wymaga roli ADMIN.

### Inne adnotacje Method Security

```java
@PreAuthorize("hasRole('ADMIN')")          // przed wykonaniem metody
@PostAuthorize("returnObject.owner == authentication.name") // po wykonaniu
@Secured("ROLE_ADMIN")                     // starsza wersja, wymaga pełnego ROLE_
@RolesAllowed("ADMIN")                     // standard JSR-250
```

#### Kiedy używać @PreAuthorize vs SecurityFilterChain?

**SecurityFilterChain (requestMatchers):**
- Globalna konfiguracja w jednym miejscu
- Łatwiejsza do przeglądania
- Dobra dla ścieżek URL

**@PreAuthorize:**
- Bardziej szczegółowa kontrola (np. "tylko właściciel zasobu")
- SpEL (Spring Expression Language) - bardziej złożone warunki
- Kontrola na poziomie metody biznesowej (nie tylko kontrolera)

```java
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
public void deleteUser(Long userId) {
    // Tylko admin lub sam użytkownik może usunąć konto
}
```

## Kroki do wykonania

### 1. Zaloguj się pomyślnie na /api/dummy

**Czynności:**
1. Uruchom aplikację: `mvnw spring-boot:run`
2. Otwórz przeglądarkę: http://localhost:8080/api/dummy
3. Zostaniesz przekierowany do `/login`
4. Zaloguj się:
   - Username: `user`
   - Password: `password`
5. Po zalogowaniu zobaczysz odpowiedź JSON z `/api/dummy`

**Sprawdź w konsoli:**
- `RequestLoggingFilter` powinien zalogować szczegóły żądania
- Przy pierwszym żądaniu: przekierowanie do `/login`
- Przy drugim żądaniu (po zalogowaniu): bezpośrednie przetworzenie + cookie `JSESSIONID`

---

### 2. Zaloguj się bez PasswordEncoder

**Cel:** Zobaczyć, co się stanie bez beana `PasswordEncoder`.

**Czynności:**
1. **Zakomentuj** bean `PasswordEncoder` w `SecurityConfig`:
```java
// @Bean
// public PasswordEncoder passwordEncoder() {
//     return new BCryptPasswordEncoder();
// }
```

2. **Zmień** hasła w `userDetailsService()` na format z prefiksem:
```java
.password("{noop}password")  // zamiast passwordEncoder().encode("password")
```

3. Uruchom aplikację ponownie
4. Zaloguj się: `user` / `password`

**Oczekiwany rezultat:**
- Logowanie działa, ale hasła przechowywane są w plain text (niezabezpieczone!)
- `{noop}` = "no operation" = bez hashowania

**Po teście przywróć PasswordEncoder!**

---

### 3. Przetestuj requestMatchers i różnice

**Cel:** Zrozumieć, jak działają różne poziomy dostępu.

#### Test 1: Endpoint publiczny (bez logowania)

1. **Wyloguj się** (jeśli jesteś zalogowany): http://localhost:8080/logout
2. Otwórz nową kartę **incognito/prywatną**
3. Wejdź na: http://localhost:8080/api/public

**Oczekiwany rezultat:** ✅ Odpowiedź JSON bez logowania

#### Test 2: Endpoint user (wymaga logowania)

1. W tej samej karcie incognito wejdź na: http://localhost:8080/api/user

**Oczekiwany rezultat:** ❌ Przekierowanie do `/login`

2. Zaloguj się jako `user` / `password`
3. Wejdź ponownie na: http://localhost:8080/api/user

**Oczekiwany rezultat:** ✅ Odpowiedź JSON

#### Test 3: Endpoint admin (wymaga roli ADMIN)

1. Będąc zalogowanym jako `user`, wejdź na: http://localhost:8080/api/admin

**Oczekiwany rezultat:** ❌ 403 Forbidden (brak uprawnień)

2. **Wyloguj się**: http://localhost:8080/logout
3. Zaloguj się jako `admin` / `admin`
4. Wejdź na: http://localhost:8080/api/admin

**Oczekiwany rezultat:** ✅ Odpowiedź JSON

#### Test 4: Użytkownik admin ma dostęp do /api/user

1. Będąc zalogowanym jako `admin`, wejdź na: http://localhost:8080/api/user

**Oczekiwany rezultat:** ✅ Odpowiedź JSON (admin ma rolę ADMIN, a `.hasAnyRole("USER", "ADMIN")` pozwala obu)

---

### 4. Dodaj @PreAuthorize i zobacz pierwszeństwo

**Cel:** Pokazać, że `@PreAuthorize` ma pierwszeństwo nad `SecurityConfig`.

#### Krok 1: Włącz Method Security

W `SecurityConfig` dodaj:
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // <- DODAJ
public class SecurityConfig {
```

#### Krok 2: Dodaj @PreAuthorize do /api/public

W `DummyController`:
```java
@GetMapping("/api/public")
@PreAuthorize("hasRole('ADMIN')")  // <- DODAJ
public ResponseEntity<Map<String, Object>> publicEndpoint() {
```

#### Krok 3: Przetestuj

1. **Wyloguj się** i otwórz kartę incognito
2. Wejdź na: http://localhost:8080/api/public

**Oczekiwany rezultat:** ❌ Przekierowanie do `/login` (mimo `.permitAll()` w SecurityConfig!)

3. Zaloguj się jako `user` / `password`
4. Wejdź na: http://localhost:8080/api/public

**Oczekiwany rezultat:** ❌ 403 Forbidden (user nie ma roli ADMIN)

5. Wyloguj się, zaloguj jako `admin` / `admin`
6. Wejdź na: http://localhost:8080/api/public

**Oczekiwany rezultat:** ✅ Odpowiedź JSON (admin ma rolę ADMIN)

**Wniosek:** `@PreAuthorize` **nadpisuje** konfigurację z `SecurityFilterChain`!

---

## Podsumowanie

### Co dodaliśmy?

1. **UserDetailsService** - własni użytkownicy (user + admin) w pamięci
2. **PasswordEncoder** - bezpieczne hashowanie haseł (BCrypt)
3. **Rozbudowana autoryzacja** - różne poziomy dostępu (public, user, admin)
4. **@PreAuthorize** - kontrola dostępu na poziomie metod (pierwszeństwo nad SecurityConfig)

### Kluczowe różnice

| Aspekt | SecurityFilterChain | @PreAuthorize |
|--------|---------------------|---------------|
| Poziom | URL patterns | Metody (kontrolery, serwisy) |
| Konfiguracja | Scentralizowana | Rozproszona |
| Pierwszeństwo | Niższe | Wyższe |
| Wyrażenia | Podstawowe | SpEL (bardziej złożone) |
| Use case | Ogólne reguły URL | Szczegółowa logika biznesowa |

### Kolejne kroki

W następnym rozdziale:
- Zastąpienie `InMemoryUserDetailsManager` bazą danych (JPA)
- Tworzenie własnych `UserDetails` i `UserDetailsService`
- Rejestracja użytkowników
- Zarządzanie sesjami i tokenami
