package cn.servicehub.config;

import cn.servicehub.common.DependencyProbe;
import cn.servicehub.common.MoneyTypeHandler;
import cn.servicehub.common.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods=false)
@EnableTransactionManagement
@MapperScan(basePackages="cn.servicehub",annotationClass=Mapper.class)
public class PersistenceConfig {
    @Bean @ConditionalOnMissingBean(DataSource.class)
    DataSource dataSource(ServiceHubProperties properties){
        var config=new HikariConfig();
        config.setJdbcUrl(properties.database().url());config.setUsername(properties.database().username());config.setPassword(properties.database().password());
        config.setMaximumPoolSize(10);config.setMinimumIdle(0);config.setConnectionTimeout(3000);config.setValidationTimeout(1000);
        config.setConnectionInitSql("SET time_zone = '+00:00'");config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");config.setPoolName("servicehub-db");
        return new HikariDataSource(config);
    }
    @Bean(initMethod="migrate") Flyway flyway(DataSource dataSource){
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").baselineOnMigrate(false).load();
    }
    @Bean @DependsOn("flyway") SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        var configuration=new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(UtcInstantTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MoneyTypeHandler.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        return factory.getObject();
    }
    @Bean PlatformTransactionManager transactionManager(DataSource dataSource){return new DataSourceTransactionManager(dataSource);}
    @Bean JdbcTemplate jdbcTemplate(DataSource dataSource){return new JdbcTemplate(dataSource);}
    @Bean DependencyProbe databaseProbe(DataSource dataSource){
        return new DependencyProbe(){
            public String name(){return "database";}
            public boolean isHealthy() throws Exception {
                try(var connection=dataSource.getConnection();var query=connection.createStatement()){
                    query.setQueryTimeout(2);try(var result=query.executeQuery("SELECT 1")){return result.next()&&result.getInt(1)==1;}
                }
            }
        };
    }
}
