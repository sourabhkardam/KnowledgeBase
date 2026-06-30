package com.springlearning.jpa.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.springlearning.jpa.entity.SourabhDao;

public interface SourabhRepository extends JpaRepository<SourabhDao, Long> {

	public SourabhDao findBySourabhId(Long id);

}
