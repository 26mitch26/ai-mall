package com.ai.mall.portal.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** Local development bootstrap creates only the new idempotency table; no existing rows are changed. */
@Component
@ConditionalOnProperty(name="agent.operations.initialize-schema",havingValue="true")
public class AgentOperationSchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;
    public AgentOperationSchemaInitializer(DataSource dataSource) { this.dataSource=dataSource; }
    @Override public void run(ApplicationArguments arguments) {
        ResourceDatabasePopulator script=new ResourceDatabasePopulator(new ClassPathResource("db/agent-operation-idempotency.sql"));
        script.setSqlScriptEncoding("UTF-8");
        script.execute(dataSource);
    }
}
