package com.example.authapp.controller;

import com.example.authapp.exception.AppException;
import com.example.authapp.exception.BadRequestException;
import com.example.authapp.model.JwtRefreshToken;
import com.example.authapp.model.Role;
import com.example.authapp.model.RoleName;
import com.example.authapp.model.User;
import com.example.authapp.payload.*;
import com.example.authapp.repository.JwtRefreshTokenRepository;
import com.example.authapp.repository.RoleRepository;
import com.example.authapp.repository.UserRepository;
import com.example.authapp.security.JwtTokenProvider;
import com.example.authapp.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

/**
 * Created by deepakpalankar 
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtTokenProvider tokenProvider;

    @Autowired
    JwtRefreshTokenRepository jwtRefreshTokenRepository;

    @Value("${app.jwtExpirationInMs}")
    private long jwtExpirationInMs;

    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getUsernameOrEmail(),
                        loginRequest.getPassword()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();

        String accessToken = tokenProvider.generateToken(userPrincipal);
        String refreshToken = tokenProvider.generateRefreshToken();

        saveRefreshToken(userPrincipal, refreshToken);

        return ResponseEntity.ok(new JwtAuthenticationResponse(accessToken, refreshToken, jwtExpirationInMs));
    }

    @PostMapping("/refreshToken")
    public ResponseEntity<?> refreshAccessToken(@Valid @RequestBody RefreshTokenRequest refreshTokenRequest) {
        return jwtRefreshTokenRepository.findById(refreshTokenRequest.getRefreshToken()).map(jwtRefreshToken -> {
            User user = jwtRefreshToken.getUser();
            String accessToken = tokenProvider.generateToken(UserPrincipal.create(user));
            return ResponseEntity.ok(new JwtAuthenticationResponse(accessToken, jwtRefreshToken.getToken(), jwtExpirationInMs));
        }).orElseThrow(() -> new BadRequestException("Invalid Refresh Token"));
    }

    private void saveRefreshToken(UserPrincipal userPrincipal, String refreshToken) {
        // Persist Refresh Token

        JwtRefreshToken jwtRefreshToken = new JwtRefreshToken(refreshToken);
        jwtRefreshToken.setUser(userRepository.getOne(userPrincipal.getId()));

        Instant expirationDateTime = Instant.now().plus(360, ChronoUnit.DAYS);  // Todo Add this in application.properties
        jwtRefreshToken.setExpirationDateTime(expirationDateTime);

        jwtRefreshTokenRepository.save(jwtRefreshToken);
    }

    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignUpRequest signUpRequest) {
        if(userRepository.existsByUsername(signUpRequest.getUsername())) {
            return new ResponseEntity(new ApiResponse(false, "Username is already taken!"),
                    HttpStatus.BAD_REQUEST);
        }

        if(userRepository.existsByEmail(signUpRequest.getEmail())) {
            return new ResponseEntity(new ApiResponse(false, "Email Address already in use!"),
                    HttpStatus.BAD_REQUEST);
        }

        // Creating user's account
        User user = new User(signUpRequest.getName(), signUpRequest.getUsername(),
                signUpRequest.getEmail(), signUpRequest.getPassword());

        user.setPassword(passwordEncoder.encode(user.getPassword()));

        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new AppException("User Role not set."));

        user.setRoles(Collections.singleton(userRole));

        User result = userRepository.save(user);

        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath().path("/users/{username}")
                .buildAndExpand(result.getUsername()).toUri();

        return ResponseEntity.created(location).body(new ApiResponse(true, "User registered successfully"));
    }
}
