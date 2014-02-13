package org.springframework.data.cassandra.config;

import static org.springframework.data.cassandra.config.BeanDefinitionUtils.getBeanDefinitionsOfType;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.data.cassandra.convert.CassandraConverter;
import org.springframework.data.cassandra.convert.MappingCassandraConverter;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.mapping.DefaultCassandraMappingContext;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.Session;

/**
 * {@link BeanDefinitionRegistryPostProcessor} that does its best to register any missing Spring Data Cassandra beans
 * that can be defaulted. Specifically, it attempts to create default bean definitions for the following required
 * interface types via their default implementation types:
 * <ul>
 * <li>{@link CassandraOperations} via {@link CassandraTemplate}</li>
 * <li>{@link CassandraMappingContext} via {@link DefaultCassandraMappingContext}</li>
 * <li> {@link CassandraConverter} via {@link MappingCassandraConverter}</li>
 * </ul>
 * <p/>
 * If there are multiple definitions for any type that another type depends on, an {@link IllegalStateException} is
 * thrown. For example, if there are two definitions for type {@link CassandraMappingContext} present and no definition
 * for type {@link CassandraConverter}, then it's impossible to know which {@link CassandraMappingContext} is to be used
 * when creating a default definition for the {@link CassandraConverter}.
 * <p/>
 * If a single definition of a required type is present, then it is used. For example, if there is already a
 * {@link CassandraMappingContext} definition present, then it will be used in the
 * {@link DefaultCassandraMappingContext} bean definition.
 * <p/>
 * It requires that a single {@link Session} or {@link CassandraDataSessionFactoryBean} definition be present. As
 * described above, multiple {@link Session} definitions, multiple {@link CassandraDataSessionFactoryBean} definitions,
 * or both a {@link Session} and {@link CassandraDataSessionFactoryBean} will cause an {@link IllegalStateException} to
 * be thrown.
 * 
 * @author Matthew T. Adams
 */
public class CassandraMappingBeanFactoryPostProcessor implements BeanDefinitionRegistryPostProcessor {

