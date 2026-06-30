package com.springlearning.jpa.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.springlearning.jpa.entity.SourabhDao;
import com.springlearning.jpa.repo.SourabhRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/sourabh")
public class SourabhController {

	@Autowired
	private SourabhRepository repository;

	@PostMapping("/save")
	public ResponseEntity<SourabhDao> saveSourabh(@Valid @RequestBody SourabhDao sourabhDao) {
		SourabhDao savedSourabhDao = repository.save(sourabhDao);
		return ResponseEntity.ok(savedSourabhDao);
	}

	@GetMapping("/id/{id}")
	public ResponseEntity<?> getSourabhDao(@PathVariable Long id) {
		Optional<SourabhDao> optional = repository.findById(id);
		if (optional.isPresent()) {
			return ResponseEntity.status(HttpStatus.OK).body(optional.get());
		} else {
			optional.orElseThrow(() -> new EntityNotFoundException());
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("Error", "User not found"));
		}
	}

}
