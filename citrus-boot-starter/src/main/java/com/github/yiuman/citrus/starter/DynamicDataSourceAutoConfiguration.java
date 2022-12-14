package com.github.yiuman.citrus.starter;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.xa.DruidXADataSource;
import com.baomidou.mybatisplus.autoconfigure.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.yiuman.citrus.support.datasource.*;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.transaction.jta.JtaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jta.atomikos.AtomikosDataSourceBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Consumer;

/**
 * ???????????????????????????
 *
 * @author yiuman
 * @date 2020/12/1
 */
@SuppressWarnings("rawtypes")
@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore({DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class, JtaAutoConfiguration.class})
@AutoConfigureAfter({MybatisPlusLanguageDriverAutoConfiguration.class})
@EnableConfigurationProperties({DynamicDataSourceProperties.class, DataSourceProperties.class, MybatisPlusProperties.class})
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class})
public class DynamicDataSourceAutoConfiguration implements InitializingBean {

    private final DataSourceProperties dataSourceProperties;

    private final DynamicDataSourceProperties dynamicDataSourceProperties;

    private final MybatisPlusProperties mybatisPlusProperties;

    private final org.apache.ibatis.plugin.Interceptor[] interceptors;

    private final TypeHandler[] typeHandlers;

    private final LanguageDriver[] languageDrivers;

    private final ResourceLoader resourceLoader;

    private final DatabaseIdProvider databaseIdProvider;

    private final List<ConfigurationCustomizer> configurationCustomizers;

    private final List<MybatisPlusPropertiesCustomizer> mybatisPlusPropertiesCustomizers;

    private final ApplicationContext applicationContext;

    public DynamicDataSourceAutoConfiguration(DataSourceProperties dataSourceProperties,
                                              DynamicDataSourceProperties dynamicDataSourceProperties,
                                              MybatisPlusProperties mybatisPlusProperties,
                                              ObjectProvider<Interceptor[]> interceptorsProvider,
                                              ObjectProvider<TypeHandler[]> typeHandlersProvider,
                                              ObjectProvider<LanguageDriver[]> languageDriversProvider,
                                              ResourceLoader resourceLoader,
                                              ObjectProvider<DatabaseIdProvider> databaseIdProvider,
                                              ObjectProvider<List<ConfigurationCustomizer>> configurationCustomizersProvider,
                                              ObjectProvider<List<MybatisPlusPropertiesCustomizer>> mybatisPlusPropertiesCustomizerProvider,
                                              ApplicationContext applicationContext) {
        this.dataSourceProperties = dataSourceProperties;
        this.dynamicDataSourceProperties = dynamicDataSourceProperties;
        this.mybatisPlusProperties = mybatisPlusProperties;
        this.interceptors = interceptorsProvider.getIfAvailable();
        this.typeHandlers = typeHandlersProvider.getIfAvailable();
        this.languageDrivers = languageDriversProvider.getIfAvailable();
        this.resourceLoader = resourceLoader;
        this.databaseIdProvider = databaseIdProvider.getIfAvailable();
        this.configurationCustomizers = configurationCustomizersProvider.getIfAvailable();
        this.mybatisPlusPropertiesCustomizers = mybatisPlusPropertiesCustomizerProvider.getIfAvailable();
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        if (!CollectionUtils.isEmpty(mybatisPlusPropertiesCustomizers)) {
            mybatisPlusPropertiesCustomizers.forEach(i -> i.customize(mybatisPlusProperties));
        }
        mybatisPlusProperties.getGlobalConfig().setBanner(false);
        checkConfigFileExists();
    }

    private void checkConfigFileExists() {
        if (this.mybatisPlusProperties.isCheckConfigLocation() && StringUtils.hasText(this.mybatisPlusProperties.getConfigLocation())) {
            Resource resource = this.resourceLoader.getResource(this.mybatisPlusProperties.getConfigLocation());
            Assert.state(resource.exists(),
                    "Cannot find config location: " + resource + " (please add config file or check your Mybatis configuration)");
        }
    }

