# Spring Security - JWT Authentication (Stateless)

## Przejście z Session-Based na Token-Based Authentication

W poprzednich rozdziałach używaliśmy **session-based authentication**:
- Po zalogowaniu Spring Security tworzy sesję (JSESSIONID)
- Cookie JSESSIONID wysyłane z każdym żądaniem
- Serwer przechowuje sesje w pamięci/bazie danych
- Problem: trudne do skalowania (sticky sessions, Redis dla sesji)

**JWT (JSON Web Token)** to **stateless authentication**:
- Po zalogowaniu serwer generuje token JWT
- Klient wysyła token w nagłówku `Authorization: Bearer <token>`
- Serwer **nie przechowuje** tokenów - wszystkie dane w tokenie
- Idealny dla REST API, mikroserwisów, mobile apps

## Co zostało dodane?

### 1. Zależność JJWT

W `pom.xml`:
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

**JJWT** to biblioteka do tworzenia i parsowania tokenów JWT w Javie.

### 2. Konfiguracja JWT w application.properties

```properties
jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
jwt.expiration=86400000
```

- **jwt.secret** - klucz do podpisywania tokenów (Base64)
- **jwt.expiration** - czas ważności tokena w milisekundach (86400000ms = 24h)

**WAŻNE:** W produkcji używaj zmiennych środowiskowych (`${JWT_SECRET}`), nie hardcoduj w properties!

### 3. DTOs dla API

#### LoginRequest.java
```java
public record LoginRequest(String username, String password) {}
```

Dane wejściowe do endpointa `/api/auth/login`.

#### AuthResponse.java
```java
public record AuthResponse(String token) {}
```

Odpowiedź z tokenem JWT po pomyślnym zalogowaniu.

### 4. JwtService - generowanie i walidacja tokenów

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/service/JwtService.java`

#### Kluczowe metody:

##### `generateToken(UserDetails userDetails)`
```java
public String generateToken(UserDetails userDetails) {
    return Jwts.builder()
        .subject(userDetails.getUsername())      // "sub": "user"
        .issuedAt(new Date())                    // "iat": timestamp
        .expiration(new Date(now + expiration))  // "exp": timestamp
        .signWith(getSignInKey())                // podpisanie kluczem
        .compact();                              // zwrot stringa
}
```

Generuje token JWT zawierający:
- **subject** - nazwa użytkownika (w tokenie jako `sub`)
- **issuedAt** - data wygenerowania (`iat`)
- **expiration** - data wygaśnięcia (`exp`)
- **signature** - podpis cyfrowy (weryfikacja autentyczności)

**Przykładowy token JWT:**
```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwODY0MDB9.signature
     ^header^           ^payload (Base64)^                                           ^signature^
```

Token JWT składa się z trzech części oddzielonych kropkami:
1. **Header** - typ tokena i algorytm (`{"alg":"HS256","typ":"JWT"}`)
2. **Payload** - dane (claims): `{"sub":"user","iat":1700000000,"exp":1700086400}`
3. **Signature** - podpis cyfrowy: `HMACSHA256(base64(header) + "." + base64(payload), secret)`

##### `extractUsername(String token)`
```java
public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
}
```

Wyciąga nazwę użytkownika z tokena (z pola `sub`).

##### `isTokenValid(String token, UserDetails userDetails)`
```java
public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
}
```

Waliduje token:
- Czy username w tokenie zgadza się z UserDetails
- Czy token nie wygasł (sprawdza `exp`)

##### `getSignInKey()`
```java
private SecretKey getSignInKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(keyBytes);
}
```

Tworzy klucz kryptograficzny z Base64 stringa z `application.properties`.

### 5. AuthController - endpoint do logowania

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/controller/AuthController.java`

```java
@PostMapping("/api/auth/login")
public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest req) {
    // 1. Uwierzytelnienie użytkownika
    Authentication auth = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(req.username(), req.password())
    );

    // 2. Pobranie UserDetails
    UserDetails principal = (UserDetails) auth.getPrincipal();

    // 3. Generowanie tokena JWT
    String token = jwtService.generateToken(principal);

    // 4. Zwrot tokena
    return ResponseEntity.ok(new AuthResponse(token));
}
```

#### Szczegółowy flow:

##### Krok 1: `authenticationManager.authenticate()`

**AuthenticationManager** to kluczowy bean w Spring Security odpowiedzialny za uwierzytelnianie.

