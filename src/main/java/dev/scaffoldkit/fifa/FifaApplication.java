package dev.scaffoldkit.fifa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FifaApplication {

	public static void main(String[] args) {
		SpringApplication.run(FifaApplication.class, args);
	}

}