    /**
     * ?????????????????????
     *
     * @return DataSource
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(DynamicDataSourceAutoConfiguration.MultiplesDatasourceCondition.class)
    public DataSource dynamicDatasource() {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        Map<String, DataSourceProperties> dataSourcePropertiesMap = dynamicDataSourceProperties.getDatasource();
        int dataSourceSize = Objects.nonNull(dataSourcePropertiesMap) ? dataSourcePropertiesMap.size() : 0;
        Map<Object, Object> dataSourceMap = new HashMap<>(dataSourceSize + 1);
        DataSource defaultDataSource;
        String primary = Optional.of(dynamicDataSourceProperties.getPrimary()).orElse("default");
        //??????????????????????????????
        boolean enableMultipleTx = dynamicDataSourceProperties.isEnableMultipleTx();
        if (dataSourceSize > 0) {
            defaultDataSource = buildDataSource(primary, dataSourceProperties, enableMultipleTx);
            dataSourcePropertiesMap.forEach((key, properties) -> dataSourceMap.put(key, buildDataSource(key, properties, enableMultipleTx)));
        } else {
            defaultDataSource = buildDataSource(null, dataSourceProperties, false);
        }
        dataSourceMap.put(primary, defaultDataSource);
        dynamicDataSource.setTargetDataSources(dataSourceMap);
        dynamicDataSource.setDefaultTargetDataSource(defaultDataSource);
        return dynamicDataSource;
    }

    @Bean
    @ConditionalOnMissingBean(SqlSessionTemplate.class)
    @Conditional(DynamicDataSourceAutoConfiguration.MultiplesDatasourceCondition.class)
    public DynamicSqlSessionTemplate sqlSessionTemplate() throws Exception {
        Map<String, DataSourceProperties> dataSourcePropertiesMap = dynamicDataSourceProperties.getDatasource();
        int dataSourceSize = Objects.nonNull(dataSourcePropertiesMap) ? dataSourcePropertiesMap.size() : 0;
        Map<Object, SqlSessionFactory> sqlSessionFactoryMap = new HashMap<>(dataSourceSize + 1);

        //??????????????????????????????
        boolean enableMultipleTx = dynamicDataSourceProperties.isEnableMultipleTx();
        DataSource defaultDataSource;
        String primary = Optional.of(dynamicDataSourceProperties.getPrimary()).orElse("");
        if (dataSourceSize > 0) {
            defaultDataSource = buildDataSource(primary, dataSourceProperties, enableMultipleTx);
            for (Map.Entry<String, DataSourceProperties> entry : dataSourcePropertiesMap.entrySet()) {
                sqlSessionFactoryMap.put(entry.getKey(), createSqlSessionFactory(buildDataSource(entry.getKey(), entry.getValue(), enableMultipleTx)));
            }
        } else {
            defaultDataSource = buildDataSource(null, dataSourceProperties, false);
        }
        SqlSessionFactory defaultSqlSessionFactory = createSqlSessionFactory(defaultDataSource);
        sqlSessionFactoryMap.put(primary, defaultSqlSessionFactory);
        DynamicSqlSessionTemplate dynamicSqlSessionTemplate = new DynamicSqlSessionTemplate(defaultSqlSessionFactory);
        dynamicSqlSessionTemplate.setTargetSqlSessionFactories(sqlSessionFactoryMap);
        dynamicSqlSessionTemplate.setDefaultTargetSqlSessionFactory(defaultSqlSessionFactory);
        dynamicSqlSessionTemplate.setStrict(dynamicDataSourceProperties.isStrict());
        return dynamicSqlSessionTemplate;
    }

    @Bean
    @Conditional(DynamicDataSourceAutoConfiguration.MultiplesDatasourceCondition.class)
    public DynamicDataSourceAnnotationAdvisor dynamicDataSourceAnnotationAdvisor() {
        return new DynamicDataSourceAnnotationAdvisor(new DynamicDataSourceAnnotationInterceptor());
    }

    private DataSource buildDataSource(String resourceName, DataSourceProperties properties, boolean enableMultipleTx) {
        return enableMultipleTx ? buildDruidXaDataSource(resourceName, properties) : buildDruidDataSource(properties);
    }

    /**
     * ?????????????????????druid?????????
     *
     * @param properties ???????????????
     * @return DruidDataSource
     */
    public DataSource buildDruidDataSource(DataSourceProperties properties) {
        DruidDataSource druidDataSource = new DruidDataSource();
        druidDataSource.setUrl(properties.getUrl());
        druidDataSource.setUsername(properties.getUsername());
        druidDataSource.setPassword(properties.getPassword());
        druidDataSource.setDriverClassName(properties.getDriverClassName());
        return druidDataSource;
    }


