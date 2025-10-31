# Spring Security - Rejestracja Użytkowników i Własna Obsługa Wyjątków

## Przejście z In-Memory na ArrayList Storage

W poprzednim rozdziale używaliśmy **InMemoryUserDetailsManager** z users hardcoded w SecurityConfig:
- Użytkownicy definiowani w kodzie (user/password, admin/admin)
- Brak możliwości rejestracji nowych użytkowników
- Dane resetowane przy każdym restarcie aplikacji

**Teraz przechodzimy na ArrayList storage**:
- Własny model użytkownika (`User.java`)
- Repository z ArrayList do przechowywania użytkowników
- Możliwość rejestracji nowych użytkowników przez API
- Własny UserDetailsService ładujący użytkowników z ArrayList

## Własna Obsługa Wyjątków 401/403

Zamiast domyślnych odpowiedzi Spring Security dodaliśmy:
- **Własne handlery** dla błędów 401 (Unauthorized) i 403 (Forbidden)
- **Spójny format JSON** dla wszystkich błędów
- **Polskie komunikaty** błędów
- **Timestamp i path** w każdej odpowiedzi

## Co zostało dodane?

### 1. Model użytkownika implementujący UserDetails

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/model/User.java`

```java
public class User implements UserDetails {
    private Long id;
    private String username;
    private String password;
    private String email;
    private List<String> roles;
    private boolean enabled;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
    // ... inne metody UserDetails
}
```

**Dlaczego implementujemy UserDetails?**
- Spring Security wymaga obiektu `UserDetails` do uwierzytelniania
- `UserDetails` to interfejs definiujący podstawowe informacje o użytkowniku:
  - `getUsername()` - nazwa użytkownika
  - `getPassword()` - zahashowane hasło
  - `getAuthorities()` - lista ról/uprawnień
  - `isEnabled()`, `isAccountNonExpired()`, `isAccountNonLocked()`, `isCredentialsNonExpired()`

**Kluczowa metoda - getAuthorities():**
```java
@Override
public Collection<? extends GrantedAuthority> getAuthorities() {
    return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toList());
}
```

- Konwertuje `List<String> roles` (np. `["USER", "ADMIN"]`) na `Collection<GrantedAuthority>`
- Dodaje prefiks `"ROLE_"` → `"ROLE_USER"`, `"ROLE_ADMIN"`
- Spring Security wymaga tego prefiksu przy używaniu `.hasRole("USER")`

**Alternatywa bez prefiksu:**
```java
// Bez prefiksu w kodzie:
return roles.stream()
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());

// W SecurityConfig trzeba użyć .hasAuthority() zamiast .hasRole():
.requestMatchers("/api/admin").hasAuthority("ADMIN")
```

### 2. Repository z ArrayList

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/repository/UserRepository.java`

```java
@Repository
public class UserRepository {
    private final List<User> users = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public User save(User user) {
        if (user.getId() == null) {
            user.setId(idGenerator.getAndIncrement());
            users.add(user);
        } else {
            users.removeIf(u -> u.getId().equals(user.getId()));
            users.add(user);
        }
        return user;
    }

    public Optional<User> findByUsername(String username) {
        return users.stream()
                .filter(user -> user.getUsername().equals(username))
                .findFirst();
    }

    public boolean existsByUsername(String username) {
        return users.stream()
                .anyMatch(user -> user.getUsername().equals(username));
    }
    // ... inne metody
}
```

