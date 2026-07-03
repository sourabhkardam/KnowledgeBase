package com.app.service;

import com.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

	// @RequiredArgsConstructor automatically create constructor with all the fields
	// present and spring use that constructor to inject the UserRepository bean
	private final UserRepository userRepository;

	/**
	 * Loads user from the database by email (used as username). Called
	 * automatically by Spring Security during authentication.
	 */
	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
	}
}
