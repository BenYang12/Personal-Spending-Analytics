package com.ledgerlens.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Recall from COMP 301 -> Inversion of control: a software engineering architectural principle where the control of object creation, configuration, and execution flow is transferred from your custom application code to an external framework or container.

// @Configuration -> this class may define beans (java objects managed by spring IOC container)
// @EnableAutoConfiguration -> activate classpath-driven auto-config (Instead of writing manual configuration files, the framework inspects my project's dependencies (the "classpath") and automatically configures and wires the necessary application components for me.)
// @ComponentScan -> scan this package and below for classes marked @Component/@Service/@RestController etc. and register each one as a bean in the container. 
// @ComponentScan part is why package location matters in Spring: a controller placed OUTSIDE com.ledgerlens.backend would be silently ignored


@SpringBootApplication
public class BackendApplication{
    public static void main(String[] args){
        // Builds the ApplicationContext (the IoC container)
        // runs auto-config, scans for components, constructs every bean and injects dependencies, then starts imbedded Tomcat
        // if any bean can't be built, app fails fast at startup
        SpringApplication.run(BackendApplication.class, args);
    }
}