### 3. UserService - logika biznesowa i UserDetailsService

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/service/UserService.java`

```java
@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        initializeDefaultUsers();
    }

    private void initializeDefaultUsers() {
        if (userRepository.findAll().isEmpty()) {
            User user = new User(
                    null,
                    "user",
                    passwordEncoder.encode("password"),
                    "user@example.com",
                    List.of("USER")
            );
            userRepository.save(user);

            User admin = new User(
                    null,
                    "admin",
                    passwordEncoder.encode("admin"),
                    "admin@example.com",
                    List.of("ADMIN", "USER")
            );
            userRepository.save(admin);
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Użytkownik nie został znaleziony: " + username));
    }

    public User registerUser(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Użytkownik o podanej nazwie już istnieje");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Użytkownik o podanym emailu już istnieje");
        }

        User user = new User(
                null,
                request.username(),
                passwordEncoder.encode(request.password()),
                request.email(),
                List.of("USER")
        );

        return userRepository.save(user);
    }
}
```

#### implements UserDetailsService

```java
@Service
public class UserService implements UserDetailsService
```

**Dlaczego implementujemy UserDetailsService?**
- Spring Security potrzebuje beana implementującego `UserDetailsService`
- Używany przez `AuthenticationManager` do ładowania użytkownika podczas logowania
- Używany przez `JwtAuthenticationFilter` do walidacji tokena

**Metoda loadUserByUsername():**
```java
@Override
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Użytkownik nie został znaleziony: " + username));
}
```

- Wymagana przez interfejs `UserDetailsService`
- Zwraca `UserDetails` (nasz `User` implementuje ten interfejs)
- Rzuca `UsernameNotFoundException` jeśli użytkownik nie istnieje
- Spring Security automatycznie łapie ten wyjątek i zwraca błąd logowania

#### Rejestracja użytkownika

```java
public User registerUser(RegisterRequest request) {
    // Walidacja - czy username już istnieje
    if (userRepository.existsByUsername(request.username())) {
        throw new IllegalArgumentException("Użytkownik o podanej nazwie już istnieje");
    }

    // Walidacja - czy email już istnieje
    if (userRepository.existsByEmail(request.email())) {
        throw new IllegalArgumentException("Użytkownik o podanym emailu już istnieje");
    }

    // Tworzenie nowego użytkownika
    User user = new User(
            null,  // ID - wygenerowane przez repository
            request.username(),
            passwordEncoder.encode(request.password()),  // hash hasła!
            request.email(),
            List.of("USER")  // domyślna rola
    );

    return userRepository.save(user);
}
```

**Walidacja:**
- Sprawdza, czy username/email już nie istnieje
- Rzuca `IllegalArgumentException` - zostanie złapany przez GlobalExceptionHandler
- W produkcji lepiej użyć dedykowanych wyjątków (np. `UserAlreadyExistsException`)

**Domyślna rola USER:**
```java
List.of("USER")
```
- Nowi użytkownicy dostają tylko rolę `USER`
- Admin może być utworzony tylko przez inicjalizację/migrację bazy

### 4. DTOs dla rejestracji

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/controller/dto/RegisterRequest.java`

```java
public record RegisterRequest(
        String username,
        String password,
        String email
) {
}
```

### 5. Endpoint rejestracji w AuthController

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/controller/AuthController.java`

```java
@PostMapping("/register")
public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
    User user = userService.registerUser(request);

    Map<String, String> response = new HashMap<>();
    response.put("message", "Użytkownik został pomyślnie zarejestrowany");
    response.put("username", user.getUsername());
    response.put("email", user.getEmail());

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

**HttpStatus.CREATED (201):**
```java
return ResponseEntity.status(HttpStatus.CREATED).body(response);
```
- Status 201 oznacza "zasób został utworzony"
- Bardziej semantyczny niż 200 OK

**Flow rejestracji:**
```
1. Request:
   POST /api/auth/register
   {"username":"newuser","password":"pass123","email":"new@example.com"}

2. AuthController.register()
   ↓
3. UserService.registerUser()
   - Sprawdza, czy username/email już istnieje
   - Tworzy nowego użytkownika z zahashowanym hasłem
   - Zapisuje w repository
   ↓
4. Response:
   HTTP 201 Created
   {"message":"Użytkownik został pomyślnie zarejestrowany","username":"newuser","email":"new@example.com"}
```

### 6. Zmiany w SecurityConfig

#### Usunięcie InMemoryUserDetailsManager bean

**Przed:**
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

**Po:**
```java
// Bean usunięty - używamy UserService jako UserDetailsService
```

**Dlaczego?**
- `UserService` implementuje `UserDetailsService` i jest oznaczony `@Service`
- Spring automatycznie wykrywa go jako bean
- Nie potrzebujemy już ręcznie tworzyć beana

**Jak Spring Security wie, którego UserDetailsService użyć?**
```java
@Bean
public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
}
```