```java
Authentication auth = authenticationManager.authenticate(
    new UsernamePasswordAuthenticationToken(req.username(), req.password())
);
```

Co się dzieje:
1. **UsernamePasswordAuthenticationToken** - obiekt reprezentujący nieuwierzytelnione żądanie logowania
2. AuthenticationManager przekazuje token do **AuthenticationProvider** (w naszym przypadku `DaoAuthenticationProvider`)
3. DaoAuthenticationProvider:
   - Wywołuje `UserDetailsService.loadUserByUsername(username)` - pobiera użytkownika z pamięci/bazy
   - Porównuje hasło z formularza z hasłem użytkownika używając `PasswordEncoder.matches()`
   - Jeśli hasło pasuje → tworzy **uwierzytelniony** Authentication obiekt
   - Jeśli nie → rzuca `BadCredentialsException`

##### Krok 2: `auth.getPrincipal()`

```java
UserDetails principal = (UserDetails) auth.getPrincipal();
```

**Principal** to obiekt reprezentujący zalogowanego użytkownika. W naszym przypadku to `UserDetails` z nazwą użytkownika i rolami.

##### Krok 3: Generowanie tokena

```java
String token = jwtService.generateToken(principal);
```

`JwtService` tworzy token JWT zawierający username jako `subject`.

##### Krok 4: Zwrot tokena

```java
return ResponseEntity.ok(new AuthResponse(token));
```

JSON: `{"token":"eyJhbGciOiJIUzI1NiJ9..."}`

**Beany używane przez AuthController:**
- **AuthenticationManager** (z beana w SecurityConfig)
- **JwtService** (autowired)

**Beany używane wewnętrznie przez AuthenticationManager:**
- **AuthenticationProvider** (tworzony automatycznie przez Spring Security)
- **UserDetailsService** (nasz bean z SecurityConfig)
- **PasswordEncoder** (nasz bean z SecurityConfig)

### 6. JwtAuthenticationFilter - walidacja tokena przy każdym żądaniu

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/filter/JwtAuthenticationFilter.java`

To **najważniejsza** część JWT authentication! Ten filtr wykonuje się **przed** wszystkimi innymi filtrami Spring Security.

#### extends OncePerRequestFilter

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter
```

**OncePerRequestFilter** to klasa bazowa Spring gwarantująca, że filtr wykona się **dokładnie raz** na żądanie HTTP (nie wielokrotnie przy forward/include).

#### Logika filtra - krok po kroku:

```java
@Override
protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
) throws ServletException, IOException {
```

##### Krok 1: Pobranie nagłówka Authorization

```java
final String authHeader = request.getHeader("Authorization");

if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    filterChain.doFilter(request, response);
    return;
}
```

Sprawdza, czy żądanie zawiera nagłówek:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

Jeśli nie → **przepuszcza żądanie dalej bez uwierzytelnienia**.
Dlaczego? Bo niektóre endpointy mogą być publiczne (`/api/public`, `/api/auth/login`).

##### Krok 2: Ekstrakcja tokena i username

```java
jwt = authHeader.substring(7);  // usuwa "Bearer "
username = jwtService.extractUsername(jwt);
```

Wyciąga token JWT z nagłówka i parsuje go, aby uzyskać username (`sub` claim).

##### Krok 3: Sprawdzenie, czy użytkownik już nie jest uwierzytelniony

```java
if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
```

**SecurityContextHolder** to ThreadLocal przechowujący kontekst bezpieczeństwa dla bieżącego żądania.

- `SecurityContextHolder.getContext()` - zwraca SecurityContext dla wątku
- `.getAuthentication()` - zwraca obiekt Authentication (jeśli użytkownik uwierzytelniony)

Jeśli `getAuthentication() == null` → użytkownik **nie jest jeszcze uwierzytelniony** w tym żądaniu.

##### Krok 4: Ładowanie UserDetails

```java
UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
```

Ładuje dane użytkownika z `UserDetailsService` (nasz InMemoryUserDetailsManager).

**Dlaczego ponownie ładujemy użytkownika?**
- Token może być sfałszowany
- Użytkownik mógł zostać usunięty/zablokowany
- Role mogły się zmienić

##### Krok 5: Walidacja tokena

```java
if (jwtService.isTokenValid(jwt, userDetails)) {
```

