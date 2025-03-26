package org.tera201.vcsmanager.domain;

public class Developer {

	private String name;
	private String email;

	public Developer(String name, String email) {
		this.name = name;
		this.email = email;
	}
	
	public String getName() {
		return name;
	}
	
	public String getEmail() {
		return email;
	}

	
	@Override
	public String toString() {
		return "Committer [name=" + name + ", email=" + email + "]";
	}

	
}