`AuthenticationConfiguration` automatycznie znajduje bean implementujący `UserDetailsService` (nasz `UserService`) i konfiguruje `AuthenticationManager`.

### 7. Własne wyjątki dla 401/403

#### Klasa ErrorResponse

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/exception/ErrorResponse.java`

```java
public record ErrorResponse(
        int status,
        String message,
        LocalDateTime timestamp,
        String path
) {
    public ErrorResponse(int status, String message, String path) {
        this(status, message, LocalDateTime.now(), path);
    }
}
```

**Konstruktor delegujący:**
```java
public ErrorResponse(int status, String message, String path) {
    this(status, message, LocalDateTime.now(), path);
}
```
- Używa canonical constructor (ten z wszystkimi polami)
- Automatycznie dodaje timestamp

**Przykładowa odpowiedź JSON:**
```json
{
  "status": 401,
  "message": "Nieprawidłowy login lub hasło",
  "timestamp": "2025-10-31T13:26:03.123",
  "path": "/api/auth/login"
}
```

#### CustomAuthenticationEntryPoint - obsługa 401

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/security/CustomAuthenticationEntryPoint.java`

```java
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public CustomAuthenticationEntryPoint() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Brak lub nieprawidłowy token autoryzacyjny. Zaloguj się ponownie.",
                request.getRequestURI()
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
```

**Kiedy jest wywoływany?**
- Gdy użytkownik nie jest uwierzytelniony (brak tokena/nieprawidłowy token)
- Próba dostępu do chronionego endpointa bez logowania
- Wygasły token JWT

**AuthenticationEntryPoint:**
```java
public interface AuthenticationEntryPoint {
    void commence(HttpServletRequest request,
                  HttpServletResponse response,
                  AuthenticationException authException) throws IOException;
}
```

**Dlaczego ObjectMapper z JavaTimeModule?**
```java
this.objectMapper.registerModule(new JavaTimeModule());
```
- Domyślnie Jackson nie wie, jak serializować `LocalDateTime`
- `JavaTimeModule` dodaje wsparcie dla Java 8 Time API
- Bez tego błąd: `InvalidDefinitionException: Java 8 date/time type not supported by default`

**Serializacja do JSON:**
```java
objectMapper.writeValue(response.getWriter(), errorResponse);
```
- Konwertuje `ErrorResponse` na JSON
- Zapisuje do `response.getWriter()` (output stream HTTP)

#### CustomAccessDeniedHandler - obsługa 403

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/security/CustomAccessDeniedHandler.java`

```java
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CustomAccessDeniedHandler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Brak uprawnień do tego zasobu. Wymagane są wyższe uprawnienia.",
                request.getRequestURI()
        );

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
```

**Kiedy jest wywoływany?**
- Użytkownik jest zalogowany, ale nie ma wystarczających uprawnień
- Przykład: USER próbuje dostać się do `/api/admin` (wymaga ROLE_ADMIN)

**AccessDeniedHandler vs AuthenticationEntryPoint:**

| Aspekt | AuthenticationEntryPoint (401) | AccessDeniedHandler (403) |
|--------|-------------------------------|--------------------------|
| **Kiedy?** | Brak uwierzytelnienia | Brak autoryzacji |
| **Status** | 401 Unauthorized | 403 Forbidden |
| **Przykład** | Brak tokena JWT | USER próbuje dostać się do /api/admin |
| **Znaczenie** | "Nie wiem, kim jesteś" | "Wiem, kim jesteś, ale nie masz uprawnień" |

#### Konfiguracja w SecurityConfig

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/public", "/api/auth/**").permitAll()
            .requestMatchers("/api/admin").hasRole("ADMIN")
            .requestMatchers("/api/user").hasAnyRole("USER", "ADMIN")
            .anyRequest().authenticated()
        )
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        )
        .exceptionHandling(exception -> exception
            .accessDeniedHandler(accessDeniedHandler)
            .authenticationEntryPoint(authenticationEntryPoint)
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

**Nowa sekcja - exceptionHandling:**
```java
.exceptionHandling(exception -> exception
    .accessDeniedHandler(accessDeniedHandler)         // 403
    .authenticationEntryPoint(authenticationEntryPoint)  // 401
)
```

- Rejestruje nasze custom handlery
- Spring Security używa ich zamiast domyślnych

**Beany autowired w SecurityConfig:**
```java
private final CustomAccessDeniedHandler accessDeniedHandler;
private final CustomAuthenticationEntryPoint authenticationEntryPoint;