Sprawdza:
- Czy username w tokenie zgadza się z UserDetails
- Czy token nie wygasł (`exp` claim)
- Czy podpis jest prawidłowy (weryfikacja za pomocą klucza)

##### Krok 6: Tworzenie Authentication i zapisanie w SecurityContext

```java
UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
    userDetails,              // principal
    null,                     // credentials (nie potrzebne po uwierzytelnieniu)
    userDetails.getAuthorities()  // role/uprawnienia
);
authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
SecurityContextHolder.getContext().setAuthentication(authToken);
```

**UsernamePasswordAuthenticationToken** to implementacja `Authentication`.

Tworzymy **uwierzytelniony** obiekt Authentication zawierający:
- **principal** - UserDetails z danymi użytkownika
- **credentials** - null (hasło nie jest potrzebne po uwierzytelnieniu)
- **authorities** - role użytkownika (np. `ROLE_USER`, `ROLE_ADMIN`)

`setDetails()` - dodaje informacje o żądaniu (IP, session ID).

**Zapisanie w SecurityContext:**
```java
SecurityContextHolder.getContext().setAuthentication(authToken);
```

Od tego momentu użytkownik jest **uwierzytelniony** dla reszty żądania HTTP.

Wszystkie kolejne filtry Spring Security (np. `AuthorizationFilter`) zobaczą, że użytkownik jest zalogowany i będą mogły sprawdzić jego role.

##### Krok 7: Przepuszczenie żądania dalej

```java
filterChain.doFilter(request, response);
```

Przekazuje żądanie do następnych filtrów i kontrolera.

#### Schemat działania JwtAuthenticationFilter:

```
Request + Authorization: Bearer <token>
  ↓
JwtAuthenticationFilter.doFilterInternal()
  ↓
1. Sprawdź, czy nagłówek Authorization istnieje
  ↓
2. Wyciągnij token JWT (usuń "Bearer ")
  ↓
3. Sparsuj token i wyciągnij username (jwtService.extractUsername)
  ↓
4. Sprawdź, czy użytkownik nie jest już uwierzytelniony (SecurityContextHolder)
  ↓
5. Załaduj UserDetails (userDetailsService.loadUserByUsername)
  ↓
6. Zwaliduj token (jwtService.isTokenValid):
   - Czy username się zgadza?
   - Czy token nie wygasł?
   - Czy podpis jest prawidłowy?
  ↓
7. Utwórz Authentication obiekt (UsernamePasswordAuthenticationToken)
  ↓
8. Zapisz Authentication w SecurityContext
  ↓
9. Przepuść żądanie dalej (filterChain.doFilter)
  ↓
Kolejne filtry Spring Security (np. AuthorizationFilter)
  ↓
DummyController
  ↓
Response
```

**Beany używane przez JwtAuthenticationFilter:**
- **JwtService** (autowired)
- **UserDetailsService** (autowired)

### 7. Zmiany w SecurityConfig

#### Bean AuthenticationManager

```java
@Bean
public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
}
```

**AuthenticationConfiguration** to klasa Spring Security, która automatycznie konfiguruje `AuthenticationManager` na podstawie dostępnych beanów:
- `UserDetailsService` (nasz InMemoryUserDetailsManager)
- `PasswordEncoder` (nasz BCryptPasswordEncoder)

Zwraca **globalny, skonfigurowany** AuthenticationManager, którego używamy w `AuthController`.

**Alternatywa (manualna konfiguracja):**
```java
@Bean
public AuthenticationManager authenticationManager() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService());
    provider.setPasswordEncoder(passwordEncoder());
    return new ProviderManager(provider);
}
```

Ale `AuthenticationConfiguration` robi to za nas automatycznie!