	/**
	 * Does nothing.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
	}

	/**
	 * Ensures that {@link BeanDefinition}s for a {@link CassandraMappingContext} and a {@link CassandraConverter} exist.
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {

		if (!(registry instanceof ListableBeanFactory)) {
			return;
		}
		ListableBeanFactory factory = (ListableBeanFactory) registry;

		registerMissingDefaultableBeanDefinitions(registry, factory);
	}

	protected void registerMissingDefaultableBeanDefinitions(BeanDefinitionRegistry registry, ListableBeanFactory factory) {

		// see if any template definitions exist, which requires a converter, which requires a mapping context
		BeanDefinitionHolder[] templateBeans = getBeanDefinitionsOfType(registry, factory, CassandraOperations.class, true,
				false);

		if (templateBeans.length >= 1) {
			return;
		}

		// need a session & converter for the default template

		// see if an actual Session definition exists
		String sessionBeanName = findSessionBeanName(registry, factory);

		// see if any converter bean definitions exist, which requires a mapping context
		BeanDefinitionHolder[] converterBeans = getBeanDefinitionsOfType(registry, factory,
				MappingCassandraConverter.class, true, false);

		if (converterBeans.length == 1) {

			registerDefaultTemplate(registry, sessionBeanName, converterBeans[0].getBeanName());
			return;
		} else if (converterBeans.length > 1) {
			// then throw, because we need to create a default converter, but we wouldn't know which mapping context to use
			throw new IllegalStateException(String.format(
					"found %d beans of type [%s] - can't disambiguate for creation of [%s]", converterBeans.length,
					CassandraConverter.class.getName(), CassandraTemplate.class.getName()));
		}

		// see if any mapping context bean definitions exist
		BeanDefinitionHolder[] contextBeans = getBeanDefinitionsOfType(registry, factory, CassandraMappingContext.class,
				true, false);

		if (contextBeans.length > 1) {
			// then throw, because we need to create a default converter, but we wouldn't know which mapping context to use
			throw new IllegalStateException(String.format(
					"found %d beans of type [%s] - can't disambiguate for creation of [%s]", contextBeans.length,
					CassandraMappingContext.class.getName(), MappingCassandraConverter.class.getName()));
		}

		// create the mapping context if necessary
		BeanDefinitionHolder contextBean = contextBeans.length == 1 ? contextBeans[0] : null;
		if (contextBean == null) {
			contextBean = regsiterDefaultContext(registry);
		}

		// create the default converter & template bean definitions
		BeanDefinitionHolder converter = registerDefaultConverter(registry, contextBean.getBeanName());
		registerDefaultTemplate(registry, sessionBeanName, converter.getBeanName());
	}

	public String findSessionBeanName(BeanDefinitionRegistry registry, ListableBeanFactory factory) {

		// first, search for any session and session factory beans
		BeanDefinitionHolder[] sessionBeans = getBeanDefinitionsOfType(registry, factory, Session.class, true, false);
		BeanDefinitionHolder[] sessionFactoryBeans = getBeanDefinitionsOfType(registry, factory,
				CassandraDataSessionFactoryBean.class, true, false);

		int sessionCount = sessionBeans.length;
		int sessionFactoryCount = sessionFactoryBeans.length;
		int totalCount = sessionCount + sessionFactoryCount;

		if (totalCount == 0 || totalCount > 1) { // can't create default template -- none or multiple
			throw createSessionException(totalCount, Session.class, CassandraDataSessionFactoryBean.class);
		}

		if (sessionCount == 1) {
			return sessionBeans[0].getBeanName();
		}
		// else it must be the one session factory bean
		return sessionFactoryBeans[0].getBeanName();
	}

	protected IllegalStateException createSessionException(int beanDefinitionCount, Class<?>... types) {

		return new IllegalStateException(String.format("found %d beans of type%s [%s] - %s for creation of default [%s]",
				beanDefinitionCount, beanDefinitionCount == 1 ? "" : "s", StringUtils.arrayToCommaDelimitedString(types),
				beanDefinitionCount == 0 ? "need exactly one" : "can't disambiguate", CassandraTemplate.class.getName()));
	}

	protected BeanDefinitionHolder regsiterDefaultContext(BeanDefinitionRegistry registry) {

		BeanDefinitionHolder contextBean = new BeanDefinitionHolder(BeanDefinitionBuilder.genericBeanDefinition(
				DefaultCassandraMappingContext.class).getBeanDefinition(), DefaultDataBeanNames.CONTEXT);

		registry.registerBeanDefinition(contextBean.getBeanName(), contextBean.getBeanDefinition());

		return contextBean;
	}

	public BeanDefinitionHolder registerDefaultConverter(BeanDefinitionRegistry registry, String contextBeanName) {

		BeanDefinitionBuilder converterBeanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(
				MappingCassandraConverter.class).addConstructorArgReference(contextBeanName);
		BeanDefinitionHolder beanDefinition = new BeanDefinitionHolder(converterBeanDefinitionBuilder.getBeanDefinition(),
				DefaultDataBeanNames.CONVERTER);

		registry.registerBeanDefinition(beanDefinition.getBeanName(), beanDefinition.getBeanDefinition());

		return beanDefinition;
	}

	public BeanDefinitionHolder registerDefaultTemplate(BeanDefinitionRegistry registry, String sessionBeanName,
			String converterBeanName) {

		BeanDefinitionBuilder templateBeanDefinitionBuilder = BeanDefinitionBuilder
				.genericBeanDefinition(CassandraTemplate.class).addConstructorArgReference(sessionBeanName)
				.addConstructorArgReference(converterBeanName);
		BeanDefinition beanDefinition = templateBeanDefinitionBuilder.getBeanDefinition();

		BeanDefinitionHolder template = new BeanDefinitionHolder(beanDefinition, DefaultDataBeanNames.TEMPLATE);
		registry.registerBeanDefinition(template.getBeanName(), template.getBeanDefinition());

		return template;
	}
}