public SecurityConfig(@Lazy JwtAuthenticationFilter jwtAuthFilter,
                      CustomAccessDeniedHandler accessDeniedHandler,
                      CustomAuthenticationEntryPoint authenticationEntryPoint) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.accessDeniedHandler = accessDeniedHandler;
    this.authenticationEntryPoint = authenticationEntryPoint;
}
```

### 8. GlobalExceptionHandler - obsługa wyjątków z kontrolerów

**Plik:** `src/main/java/pl/wiktor/springsecurityintro/exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(
            UnauthorizedException ex,
            HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenException(
            ForbiddenException ex,
            HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException ex,
            HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Nieprawidłowy login lub hasło",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }
}
```

**@RestControllerAdvice:**
- Globalny handler wyjątków dla wszystkich kontrolerów
- Łapie wyjątki rzucone w `@RestController`
- Alternatywa dla try-catch w każdym kontrolerze

**@ExceptionHandler:**
```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgumentException(...)
```
- Definiuje metodę obsługującą konkretny typ wyjątku
- Automatycznie wywoływana gdy kontroler rzuci ten wyjątek

**Przykład użycia:**
```java
// W UserService.registerUser():
if (userRepository.existsByUsername(request.username())) {
    throw new IllegalArgumentException("Użytkownik o podanej nazwie już istnieje");
}

// GlobalExceptionHandler łapie wyjątek i zwraca:
// HTTP 400 Bad Request
// {"status":400,"message":"Użytkownik o podanej nazwie już istnieje","timestamp":"...","path":"/api/auth/register"}
```

**BadCredentialsException:**
```java
@ExceptionHandler(BadCredentialsException.class)
public ResponseEntity<ErrorResponse> handleBadCredentialsException(...)
```
- Rzucany przez Spring Security przy złym hasle/username
- Łapiemy go i zwracamy polski komunikat
- Bez tego: domyślny komunikat Spring Security (angielski, mniej czytelny)

**Różnica: GlobalExceptionHandler vs Security Handlers**

| Typ | Handler | Gdzie wywoływany | Przykład |
|-----|---------|-----------------|----------|
| **Controller exceptions** | GlobalExceptionHandler | Wyjątki w `@RestController` | `IllegalArgumentException` w `registerUser()` |
| **Security exceptions** | AuthenticationEntryPoint / AccessDeniedHandler | Wyjątki Spring Security (filtry) | Brak tokena JWT (401), brak uprawnień (403) |

## Pełny flow rejestracji

```
1. Request:
   POST /api/auth/register
   Content-Type: application/json
   Body: {"username":"newuser","password":"pass123","email":"new@example.com"}

2. Spring Security - SecurityFilterChain:
   - JwtAuthenticationFilter: brak tokena → przepuszcza dalej
   - AuthorizationFilter: sprawdza .requestMatchers("/api/auth/**").permitAll() → ✅

3. AuthController.register(@RequestBody RegisterRequest request):
   ↓
4. UserService.registerUser(request):
   - Sprawdza: existsByUsername("newuser") → false ✅
   - Sprawdza: existsByEmail("new@example.com") → false ✅
   - Tworzy nowego użytkownika:
     User user = new User(
         null,                                  // ID
         "newuser",                             // username
         passwordEncoder.encode("pass123"),     // hasło → "$2a$10$..."
         "new@example.com",                     // email
         List.of("USER")                        // role
     )
   - userRepository.save(user):
     - user.setId(1L)  // AtomicLong
     - users.add(user)

5. Response:
   HTTP 201 Created
   {
     "message": "Użytkownik został pomyślnie zarejestrowany",
     "username": "newuser",
     "email": "new@example.com"
   }
```

**Walidacja - duplikat username:**
```
1. Request:
   POST /api/auth/register
   {"username":"newuser","password":"pass123","email":"another@example.com"}

2. UserService.registerUser():
   - Sprawdza: existsByUsername("newuser") → true ❌
   - Rzuca: throw new IllegalArgumentException("Użytkownik o podanej nazwie już istnieje")

3. GlobalExceptionHandler.handleIllegalArgumentException():
   - Tworzy ErrorResponse
   - Zwraca ResponseEntity z 400 Bad Request

4. Response:
   HTTP 400 Bad Request
   {
     "status": 400,
     "message": "Użytkownik o podanej nazwie już istnieje",
     "timestamp": "2025-10-31T13:31:34.123",
     "path": "/api/auth/register"
   }
```

## Pełny flow logowania z nowym UserService

```
1. Request:
   POST /api/auth/login
   {"username":"newuser","password":"pass123"}

2. AuthController.login():
   authenticationManager.authenticate(
       new UsernamePasswordAuthenticationToken("newuser", "pass123")
   )
   ↓
3. DaoAuthenticationProvider:
   - Wywołuje userDetailsService.loadUserByUsername("newuser")
   ↓
4. UserService.loadUserByUsername("newuser"):
   - userRepository.findByUsername("newuser")
   - Zwraca User object (implements UserDetails)
   ↓
5. DaoAuthenticationProvider:
   - passwordEncoder.matches("pass123", "$2a$10$...")
   - Hasło prawidłowe → zwraca Authentication
   ↓
6. AuthController:
   - UserDetails principal = auth.getPrincipal()
   - String token = jwtService.generateToken(principal)
   ↓
7. Response:
   HTTP 200 OK
   {"token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWI6Im5ld3VzZXIi..."}
```

## Flow dostępu do chronionego endpointa

### Scenariusz 1: Prawidłowy token

```
1. Request:
   GET /api/user
   Authorization: Bearer <valid-token>

2. JwtAuthenticationFilter:
   - Parsuje token → username = "newuser"
   - Wywołuje userDetailsService.loadUserByUsername("newuser")
   ↓
3. UserService.loadUserByUsername("newuser"):
   - userRepository.findByUsername("newuser")
   - Zwraca User z rolami ["USER"]
   ↓
4. JwtAuthenticationFilter:
   - Waliduje token → ✅
   - Tworzy Authentication z authorities ["ROLE_USER"]
   - SecurityContextHolder.getContext().setAuthentication(authToken)
   ↓
5. AuthorizationFilter:
   - Sprawdza: .requestMatchers("/api/user").hasAnyRole("USER", "ADMIN")
   - Użytkownik ma ROLE_USER → ✅
   ↓
6. DummyController.userEndpoint():
   - Zwraca odpowiedź

7. Response:
   HTTP 200 OK
   {"message":"User endpoint","user":"newuser"}
```

### Scenariusz 2: Brak tokena (401)

```
1. Request:
   GET /api/user
   (brak nagłówka Authorization)

2. JwtAuthenticationFilter:
   - authHeader == null → filterChain.doFilter()
   - SecurityContext.authentication = null
   ↓
3. AuthorizationFilter:
   - SecurityContext.authentication == null
   - Endpoint wymaga uwierzytelnienia
   - Rzuca AuthenticationException
   ↓
4. CustomAuthenticationEntryPoint.commence():
   - Tworzy ErrorResponse
   - response.setStatus(401)
   - objectMapper.writeValue(response.getWriter(), errorResponse)

5. Response:
   HTTP 401 Unauthorized
   {
     "status": 401,
     "message": "Brak lub nieprawidłowy token autoryzacyjny. Zaloguj się ponownie.",
     "timestamp": "2025-10-31T13:31:34.123",
     "path": "/api/user"
   }
```

### Scenariusz 3: Brak uprawnień (403)

```
1. Request:
   GET /api/admin
   Authorization: Bearer <user-token>  (USER, nie ADMIN)

2. JwtAuthenticationFilter:
   - Parsuje token → username = "newuser"
   - Ładuje UserDetails z rolą ["USER"]
   - Ustawia Authentication w SecurityContext

3. AuthorizationFilter:
   - Sprawdza: .requestMatchers("/api/admin").hasRole("ADMIN")
   - Użytkownik ma tylko ROLE_USER, nie ROLE_ADMIN
   - Rzuca AccessDeniedException
   ↓
4. CustomAccessDeniedHandler.handle():
   - Tworzy ErrorResponse
   - response.setStatus(403)
   - objectMapper.writeValue(response.getWriter(), errorResponse)

5. Response:
   HTTP 403 Forbidden
   {
     "status": 403,
     "message": "Brak uprawnień do tego zasobu. Wymagane są wyższe uprawnienia.",
     "timestamp": "2025-10-31T13:31:34.123",
     "path": "/api/admin"
   }
```

## Testowanie

### 1. Rejestracja nowego użytkownika

**cURL:**
```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123","email":"test@example.com"}'
```

**Odpowiedź:**
```json
{
  "message": "Użytkownik został pomyślnie zarejestrowany",
  "username": "testuser",
  "email": "test@example.com"
}
```

### 2. Próba rejestracji z duplikatem username

```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"other123","email":"other@example.com"}'
```

**Odpowiedź:**
```json
{
  "status": 400,
  "message": "Użytkownik o podanej nazwie już istnieje",
  "timestamp": "2025-10-31T13:31:34.123",
  "path": "/api/auth/register"
}
```

### 3. Logowanie zarejestrowanego użytkownika

```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123"}'
```

**Odpowiedź:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWI6InRlc3R1c2VyIi..."
}
```