#### Zmiany w SecurityFilterChain

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())  // <-- NOWE: wyłączenie CSRF
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/public", "/api/auth/**").permitAll()  // <-- ZMIANA
            .requestMatchers("/api/admin").hasRole("ADMIN")
            .requestMatchers("/api/user").hasAnyRole("USER", "ADMIN")
            .anyRequest().authenticated()
        )
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS)  // <-- NOWE: stateless
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);  // <-- NOWE: dodanie filtra

    return http.build();
}
```

##### `.csrf(csrf -> csrf.disable())`

**CSRF (Cross-Site Request Forgery)** to atak polegający na wykonaniu nieautoryzowanej akcji w imieniu zalogowanego użytkownika.

**Dlaczego wyłączamy CSRF?**
- CSRF dotyczy **session-based authentication** (cookies JSESSIONID)
- JWT przechowywane jest w **nagłówku Authorization**, nie w cookie
- JavaScript musi **ręcznie dodać** nagłówek → brak automatycznego wysyłania (jak w przypadku cookies)
- REST API nie używa cookies → CSRF nie ma zastosowania

**WAŻNE:** Jeśli używasz JWT w **cookie** (nie w nagłówku), **NIE WYŁĄCZAJ CSRF**!

##### `.requestMatchers("/api/auth/**").permitAll()`

Przepuszcza ruch do endpointu logowania (`/api/auth/login`) bez uwierzytelnienia.

Bez tego nie można by się zalogować (chicken-egg problem).

##### `.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`

**SessionCreationPolicy.STATELESS** - Spring Security **nie tworzy sesji HTTP** i **nie używa JSESSIONID**.

**Dlaczego?**
- JWT to **stateless authentication** - serwer nie przechowuje stanu sesji
- Wszystkie dane w tokenie JWT
- Łatwiejsze skalowanie (brak sticky sessions, brak replikacji sesji)

**Inne opcje:**
- `ALWAYS` - zawsze tworzy sesję
- `IF_REQUIRED` - tworzy sesję gdy potrzebna (domyślne)
- `NEVER` - nie tworzy sesji, ale używa istniejącej
- `STATELESS` - całkowicie bez sesji

##### `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`

Dodaje nasz `JwtAuthenticationFilter` **przed** wbudowanym `UsernamePasswordAuthenticationFilter`.

**Kolejność filtrów Spring Security:**

```
SecurityFilterChain:
  1. JwtAuthenticationFilter (nasz filtr) <-- TUTAJ DODAJEMY
  2. UsernamePasswordAuthenticationFilter (wbudowany, obsługuje /login)
  3. AuthorizationFilter (sprawdza uprawnienia)
  4. ... inne filtry
  5. DispatcherServlet (Spring MVC)
  6. DummyController
```

**Dlaczego przed UsernamePasswordAuthenticationFilter?**
- `UsernamePasswordAuthenticationFilter` obsługuje logowanie formularzem (POST do `/login`)
- My już nie używamy formularza, używamy JWT
- Nasz filtr musi być **wcześniej**, żeby ustawić Authentication w SecurityContext **zanim** inne filtry sprawdzą uprawnienia

## Co się dzieje pod spodem - pełny flow

### Flow 1: Logowanie (`POST /api/auth/login`)

```
1. Request:
   POST /api/auth/login
   Content-Type: application/json
   Body: {"username":"user","password":"password"}

2. JwtAuthenticationFilter.doFilterInternal():
   - Brak nagłówka Authorization → przepuszcza dalej
   - SecurityContext.authentication = null

3. AuthorizationFilter:
   - Sprawdza, czy /api/auth/login jest dostępne bez logowania
   - .requestMatchers("/api/auth/**").permitAll() → TAK
   - Przepuszcza dalej

4. AuthController.login():
   ┌─────────────────────────────────────────────────────────┐
   │ authenticationManager.authenticate(...)                 │
   │  ↓                                                       │
   │ AuthenticationProvider (DaoAuthenticationProvider):     │
   │  ↓                                                       │
   │ userDetailsService.loadUserByUsername("user")           │
   │  ↓                                                       │
   │ InMemoryUserDetailsManager zwraca UserDetails          │
   │  ↓                                                       │
   │ passwordEncoder.matches("password", "$2a$10...")        │
   │  ↓                                                       │
   │ ✅ Hasło prawidłowe → zwraca Authentication            │
   └─────────────────────────────────────────────────────────┘

   UserDetails principal = auth.getPrincipal();
   String token = jwtService.generateToken(principal);

   ┌─────────────────────────────────────────────────────────┐
   │ jwtService.generateToken(userDetails)                   │
   │  ↓                                                       │
   │ Jwts.builder()                                          │
   │   .subject("user")                                      │
   │   .issuedAt(now)                                        │
   │   .expiration(now + 24h)                                │
   │   .signWith(secretKey)                                  │
   │   .compact()                                            │
   │  ↓                                                       │
   │ Token: eyJhbGciOiJIUzI1NiJ9.eyJzdWI6InVzZXIi...        │
   └─────────────────────────────────────────────────────────┘

5. Response:
   HTTP 200 OK
   {"token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWI6InVzZXIi..."}
```

**Beany utworzone automatycznie przez Spring:**
- **AuthenticationProvider** (DaoAuthenticationProvider) - tworzy się automatycznie gdy istnieje UserDetailsService i PasswordEncoder

### Flow 2: Chroniony endpoint z tokenem JWT (`GET /api/user`)

```
1. Request:
   GET /api/user
   Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWI6InVzZXIi...

2. JwtAuthenticationFilter.doFilterInternal():
   ┌─────────────────────────────────────────────────────────┐
   │ 1. Pobranie nagłówka: "Bearer eyJhbGci..."             │
   │ 2. Ekstrakcja tokena: authHeader.substring(7)          │
   │ 3. Parsowanie tokena:                                   │
   │    username = jwtService.extractUsername(jwt)          │
   │    ↓                                                    │
   │    Jwts.parser()                                        │
   │      .verifyWith(secretKey)                            │
   │      .build()                                           │
   │      .parseSignedClaims(token)                         │
   │      .getPayload()                                      │
   │      .getSubject() → "user"                            │
   │                                                         │
   │ 4. SecurityContextHolder.getContext().getAuthentication() │
   │    → null (jeszcze nie uwierzytelniony)                │
   │                                                         │
   │ 5. Ładowanie użytkownika:                              │
   │    userDetails = userDetailsService.loadUserByUsername("user") │
   │    ↓                                                    │
   │    InMemoryUserDetailsManager zwraca UserDetails       │
   │                                                         │
   │ 6. Walidacja tokena:                                    │
   │    jwtService.isTokenValid(jwt, userDetails)           │
   │    - Czy username == userDetails.username? ✅          │
   │    - Czy token nie wygasł? ✅                          │
   │    - Czy podpis prawidłowy? ✅                         │
   │                                                         │
   │ 7. Tworzenie Authentication:                            │
   │    authToken = new UsernamePasswordAuthenticationToken( │
   │      userDetails,                                       │
   │      null,                                              │
   │      [ROLE_USER]                                        │
   │    )                                                    │
   │                                                         │
   │ 8. Zapisanie w SecurityContext:                        │
   │    SecurityContextHolder.getContext().setAuthentication(authToken) │
   └─────────────────────────────────────────────────────────┘

3. AuthorizationFilter:
   - SecurityContext.authentication != null → użytkownik zalogowany
   - Sprawdza reguły: .requestMatchers("/api/user").hasAnyRole("USER", "ADMIN")
   - Użytkownik ma ROLE_USER → ✅ dostęp dozwolony

4. DummyController.userEndpoint():
   - Wykonuje logikę biznesową
   - Zwraca odpowiedź

5. Response:
   HTTP 200 OK
   {"message":"User endpoint","user":"user"}
```

### Flow 3: Nieudane uwierzytelnienie (brak tokena)

```
1. Request:
   GET /api/user
   (brak nagłówka Authorization)

2. JwtAuthenticationFilter.doFilterInternal():
   - authHeader == null → filterChain.doFilter() → przepuszcza dalej
   - SecurityContext.authentication = null

3. AuthorizationFilter:
   - SecurityContext.authentication == null → użytkownik NIE zalogowany
   - Endpoint wymaga uwierzytelnienia (.authenticated())
   - ❌ Dostęp zabroniony

4. AuthenticationEntryPoint (domyślny):
   - Zwraca 403 Forbidden lub 401 Unauthorized

5. Response:
   HTTP 403 Forbidden
```

### Flow 4: Wygasły token

```
1. Request:
   GET /api/user
   Authorization: Bearer <wygasły-token>

2. JwtAuthenticationFilter.doFilterInternal():
   - Parsowanie tokena → ✅ sukces
   - username = "user"
   - Ładowanie UserDetails → ✅ sukces
   - Walidacja tokena:
     - Czy username == userDetails.username? ✅
     - Czy token nie wygasł? ❌ (extractExpiration().before(now) == true)
   - isTokenValid() zwraca false
   - Authentication NIE zostaje ustawione w SecurityContext
   - filterChain.doFilter() → przepuszcza dalej

3. AuthorizationFilter:
   - SecurityContext.authentication == null
   - ❌ Dostęp zabroniony

4. Response:
   HTTP 403 Forbidden
```

## Podsumowanie kluczowych beanów

### Beany, które tworzymy ręcznie w SecurityConfig:

1. **SecurityFilterChain** - konfiguracja Spring Security (reguły dostępu, filtry)
2. **UserDetailsService** - ładowanie użytkowników (InMemoryUserDetailsManager)
3. **PasswordEncoder** - hashowanie haseł (BCryptPasswordEncoder)
4. **AuthenticationManager** - uwierzytelnianie (z AuthenticationConfiguration)

### Beany tworzone automatycznie przez Spring Security:

1. **AuthenticationProvider** (DaoAuthenticationProvider) - używa UserDetailsService + PasswordEncoder
2. **AuthorizationFilter** - sprawdza uprawnienia
3. **AuthenticationEntryPoint** - obsługuje błędy autoryzacji (401/403)

### Beany, które tworzymy jako @Component/@Service:

1. **JwtService** (@Service) - generowanie i walidacja JWT
2. **JwtAuthenticationFilter** (@Component) - filtr walidujący JWT przy każdym żądaniu
3. **AuthController** (@RestController) - endpoint do logowania

## Testowanie JWT Authentication

### 1. Logowanie i otrzymanie tokena

**cURL:**
```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}'
```

**Odpowiedź:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwODY0MDB9.signature"
}
```

**Postman:**
1. POST `http://localhost:8081/api/auth/login`
2. Body → raw → JSON:
   ```json
   {"username":"user","password":"password"}
   ```
3. Send
4. Skopiuj token z odpowiedzi

### 2. Użycie tokena do dostępu do chronionego endpointa

**cURL:**
```bash
curl http://localhost:8081/api/user \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIi..."
```

**Postman:**
1. GET `http://localhost:8081/api/user`
2. Headers → dodaj:
   ```
   Key: Authorization
   Value: Bearer <twój-token>
   ```
3. Send

**Oczekiwany rezultat:** ✅ 200 OK z odpowiedzią JSON

### 3. Próba dostępu bez tokena

**cURL:**
```bash
curl http://localhost:8081/api/user
```

**Oczekiwany rezultat:** ❌ 403 Forbidden

### 4. Test wygaśnięcia tokena

1. Zmień `jwt.expiration` w `application.properties` na małą wartość:
   ```properties
   jwt.expiration=5000  # 5 sekund
   ```
2. Uruchom aplikację ponownie
3. Zaloguj się i otrzymaj token
4. Użyj tokena od razu → ✅ działa
5. Poczekaj 6 sekund
6. Użyj tego samego tokena → ❌ 403 Forbidden (token wygasł)

### 5. Test dostępu admina

**Logowanie jako admin:**
```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
```

**Dostęp do /api/admin:**
```bash
curl http://localhost:8081/api/admin \
  -H "Authorization: Bearer <admin-token>"
```

**Oczekiwany rezultat:** ✅ 200 OK

**Próba dostępu jako user:**
```bash
curl http://localhost:8081/api/admin \
  -H "Authorization: Bearer <user-token>"
```

**Oczekiwany rezultat:** ❌ 403 Forbidden (brak roli ADMIN)

## Różnice: Session-Based vs JWT

| Aspekt | Session-Based | JWT (Token-Based) |
|--------|---------------|-------------------|
| **Przechowywanie stanu** | Serwer (pamięć/baza/Redis) | Klient (token zawiera wszystko) |
| **Identyfikator** | Cookie: JSESSIONID | Nagłówek: Authorization: Bearer <token> |
| **Skalowanie** | Trudniejsze (sticky sessions/Redis) | Łatwiejsze (stateless) |
| **Wydajność** | Szybsze (lookup w pamięci) | Wolniejsze (parsowanie + weryfikacja podpisu) |
| **Bezpieczeństwo** | CSRF protection potrzebne | CSRF nie dotyczy (nagłówek, nie cookie) |
| **Revocation (unieważnienie)** | Łatwe (usuń sesję) | Trudniejsze (token ważny do wygaśnięcia*) |
| **Użycie** | Tradycyjne web apps | REST API, mobile, SPA, mikroserwisy |

*Revocation JWT wymaga dodatkowej logiki (blacklist tokenów, krótszy czas życia + refresh token)

## Kolejne kroki

W następnych rozdziałach:
- **Refresh Tokens** - automatyczne odnawianie tokenów bez ponownego logowania
- **Token Revocation** - blacklisting tokenów (wylogowanie, zmiana hasła)
- **Baza danych** - przejście z InMemoryUserDetailsManager na JPA
- **Rejestracja użytkowników** - endpoint do tworzenia kont
- **Role i uprawnienia** - bardziej szczegółowa kontrola dostępu
- **OAuth2/OIDC** - logowanie przez Google, GitHub, itp.

## Podsumowanie kluczowych konceptów

### SecurityContextHolder
**ThreadLocal** przechowujący kontekst bezpieczeństwa dla bieżącego żądania HTTP.

```java
// Pobieranie zalogowanego użytkownika w kontrolerze:
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();
Collection<? extends GrantedAuthority> roles = auth.getAuthorities();
```

### Authentication
Obiekt reprezentujący uwierzytelnionego użytkownika.

```java
public interface Authentication {
    Object getPrincipal();        // UserDetails
    Object getCredentials();      // hasło (null po uwierzytelnieniu)
    Collection<? extends GrantedAuthority> getAuthorities();  // role
    boolean isAuthenticated();
}
```

### AuthenticationManager
Odpowiedzialny za uwierzytelnianie (weryfikacja username + password).

```java
// W kontrolerze:
Authentication auth = authenticationManager.authenticate(
    new UsernamePasswordAuthenticationToken(username, password)
);
```

### UserDetailsService
Interfejs do ładowania danych użytkownika (z bazy/pamięci).

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

### OncePerRequestFilter
Klasa bazowa dla filtrów wykonywanych **raz** na żądanie HTTP.

```java
@Override
protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
) {
    // Logika filtra
    filterChain.doFilter(request, response);  // ZAWSZE wywołaj!
}
```

### JWT Claims
Dane przechowywane w tokenie JWT.

**Standardowe claims:**
- `sub` (subject) - nazwa użytkownika
- `iat` (issued at) - data wygenerowania
- `exp` (expiration) - data wygaśnięcia
- `iss` (issuer) - wystawca tokena
- `aud` (audience) - odbiorca tokena

**Custom claims:**
```java
Map<String, Object> extraClaims = new HashMap<>();
extraClaims.put("userId", 123);
extraClaims.put("email", "user@example.com");

String token = jwtService.generateToken(extraClaims, userDetails);
```

### CSRF (Cross-Site Request Forgery)
Atak polegający na wykonaniu akcji w imieniu zalogowanego użytkownika.

**Dlaczego JWT nie wymaga CSRF protection?**
- Atakujący nie może odczytać tokena JWT (same-origin policy)
- Atakujący nie może dodać nagłówka Authorization (CORS policy)
- Cookie JSESSIONID jest wysyłane **automatycznie** → podatne na CSRF
- Nagłówek Authorization musi być dodany **ręcznie przez JS** → bezpieczne

## Najczęstsze błędy i rozwiązania

### 1. 403 Forbidden mimo prawidłowego tokena

**Przyczyna:** Token nie zawiera ról lub role są nieprawidłowe.

**Rozwiązanie:** Sprawdź, czy UserDetails zawiera role:
```java
// W JwtAuthenticationFilter:
System.out.println("Authorities: " + userDetails.getAuthorities());
```

### 2. Token nie jest walidowany (zawsze 403)

**Przyczyna:** JwtAuthenticationFilter nie jest zarejestrowany lub jest w złej kolejności.

**Rozwiązanie:** Sprawdź SecurityConfig:
```java
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
```

### 3. Circular dependency (cykliczna zależność)

**Przyczyna:** JwtAuthenticationFilter → SecurityConfig → JwtAuthenticationFilter

**Rozwiązanie:** Użyj `@Lazy` w konstruktorze SecurityConfig:
```java
public SecurityConfig(@Lazy JwtAuthenticationFilter jwtAuthFilter) {
    this.jwtAuthFilter = jwtAuthFilter;
}
```

### 4. JWT parser error (nieprawidłowy klucz)

**Przyczyna:** Klucz w application.properties nie jest prawidłowym Base64.

**Rozwiązanie:** Użyj generatora kluczy:
```java
// Wygeneruj nowy klucz:
String key = Encoders.BASE64.encode(Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
System.out.println(key);
```

### 5. Token wygasa zbyt szybko

**Przyczyna:** `jwt.expiration` w milisekundach (nie sekundach!).

**Rozwiązanie:**
```properties
jwt.expiration=86400000  # 24h (nie 86400!)
jwt.expiration=3600000   # 1h
jwt.expiration=604800000 # 7 dni
```
