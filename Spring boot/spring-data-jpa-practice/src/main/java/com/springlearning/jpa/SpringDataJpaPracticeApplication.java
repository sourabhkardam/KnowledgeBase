package com.springlearning.jpa;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

import com.springlearning.jpa.entity.SourabhDao;
import com.springlearning.jpa.repo.SourabhRepository;

@SpringBootApplication
public class SpringDataJpaPracticeApplication {

	public static void main(String[] args) {
		ApplicationContext context = SpringApplication.run(SpringDataJpaPracticeApplication.class, args);

		SourabhRepository sourabhRepository = context.getBean(SourabhRepository.class);
//		saveSourabhDao(sourabhRepository);
		fetchSourabhDao(sourabhRepository);
		fetchSourabhDaoAndUpdate(sourabhRepository);
	}

	private static void fetchSourabhDaoAndUpdate(SourabhRepository sourabhRepository) {
		SourabhDao sourabhDao = sourabhRepository.findById(1l).get();
		sourabhDao.setJobTitle("Senior Software Engineer");
		sourabhRepository.save(sourabhDao);
	}

	private static void fetchSourabhDao(SourabhRepository sourabhRepository) {
//		System.out.println(sourabhRepository.findAll());
		System.out.println(sourabhRepository.findById(1l).get());

		System.out.println(sourabhRepository.findBySourabhId(1l));
	}

	private static void saveSourabhDao(SourabhRepository sourabhRepository) {
		sourabhRepository.saveAll(Arrays.asList(new SourabhDao("Sourabh", "SSE"), new SourabhDao("Dinkar", "SSE"),
				new SourabhDao("Abhishek", "SE"), new SourabhDao("Rohit", "ASE")));

		sourabhRepository.save(new SourabhDao("Rohit", "ASE"));

	}

}
