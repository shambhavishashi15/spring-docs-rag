# Eval report

Run at 2026-10-11T09:19:43.280580432Z against http://localhost:8080 with `eval/questions.json`.

| Metric | Score |
|---|---|
| Questions passed | 19/20 |
| Key facts found (answerable questions) | 31/31 |
| Expected page cited (answerable questions) | 17/18 |
| Correct refusal behaviour (answered vs. "not in the docs") | 20/20 |

## Questions

### q01 ✅ Which property do I set to activate Spring Boot profiles?

- **Key facts:** 1/1 found [spring.profiles.active]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/profiles.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Profiles > Profiles

> You can use the `spring.profiles.active` Environment property to specify which profiles are active [1]. This can be set in your `application.properties` file or on the command line using the `--spring.profiles.active` switch [1].

### q02 ✅ How can I activate several profiles together under one group name?

- **Key facts:** 1/1 found [spring.profiles.group]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/profiles.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Profiles > Profile Groups

> You can define a profile group to create a logical name for a related group of profiles [1]. For example, you can create a production group consisting of the `proddb` and `prodmq` profiles using the following configuration [1]:
> 
> ```properties
> spring.profiles.group.production[0]=proddb
> spring.profiles.group.production[1]=prodmq
> ```
> 
> Your application can then be started with `--spring.profiles.active=production` to activate the production, `proddb`, and `prodmq` profiles all at once [1].

### q03 ✅ How do I register @ConfigurationProperties classes so that Spring Boot binds them?

- **Key facts:** 2/2 found [EnableConfigurationProperties, ConfigurationPropertiesScan]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/external-config.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Externalized Configuration > Type-safe Configuration Properties > Enabling @ConfigurationProperties-annotated Types

> You can register `@ConfigurationProperties` classes so that Spring Boot binds them using one of two methods:
> 
> 1. **Using `@EnableConfigurationProperties`**: Specify the list of types to process using the `@EnableConfigurationProperties` annotation on any `@Configuration` class [1]. For example:
> ```java
> import org.springframework.boot.context.properties.EnableConfigurationProperties;
> import org.springframework.context.annotation.Configuration;
> 
> @Configuration(proxyBeanMethods = false)
> @EnableConfigurationProperties(SomeProperties.class)
> public class MyConfiguration {
> 
> }
> ```
> 
> 2. **Using `@ConfigurationPropertiesScan`**: Add the `@ConfigurationPropertiesScan` annotation to your application (typically on the main application class annotated with `@SpringBootApplication`, or any `@Configuration` class) [1]. For example:
> ```java
> import org.springframework.boot.autoconfigure.SpringBootApplication;
> import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
> 
> @SpringBootApplication
> @ConfigurationPropertiesScan({ "com.example.app", "com.example.another" })
> public class MyApplication {
> 
> }
> ```

### q04 ❌ Which environment variable name binds to the property my.main-project.person.first-name?

- **Key facts:** 1/1 found [MY_MAINPROJECT_PERSON_FIRSTNAME]
- **Expected page cited:** no (https://docs.spring.io/spring-boot/reference/features/external-config.html)
- **Answered:** true (expected an answer)
- **Citations:** none

> Based on the provided documentation, the environment variable name that binds to the property `my.main-project.person.first-name` is `MY_MAINPROJECT_PERSON_FIRSTNAME`.

### q05 ✅ How do I make Spring Boot write its logs to a file, and when do the log files rotate?

- **Key facts:** 2/2 found [logging.file.name|logging.file.path, 10 MB]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/logging.html)
- **Answered:** true (expected an answer)
- **Citations:** [2] Spring Boot: Logging > File Output

> To make Spring Boot write log files in addition to the console output, you need to set either the `logging.file.name` or `logging.file.path` property (for example, in your `application.properties`) [2]. 
> 
> Log files rotate when they reach 10 MB [2].

### q06 ✅ How can I disable a specific auto-configuration class?

- **Key facts:** 2/2 found [exclude, spring.autoconfigure.exclude]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Auto-configuration > Disabling Specific Auto-configuration Classes

> You can disable specific auto-configuration classes by using:
> - The `exclude` attribute of `@SpringBootApplication` or `@EnableAutoConfiguration` [1].
> - The `excludeName` attribute of `@SpringBootApplication` or `@EnableAutoConfiguration` to specify the fully qualified name if the class is not on the classpath [1].
> - The `spring.autoconfigure.exclude` property [1].

### q07 ✅ Which condition annotation enables a configuration based on a Spring Environment property, and which attributes refine the check?

- **Key facts:** 3/3 found [ConditionalOnProperty, havingValue, matchIfMissing]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Creating Your Own Auto-configuration > Condition Annotations > Property Conditions

> The `@ConditionalOnProperty` annotation enables configuration based on a Spring Environment property [1]. The `prefix` and `name` attributes are used to specify the property to check, and more advanced checks can be created using the `havingValue` and `matchIfMissing` attributes [1].

