package com.springlearning.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;

/**
 * Only mandatory thing to mark a class an Entity class is by annotating that
 * class with @Entity annotation and field must be annotated with @Id annotation
 * to declare primary key of entity.
 */

@Entity
public class SourabhDao {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long sourabhId;
	
	@NotBlank(message = "name field should not be empty")
	private String name;
	
	@NotBlank(message = "jobTitle field should not be empty")
	private String jobTitle;

	public SourabhDao() {

	}

	public SourabhDao(String name, String jobTitle) {
		super();
		this.name = name;
		this.jobTitle = jobTitle;
	}

	@Override
	public String toString() {
		return "SourabhDao [sourabhId=" + sourabhId + ", name=" + name + ", jobTitle=" + jobTitle + "]";
	}

	public Long getSourabhId() {
		return sourabhId;
	}

	public void setSourabhId(Long sourabhId) {
		this.sourabhId = sourabhId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getJobTitle() {
		return jobTitle;
	}

	public void setJobTitle(String jobTitle) {
		this.jobTitle = jobTitle;
	}

}
