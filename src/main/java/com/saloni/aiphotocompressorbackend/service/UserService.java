package com.saloni.aiphotocompressorbackend.service;

import com.saloni.aiphotocompressorbackend.dto.ForgotPasswordRequest;
import com.saloni.aiphotocompressorbackend.dto.LoginRequest;
import com.saloni.aiphotocompressorbackend.dto.SignupRequest;
import com.saloni.aiphotocompressorbackend.entity.UserEntity;
import com.saloni.aiphotocompressorbackend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    // ============================
    // SIGNUP
    // ============================
    public String signup(SignupRequest request) {

        // Full Name Validation
        if (request.getFullName() == null || request.getFullName().trim().isEmpty()) {
            throw new RuntimeException("Full name is required");
        }

        if (!request.getFullName().matches("^[a-zA-Z ]+$")) {
            throw new RuntimeException("Name should contain only letters");
        }

        // Email Validation
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new RuntimeException("Email is required");
        }

        if (!request.getEmail().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            throw new RuntimeException("Invalid email format");
        }

        // Phone Validation
        if (request.getPhoneNumber() == null || request.getPhoneNumber().trim().isEmpty()) {
            throw new RuntimeException("Phone number is required");
        }

        if (!request.getPhoneNumber().matches("^[0-9]{10}$")) {
            throw new RuntimeException("Phone number must be 10 digits");
        }

        // Password Validation
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new RuntimeException("Password is required");
        }

        if (request.getPassword().length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }

        // Existing checks
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        if (userRepository.findByPhoneNumber(request.getPhoneNumber()).isPresent()) {
            throw new RuntimeException("Phone number already registered");
        }

        UserEntity user = new UserEntity();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setPassword(request.getPassword());

        userRepository.save(user);

        return "Signup successful";
    }

    // ============================
    // LOGIN
    // ============================
    public UserEntity login(LoginRequest request) {

        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new RuntimeException("Email is required");
        }

        if (!request.getEmail().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            throw new RuntimeException("Invalid email format");
        }

        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new RuntimeException("Password is required");
        }

        if (request.getPassword().length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }

        UserEntity user = userRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.getPassword().equals(request.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        return user;
    }

    // ============================
    // FORGOT PASSWORD
    // ============================
    public String resetPassword(ForgotPasswordRequest request) {

        // Email Validation
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new RuntimeException("Email is required");
        }

        if (!request.getEmail().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            throw new RuntimeException("Invalid email format");
        }

        // Phone Validation
        if (request.getPhoneNumber() == null || request.getPhoneNumber().trim().isEmpty()) {
            throw new RuntimeException("Phone number is required");
        }

        if (!request.getPhoneNumber().matches("^[0-9]{10}$")) {
            throw new RuntimeException("Phone number must be 10 digits");
        }

        // New Password Validation
        if (request.getNewPassword() == null || request.getNewPassword().trim().isEmpty()) {
            throw new RuntimeException("New password is required");
        }

        if (request.getNewPassword().length() < 6) {
            throw new RuntimeException("Password must be at least 6 characters");
        }

        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.getPhoneNumber().equals(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number does not match");
        }

        user.setPassword(request.getNewPassword());
        userRepository.save(user);

        return "Password updated successfully";
    }
}