### q08 ✅ In which file must auto-configuration classes be listed so that Spring Boot finds them?

- **Key facts:** 1/1 found [AutoConfiguration.imports]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Creating Your Own Auto-configuration > Locating Auto-configuration Candidates

> Auto-configuration classes must be listed in the `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file within your published jar [1].

### q09 ✅ Which actuator endpoints are exposed over HTTP by default, and how do I expose more of them?

- **Key facts:** 2/2 found [health, management.endpoints.web.exposure.include]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Actuator Endpoints > Exposing Endpoints; [3] Spring Boot: Actuator Endpoints > Security

> By default, only the health endpoint is exposed over HTTP [1][3].
> 
> To expose more endpoints, you can use the `management.endpoints.web.exposure.include` property to configure which endpoints are exposed [3]. For example, to expose the health and info endpoints over JMX or to include specific IDs, you can list them in the include property [1]. You can also use `*` to select all endpoints (note that quotation marks are required if using YAML) [1]:
> 
> ```properties
> management.endpoints.web.exposure.include=*
> ```

### q10 ✅ In a @SpringBootTest, what is the default web environment, and how do I start the embedded server on a random port?

- **Key facts:** 2/2 found [MOCK, RANDOM_PORT]
- **Expected page cited:** yes (https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Boot: Testing Spring Boot Applications > Testing Spring Boot Applications; [2] Spring Boot: Testing Spring Boot Applications > Testing With a Running Server

> By default, `@SpringBootTest` uses `MOCK` as its web environment (loads a web ApplicationContext and provides a mock web environment without starting embedded servers) [1]. 
> 
> To start the embedded server on a random port, you can use the `webEnvironment` attribute set to `WebEnvironment.RANDOM_PORT`, as shown in the following example [1][2]:
> 
> ```java
> @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
> ```

### q11 ✅ Which interface represents the Spring IoC container?

- **Key facts:** 1/1 found [ApplicationContext]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/core/beans/basics.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Container Overview > Container Overview

> The `org.springframework.context.ApplicationContext` interface represents the Spring IoC container [1].

### q12 ✅ What is the difference between the singleton and prototype bean scopes?

- **Key facts:** 2/2 found [one shared instance|single instance|exactly one instance|one instance, new bean instance|new instance]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Bean Scopes > Bean Scopes; [2] Spring Framework: Bean Scopes > The Prototype Scope; [4] Spring Framework: Bean Scopes > The Singleton Scope

> Based on the provided documentation, the differences between the singleton and prototype bean scopes are:
> 
> * **Singleton:** Scopes a single bean definition to a single object instance for each Spring IoC container [1]. Only one shared instance is managed, stored in a cache, and all subsequent requests and references return that same cached object [4].
> * **Prototype:** Scopes a single bean definition to any number of object instances [1]. A new bean instance is created every time a request for that specific bean is made (such as when it is injected into another bean or requested through a `getBean()` method call) [2]. 
> 
> Additionally, Spring manages the complete lifecycle of a singleton bean, whereas it does not manage the complete lifecycle of a prototype bean (configured destruction lifecycle callbacks are not called for prototypes, and client code must clean up and release resources) [2].

### q13 ✅ How do I declare initialization and destruction callbacks on a @Bean method?

- **Key facts:** 2/2 found [initMethod, destroyMethod]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Using the @Bean Annotation > Receiving Lifecycle Callbacks; [2] Spring Framework: Using the @Bean Annotation > Using the @Bean Annotation

> You can declare initialization and destruction callbacks on a `@Bean` method by specifying the `initMethod` and `destroyMethod` attributes, similar to Spring XML's `init-method` and `destroy-method` attributes [1][2]. 
> 
> For example:
> 
> ```java
> @Configuration
> public class AppConfig {
> 
> 	@Bean(initMethod = "init")
> 	public BeanOne beanOne() {
> 		return new BeanOne();
> 	}
> 
> 	@Bean(destroyMethod = "cleanup")
> 	public BeanTwo beanTwo() {
> 		return new BeanTwo();
> 	}
> }
> ```
> 
> Additionally, classes defined with the `@Bean` annotation support JSR-250 lifecycle annotations (`@PostConstruct` and `@PreDestroy`), regular Spring lifecycle interfaces (`InitializingBean`, `DisposableBean`, `Lifecycle`), and standard `*Aware` interfaces [1].

### q14 ✅ Which exceptions make a @Transactional method roll back by default?

- **Key facts:** 3/3 found [RuntimeException, Error, checked]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Using @Transactional > @Transactional Settings

> By default, any `RuntimeException` or `Error` triggers a rollback, while any checked `Exception` does not [1].

### q15 ✅ What does PROPAGATION_REQUIRES_NEW do?

- **Key facts:** 1/1 found [independent physical transaction|independent transaction|new transaction]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Transaction Propagation > Understanding PROPAGATION_REQUIRES_NEW

> PROPAGATION_REQUIRES_NEW always uses an independent physical transaction for each affected transaction scope and never participates in an existing transaction for an outer scope [1]. The underlying resource transactions are different and can commit or roll back independently [1]. An outer transaction is not affected by an inner transaction’s rollback status, and an inner transaction’s locks are released immediately after its completion [1]. Additionally, an independent inner transaction can declare its own isolation level, timeout, and read-only settings rather than inheriting an outer transaction's characteristics [1].

### q16 ✅ How do I handle exceptions thrown by Spring MVC controllers, and how do I apply that handling across all controllers?

- **Key facts:** 2/2 found [ExceptionHandler, ControllerAdvice]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: MVC Exceptions > Exceptions; [2] Spring Framework: MVC Exceptions > Exception Mapping; [3] Spring Framework: MVC Error Responses > Non-Standard Fields; [4] Spring Framework: MVC Error Responses > Error Responses; [5] Spring Framework: MVC Error Responses > Render

> To handle exceptions thrown by controller methods, you can use `@ExceptionHandler` methods within `@Controller` or `@ControllerAdvice` classes [1]. For example:
> 
> ```java
> import java.io.IOException;
> 
> import org.springframework.http.ResponseEntity;
> import org.springframework.stereotype.Controller;
> import org.springframework.web.bind.annotation.ExceptionHandler;
> 
> @Controller
> public class SimpleController {
> 
> 	@ExceptionHandler(IOException.class)
> 	public ResponseEntity<String> handle() {
> 		return ResponseEntity.internalServerError().body("Could not read file storage");
> 	}
> 
> }
> ```
> 
> To apply exception handling across all controllers centrally, you can use a `@ControllerAdvice` class [1][3]. Additionally, you can extend `ResponseEntityExceptionHandler` and declare it as an `@ControllerAdvice` to centrally handle all Spring MVC exceptions and `ErrorResponseException` instances [3][4][5]. In a multi-`@ControllerAdvice` arrangement, you can declare primary root exception mappings on a `@ControllerAdvice` prioritized with a corresponding order [2].

### q17 ✅ How does Spring MVC support RFC 9457 problem details in error responses?

- **Key facts:** 2/2 found [ProblemDetail, ResponseEntityExceptionHandler]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: MVC Error Responses > Error Responses; [2] Spring Framework: MVC Error Responses > Render; [3] Spring Framework: MVC Error Responses > Non-Standard Fields; [5] Spring Framework: MVC Error Responses > Customization and i18n

> Spring Framework supports RFC 9457 ("Problem Details for HTTP APIs") through the following main abstractions [1]:
> 
> * **`ProblemDetail`**: A simple container representing an RFC 9457 problem detail for both standard and non-standard fields [1].
> * **`ErrorResponse`**: A contract that allows exceptions to encapsulate and expose HTTP error response details (status, headers, and an RFC 9457 body). All Spring MVC exceptions implement this [1].
> * **`ErrorResponseException`**: A basic `ErrorResponse` implementation that can serve as a convenient base class [1].
> * **`ResponseEntityExceptionHandler`**: A convenient base class for an `@ControllerAdvice` that handles Spring MVC and `ErrorResponseException` exceptions, rendering an error response with a body [1]. It resolves message codes for "type", "title", and "detail" through a `MessageSource` to update the `ProblemDetail` fields [5].
> 
> Additionally, you can return `ProblemDetail` or `ErrorResponse` from any `@ExceptionHandler` or `@RequestMapping` method to render an RFC 9457 response [2]. Jackson JSON and XML codecs use specific producible media types (`application/problem+json` or `application/problem+xml`) for content negotiation [2]. You can also register `ErrorResponse` interceptors via MVC Config with a `WebMvcConfigurer` [2], and extend responses with non-standard fields using the `properties` Map of `ProblemDetail` or by extending `ProblemDetail` [3].

### q18 ✅ How do I enable method validation with Bean Validation in a Spring application?

- **Key facts:** 1/1 found [MethodValidationPostProcessor]
- **Expected page cited:** yes (https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
- **Answered:** true (expected an answer)
- **Citations:** [1] Spring Framework: Java Bean Validation > Spring-driven Method Validation

> To integrate the method validation feature of Bean Validation into a Spring context, you can define a `MethodValidationPostProcessor` bean [1]:
> 
> ```java
> @Configuration
> public class ApplicationConfiguration {
> 
> 	@Bean
> 	public static MethodValidationPostProcessor validationPostProcessor() {
> 		return new MethodValidationPostProcessor();
> 	}
> }
> ```
> 
> Additionally, target classes must be annotated with Spring's `@Validated` annotation to be eligible for Spring-driven method validation [1].

### q19 ✅ How do I configure OAuth2 login with Spring Security?

- **Answered:** false (expected "not in the docs")
- **Citations:** none

> I don't know based on the provided Spring documentation.

### q20 ✅ How do I set the concurrency of a Kafka listener in Spring Boot?

- **Answered:** false (expected "not in the docs")
- **Citations:** none

> I don't know based on the provided Spring documentation.