### 4. Dostęp do chronionego endpointa

```bash
curl http://localhost:8081/api/user \
  -H "Authorization: Bearer <token-z-logowania>"
```

**Odpowiedź:**
```json
{
  "message": "User endpoint",
  "user": "testuser"
}
```

### 5. Próba dostępu bez tokena (test 401)

```bash
curl http://localhost:8081/api/user
```

**Odpowiedź:**
```json
{
  "status": 401,
  "message": "Brak lub nieprawidłowy token autoryzacyjny. Zaloguj się ponownie.",
  "timestamp": "2025-10-31T13:31:34.123",
  "path": "/api/user"
}
```

### 6. Próba dostępu do /api/admin jako USER (test 403)

```bash
# Zaloguj się jako testuser (rola USER)
TOKEN=$(curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123"}' \
  | jq -r '.token')

# Próba dostępu do /api/admin
curl http://localhost:8081/api/admin \
  -H "Authorization: Bearer $TOKEN"
```

**Odpowiedź:**
```json
{
  "status": 403,
  "message": "Brak uprawnień do tego zasobu. Wymagane są wyższe uprawnienia.",
  "timestamp": "2025-10-31T13:31:34.123",
  "path": "/api/admin"
}
```

### 7. Dostęp jako admin

```bash
# Zaloguj się jako admin (rola ADMIN)
ADMIN_TOKEN=$(curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  | jq -r '.token')

# Dostęp do /api/admin
curl http://localhost:8081/api/admin \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Odpowiedź:**
```json
{
  "message": "Admin endpoint",
  "user": "admin"
}
```

## Podsumowanie zmian

### Nowe klasy utworzone:

1. **Model:**
   - `User.java` - implementuje UserDetails

2. **Repository:**
   - `UserRepository.java` - ArrayList storage

3. **Service:**
   - `UserService.java` - implementuje UserDetailsService, logika rejestracji

4. **DTO:**
   - `RegisterRequest.java` - dane do rejestracji

5. **Exception:**
   - `ErrorResponse.java` - format błędu JSON
   - `UnauthorizedException.java` - custom 401
   - `ForbiddenException.java` - custom 403
   - `GlobalExceptionHandler.java` - obsługa wyjątków z kontrolerów

6. **Security:**
   - `CustomAuthenticationEntryPoint.java` - obsługa 401
   - `CustomAccessDeniedHandler.java` - obsługa 403

### Zmienione klasy:

1. **AuthController.java:**
   - Dodany endpoint `/register`
   - Autowired `UserService`

2. **SecurityConfig.java:**
   - Usunięty bean `UserDetailsService` (InMemoryUserDetailsManager)
   - Dodana konfiguracja `exceptionHandling()`
   - Autowired custom security handlers
