package pl.wiktor.springsecurityintro.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.wiktor.springsecurityintro.controller.dto.RegisterRequest;
import pl.wiktor.springsecurityintro.model.User;
import pl.wiktor.springsecurityintro.repository.UserRepository;

import java.util.List;

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
        // Dodajemy domyślnych użytkowników jeśli repozytorium jest puste
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
