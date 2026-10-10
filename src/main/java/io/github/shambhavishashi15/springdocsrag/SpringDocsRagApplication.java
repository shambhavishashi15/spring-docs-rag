package io.github.shambhavishashi15.springdocsrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpringDocsRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringDocsRagApplication.class, args);
	}

}
