package com.yunhwan.auth.error;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AuthErrorAutomationApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthErrorAutomationApplication.class, args);
	}

}
