package com.saloni.aiphotocompressorbackend.controller;

import com.saloni.aiphotocompressorbackend.dto.ForgotPasswordRequest;
import com.saloni.aiphotocompressorbackend.dto.LoginRequest;
import com.saloni.aiphotocompressorbackend.dto.SignupRequest;
import com.saloni.aiphotocompressorbackend.entity.UserEntity;
import com.saloni.aiphotocompressorbackend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private UserService userService;

    // ===============================
    // SIGNUP
    // ===============================
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        try {
            String message = userService.signup(request);

            Map<String, String> response = new HashMap<>();
            response.put("message", message);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }

    // ===============================
    // LOGIN
    // ===============================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            UserEntity user = userService.login(request);

            Map<String, Object> response = new HashMap<>();
            response.put("id", user.getId());
            response.put("fullName", user.getFullName());
            response.put("email", user.getEmail());
            response.put("phoneNumber", user.getPhoneNumber());
            response.put("message", "Login successful");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }

    // ===============================
    // FORGOT PASSWORD
    // ===============================
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            String message = userService.resetPassword(request);

            Map<String, String> response = new HashMap<>();
            response.put("message", message);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());

            return ResponseEntity.badRequest().body(error);
        }
    }
}