    /**
     * ??????????????????XA?????????
     *
     * @param resourceName ????????????????????????XA????????????
     * @param properties   ???????????????
     * @return XA?????????
     */
    public DataSource buildDruidXaDataSource(String resourceName, DataSourceProperties properties) {
        DruidXADataSource druidDataSource = new DruidXADataSource();
        druidDataSource.setUrl(properties.getUrl());
        druidDataSource.setUsername(properties.getUsername());
        druidDataSource.setPassword(properties.getPassword());
        druidDataSource.setDriverClassName(properties.getDriverClassName());

        AtomikosDataSourceBean atomikosDataSourceBean = new AtomikosDataSourceBean();
        atomikosDataSourceBean.setXaDataSource(druidDataSource);
        atomikosDataSourceBean.setUniqueResourceName(String.format("%s$$%s", resourceName, UUID.randomUUID().toString().substring(0, 15)));
        return atomikosDataSourceBean;
    }

    /**
     * ?????????mybatis-plus??????????????????
     *
     * @param dynamicSqlSessionTemplate ?????????????????????
     * @return SqlSessionFactory
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(DynamicDataSourceAutoConfiguration.MultiplesDatasourceCondition.class)
    public SqlSessionFactory sqlSessionFactory(DynamicSqlSessionTemplate dynamicSqlSessionTemplate) {
        return dynamicSqlSessionTemplate.getSqlSessionFactory();
    }

    /**
     * ?????????????????????
     * ?????????????????????SqlSessionFactory
     *
     * @param dataSource ?????????
     * @return SqlSessionFactory
     * @throws Exception in case of creation errors
     */
    private SqlSessionFactory createSqlSessionFactory(DataSource dataSource) throws Exception {
        // ?????? MybatisSqlSessionFactoryBean ????????? SqlSessionFactoryBean
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setVfs(SpringBootVFS.class);
        if (StringUtils.hasText(this.mybatisPlusProperties.getConfigLocation())) {
            factory.setConfigLocation(this.resourceLoader.getResource(this.mybatisPlusProperties.getConfigLocation()));
        }
        applyConfiguration(factory);
        if (this.mybatisPlusProperties.getConfigurationProperties() != null) {
            factory.setConfigurationProperties(this.mybatisPlusProperties.getConfigurationProperties());
        }

        if (this.databaseIdProvider != null) {
            factory.setDatabaseIdProvider(this.databaseIdProvider);
        }
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeAliasesPackage())) {
            factory.setTypeAliasesPackage(this.mybatisPlusProperties.getTypeAliasesPackage());
        }
        if (this.mybatisPlusProperties.getTypeAliasesSuperType() != null) {
            factory.setTypeAliasesSuperType(this.mybatisPlusProperties.getTypeAliasesSuperType());
        }
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeHandlersPackage())) {
            factory.setTypeHandlersPackage(this.mybatisPlusProperties.getTypeHandlersPackage());
        }
        if (!ObjectUtils.isEmpty(this.typeHandlers)) {
            factory.setTypeHandlers(this.typeHandlers);
        }
        if (!ObjectUtils.isEmpty(this.mybatisPlusProperties.resolveMapperLocations())) {
            factory.setMapperLocations(this.mybatisPlusProperties.resolveMapperLocations());
        }

        Class<? extends LanguageDriver> defaultLanguageDriver = this.mybatisPlusProperties.getDefaultScriptingLanguageDriver();
        if (!ObjectUtils.isEmpty(this.languageDrivers)) {
            factory.setScriptingLanguageDrivers(this.languageDrivers);
        }
        Optional.ofNullable(defaultLanguageDriver).ifPresent(factory::setDefaultScriptingLanguageDriver);
        if (!ObjectUtils.isEmpty(this.interceptors)) {
            factory.setPlugins(this.interceptors);
        }
        //??????????????????
        if (StringUtils.hasLength(this.mybatisPlusProperties.getTypeEnumsPackage())) {
            factory.setTypeEnumsPackage(this.mybatisPlusProperties.getTypeEnumsPackage());
        }
        // ????????????MybatisSqlSessionFactoryBean??????????????????????????????GlobalConfig????????????????????????
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        //????????????
        globalConfig.setBanner(false);
        //  ???????????????
        this.getBeanThen(MetaObjectHandler.class, globalConfig::setMetaObjectHandler);
        // ?????????????????????
        this.getBeansThen(IKeyGenerator.class, i -> globalConfig.getDbConfig().setKeyGenerators(i));
        // ??????sql?????????
        this.getBeanThen(ISqlInjector.class, globalConfig::setSqlInjector);
        // ??????ID?????????
        this.getBeanThen(IdentifierGenerator.class, globalConfig::setIdentifierGenerator);
        //?????? GlobalConfig ??? MybatisSqlSessionFactoryBean
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    /**
     * ???????????? MybatisSqlSessionFactoryBean
     *
     * @param factory MybatisSqlSessionFactoryBean
     */
    private void applyConfiguration(MybatisSqlSessionFactoryBean factory) {
        // ?????? MybatisConfiguration
        factory.setConfiguration(getCopyMybatisConfiguration(this.mybatisPlusProperties));
    }

    private MybatisConfiguration getCopyMybatisConfiguration(MybatisPlusProperties mybatisPlusProperties) {
        MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();

        //????????????????????????????????????
        Optional.ofNullable(mybatisPlusProperties.getConfiguration())
                .ifPresent(propertiesConfiguration ->
                        BeanUtils.copyProperties(propertiesConfiguration, mybatisConfiguration));

        if (!CollectionUtils.isEmpty(this.configurationCustomizers)) {
            for (ConfigurationCustomizer customizer : this.configurationCustomizers) {
                customizer.customize(mybatisConfiguration);
            }
        }

        return mybatisConfiguration;
    }

    /**
     * ??????spring???????????????????????????bean,??????????????????
     *
     * @param clazz    class
     * @param consumer ??????
     * @param <T>      ??????
     */
    private <T> void getBeanThen(Class<T> clazz, Consumer<T> consumer) {
        if (this.applicationContext.getBeanNamesForType(clazz, false, false).length > 0) {
            consumer.accept(this.applicationContext.getBean(clazz));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private <T> void getBeansThen(Class<T> clazz, Consumer<List<T>> consumer) {
        if (this.applicationContext.getBeanNamesForType(clazz, false, false).length > 0) {
            Map<String, T> beansOfType = this.applicationContext.getBeansOfType(clazz);
            List<T> clazzList = new ArrayList<>();
            beansOfType.forEach((k, v) -> clazzList.add(v));
            consumer.accept(clazzList);
        }

    }


    @Configuration(proxyBeanMethods = false)
    @Conditional(DynamicDataSourceAutoConfiguration.MultiplesDatasourceCondition.class)
    static class CustomJtaAutoConfiguration extends JtaAutoConfiguration {
    }

    /**
     * ??????????????????????????????????????????????????????spring.datasource.multiples????????????????????????
     */
    static class MultiplesDatasourceCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            MutablePropertySources propertySources = ((StandardEnvironment) environment).getPropertySources();
            boolean matchMutableDataSources = propertySources.stream()
                    .filter(propertySource -> propertySource instanceof MapPropertySource)
                    .anyMatch(propertySource ->
                            Arrays.stream(((MapPropertySource) propertySource).getPropertyNames())
                                    .anyMatch(propertyName -> propertyName.startsWith("spring.datasource.multiples")));

            return matchMutableDataSources
                    ? ConditionOutcome.match()
                    : ConditionOutcome.noMatch("spring.datasource.multiples");
        }
    }

}
