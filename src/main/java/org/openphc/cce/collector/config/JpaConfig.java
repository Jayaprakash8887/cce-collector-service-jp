package org.openphc.cce.collector.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA configuration — enables repositories and transaction management.
 */
@Configuration
@EnableJpaRepositories(basePackages = "org.openphc.cce.collector.domain.repository")
@EnableTransactionManagement
public class JpaConfig {
    // Spring Boot auto-configures DataSource, EntityManagerFactory, TransactionManager
    // via application.yml and spring-boot-starter-data-jpa
}
