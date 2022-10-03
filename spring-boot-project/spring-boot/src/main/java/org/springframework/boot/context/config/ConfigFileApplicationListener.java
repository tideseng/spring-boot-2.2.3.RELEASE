/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>file:./config/</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>classpath:</li>
 * </ul>
 * The list is ordered by precedence (properties defined in locations higher in the list
 * override those defined in lower locations).
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 * @since 1.0.0
 */
public class ConfigFileApplicationListener implements EnvironmentPostProcessor, SmartApplicationListener, Ordered { // 通过事件通知完成Spring Boot配置文件的加载（实现了ApplicationListener事件监听器接口，事件监听器在SpringApplication初始化中进行加载）

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/"; // 实际上是从后往前的顺序加载application.properties、application.yml文件，前面路径下的文件会覆盖后面路径的文件

	private static final String DEFAULT_NAMES = "application"; // Spring Boot默认的配置文件名称为：application

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	private static final Set<String> LOAD_FILTERED_PROPERTY; // 静态代码块中进行初始化

	static {
		Set<String> filteredProperties = new HashSet<>();
		filteredProperties.add("spring.profiles.active");
		filteredProperties.add("spring.profiles.include");
		LOAD_FILTERED_PROPERTY = Collections.unmodifiableSet(filteredProperties);
	}

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) { // 支持的事件类型（适配器模式）
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType) // 支持ApplicationEnvironmentPreparedEvent、ApplicationPreparedEvent事件
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) { // 处理ApplicationEnvironmentPreparedEvent、ApplicationPreparedEvent事件
		if (event instanceof ApplicationEnvironmentPreparedEvent) { // ApplicationEnvironmentPreparedEvent事件是在SpringApplication#prepareEnvironment方法中通过listeners.environmentPrepared(environment)触发，即在应用程序启动后准备Environment环境时触发
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event); // 处理ApplicationEnvironmentPreparedEvent事件
		}
		if (event instanceof ApplicationPreparedEvent) { // ApplicationPreparedEvent事件是在SpringApplication#prepareContext方法中通过listeners.contextLoaded(context)触发，即在应用程序已经启动调用refreshed之前触发
			onApplicationPreparedEvent(event);
		}
	}

	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) { // 处理ApplicationEnvironmentPreparedEvent事件，添加properties/yml配置文件属性源到Environment中
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors(); // 从spring.factories文件加载org.springframework.boot.env.EnvironmentPostProcessor值对应的EnvironmentPostProcessor实例列表
		postProcessors.add(this); // ConfigFileApplicationListener当前类也实现了EnvironmentPostProcessor（spring.factories中的org.springframework.boot.env.EnvironmentPostProcessor对应类并不包含当前类）
		AnnotationAwareOrderComparator.sort(postProcessors); // 对EnvironmentPostProcessor实现类按顺序排序
		for (EnvironmentPostProcessor postProcessor : postProcessors) { // 遍历EnvironmentPostProcessor
			postProcessor.postProcessEnvironment(event.getEnvironment(), event.getSpringApplication()); // 循环调用Environment的后置处理器方法
		}
	}

	List<EnvironmentPostProcessor> loadPostProcessors() { // 从spring.factories文件加载org.springframework.boot.env.EnvironmentPostProcessor值对应的EnvironmentPostProcessor实例列表
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class, getClass().getClassLoader());
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) { // Environment的后置处理器方法，处理ApplicationEnvironmentPreparedEvent事件
		addPropertySources(environment, application.getResourceLoader()); // 添加properties/yml配置文件属性源到Environment中
	}

	private void onApplicationPreparedEvent(ApplicationEvent event) {
		this.logger.switchTo(ConfigFileApplicationListener.class);
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
		RandomValuePropertySource.addToEnvironment(environment); // 添加与随机数相关的配置源
		new Loader(environment, resourceLoader).load(); // 私有内部类Load类最终负责加载配置文件属性源到Environment中
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) { // 设置配置文件的查找路径
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private static class PropertySourceOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			PropertySource<?> defaultProperties = environment.getPropertySources().remove(DEFAULT_PROPERTIES); // 移除defaultProperties属性源
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties); // 将defaultProperties属性源到调整到队尾
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		private final ConfigurableEnvironment environment; // Environment对象

		private final PropertySourcesPlaceholdersResolver placeholdersResolver; // 占位符解析器

		private final ResourceLoader resourceLoader;

		private final List<PropertySourceLoader> propertySourceLoaders; // PropertySource加载器，只加载符合条件的配置文件

		private Deque<Profile> profiles; // 候选的Profile（双端队列结构）

		private List<Profile> processedProfiles; // 维护处理过的Profile（List<Profile>结构）

		private boolean activatedProfiles; // 是否指定了/有激活的Profile

		private Map<Profile, MutablePropertySources> loaded; // 存放Profile与MutablePropertySources的映射

		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) { // ConfigFileApplicationListener.Loader内部类构造方法
			this.environment = environment;
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(this.environment); // 创建PropertySourcesPlaceholdersResolver对象
			this.resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader(); // 默认需要创建DefaultResourceLoader对象
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class, // 从spring.factories文件加载org.springframework.boot.env.PropertySourceLoader值对应的PropertySourceLoader实例列表（PropertiesPropertySourceLoader和YamlPropertySourceLoader）
					getClass().getClassLoader());
		}

		void load() { // 加载配置文件属性源到Environment中
			FilteredPropertySource.apply(this.environment, DEFAULT_PROPERTIES, LOAD_FILTERED_PROPERTY,
					(defaultProperties) -> { // 函数式接口，java.util.function.Consumer#accept方法调用，接收的是PropertySource对象
						this.profiles = new LinkedList<>(); // 初始化候选的Profile
						this.processedProfiles = new LinkedList<>(); // 初始化处理过的处理过的Profile
						this.activatedProfiles = false; // 默认设置为没有指定的Profile
						this.loaded = new LinkedHashMap<>(); // 初始化Profile与MutablePropertySources的映射容器
						initializeProfiles(); // 初始化Profile列表（加载Profile并填充到profiles双端队列中）
						while (!this.profiles.isEmpty()) { // 遍历Profile（肯定不为空，最少会有2个）
							Profile profile = this.profiles.poll(); // 获取对头的Profile
							if (isDefaultProfile(profile)) { // 是否为指定的Profile
								addProfileToEnvironment(profile.getName()); // 设置指定的Profile，即将指定的Profile添加到Environment对象的activeProfiles
							}
							load(profile, this::getPositiveProfileFilter, // 加载Profile配置文件并存放到loaded属性中，以后获取配置可以通过Environment获取
									addToLoaded(MutablePropertySources::addLast, false));
							this.processedProfiles.add(profile);
						}
						load(null, this::getNegativeProfileFilter, addToLoaded(MutablePropertySources::addFirst, true)); // 加载application.properties配置文件（如果application.properties中没有配置spring.profiles属性，则不会加载任何内容）
						addLoadedPropertySources(); // 将加载到的配置文件属性配置添加到Environment对象中
						applyActiveProfiles(defaultProperties); // 将指定的profile设置到Environment对象中
					});
		}

		/**
		 * Initialize profile information from both the {@link Environment} active
		 * profiles and any {@code spring.profiles.active}/{@code spring.profiles.include}
		 * properties that are already set.
		 */
		private void initializeProfiles() { // 初始化Profile列表
			// The default profile for these purposes is represented as null. We add it
			// first so that it is processed first and has lowest priority.
			this.profiles.add(null); // 首先添加一个空的Profile，是为了保证能首先处理默认的配置文件
			Set<Profile> activatedViaProperty = getProfilesFromProperty(ACTIVE_PROFILES_PROPERTY); // 从environment中加载spring.profiles.active指定的Profile
			Set<Profile> includedViaProperty = getProfilesFromProperty(INCLUDE_PROFILES_PROPERTY); // 从environment中加载spring.profiles.include指定的Profile
			List<Profile> otherActiveProfiles = getOtherActiveProfiles(activatedViaProperty, includedViaProperty); // 从启动参数中加载spring.profiles.active指定的Profile
			this.profiles.addAll(otherActiveProfiles);
			// Any pre-existing active profiles set via property sources (e.g.
			// System properties) take precedence over those added in config files.
			this.profiles.addAll(includedViaProperty);
			addActiveProfiles(activatedViaProperty); // 给profiles集合添加spring.profiles.active指定的Profile（只会成功添加一次）
			if (this.profiles.size() == 1) { // only has null profile // 获取不到指定的Profile时
				for (String defaultProfileName : this.environment.getDefaultProfiles()) { // 当从environment中获取不到任何指定的Profile时，获取默认指定的Profile，仍获取不到时添加一个默认的Profile
					Profile defaultProfile = new Profile(defaultProfileName, true); // 默认的Profile配置（默认的profile为default）
					this.profiles.add(defaultProfile);
				}
			}
		}

		private Set<Profile> getProfilesFromProperty(String profilesProperty) {
			if (!this.environment.containsProperty(profilesProperty)) { // 当在Environment中获取不到属时，返回一个空集合
				return Collections.emptySet();
			}
			Binder binder = Binder.get(this.environment);
			Set<Profile> profiles = getProfiles(binder, profilesProperty);
			return new LinkedHashSet<>(profiles);
		}

		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty,
				Set<Profile> includedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles()).map(Profile::new).filter(
					(profile) -> !activatedViaProperty.contains(profile) && !includedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		void addActiveProfiles(Set<Profile> profiles) { // 添加激活的Profile（只会成功添加一次）
			if (profiles.isEmpty()) {
				return;
			}
			if (this.activatedProfiles) { // 只有第一次调用时为false
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles + "' will not be applied");
				}
				return;
			}
			this.profiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles " + StringUtils.collectionToCommaDelimitedString(profiles));
			}
			this.activatedProfiles = true; // 第一次进来会修改activatedProfiles的值
			removeUnprocessedDefaultProfiles(); // 如果有激活的Profile，则删除默认的Profile
		}

		private void removeUnprocessedDefaultProfiles() {
			this.profiles.removeIf((profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) { // DocumentFilterFactory接口的函数式接口对应的实现调用
			return (Document document) -> {
				if (profile == null) {
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				return ObjectUtils.containsElement(document.getProfiles(), profile.getName())
						&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) { // DocumentFilterFactory接口的函数式接口对应的实现调用
			return (Document document) -> (profile == null && !ObjectUtils.isEmpty(document.getProfiles())
					&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles())));
		}

		private DocumentConsumer addToLoaded(BiConsumer<MutablePropertySources, PropertySource<?>> addMethod, // DocumentConsumer接口的函数式接口对应的实现调用，将加载到的文档存放入loaded属性
				boolean checkForExisting) {
			return (profile, document) -> {
				if (checkForExisting) { // 是否检查loaded中已存在该文档对应的PropertySource
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile,
						(k) -> new MutablePropertySources());
				addMethod.accept(merged, document.getPropertySource()); // 又是一个函数式接口（添加属性源到队尾）
			};
		}

		private void load(Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			getSearchLocations().forEach((location) -> { // 先调用getSearchLocations方法获取加载配置文件的路径再进行遍历（默认为：file:./config/,classpath:/,classpath:/config/,file:./）
				boolean isFolder = location.endsWith("/");
				Set<String> names = isFolder ? getSearchNames() : NO_SEARCH_NAMES; // 查找配置文件名，默认为application（可以通过spring.config.name指定文件名，在BootstrapApplicationListener类中设置为了bootstarp）
				names.forEach((name) -> load(location, name, profile, filterFactory, consumer)); // 遍历配置文件名，并调用load方法进行加载
			});
		}

		private void load(String location, String name, Profile profile, DocumentFilterFactory filterFactory,
				DocumentConsumer consumer) {
			if (!StringUtils.hasText(name)) { // 配置文件名name默认为application，默认不成立
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) { // 检查配置文件名的后缀是否符合要求：properties、xml、yml或者yaml
						load(loader, location, profile, filterFactory.getDocumentFilter(profile), consumer); // 加载location指定的文件
						return;
					}
				}
				throw new IllegalStateException("File extension of config file location '" + location
						+ "' is not known to any PropertySourceLoader. If the location is meant to reference "
						+ "a directory, it must end in '/'");
			}
			Set<String> processed = new HashSet<>();
			for (PropertySourceLoader loader : this.propertySourceLoaders) { // 遍历PropertySource加载器，propertySource加载器是在Load类的构造方法中初始化的，可以加载文件后缀为properties、xml、yml或者yaml的文件
				for (String fileExtension : loader.getFileExtensions()) { // 遍历加载器支持的文件扩展名
					if (processed.add(fileExtension)) { // 不同的扩展名只会加载一次
						loadForFileExtension(loader, location + name, "." + fileExtension, profile, filterFactory,
								consumer); // 加载具体的配置文件（将路径、文件名、后缀组合起来形成完整文件名）
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name, fileExtension));
		}

		private void loadForFileExtension(PropertySourceLoader loader, String prefix, String fileExtension,
				Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) { // 加载具体的配置文件
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null); // 生成默认的DocumentFilter，用于限制何时加载Document的过滤器
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile); // 生成指定Profile的DocumentFilter，用于限制何时加载Document的过滤器
			if (profile != null) { // 当指定了Profile时
				// Try profile-specific file & profile section in profile file (gh-340)
				String profileSpecificFile = prefix + "-" + profile + fileExtension; // 具体的配置文件名为：配置文件路径+配置文件名+"-"+profile+"."+配置文件扩展名
				load(loader, profileSpecificFile, profile, defaultFilter, consumer); // 加载带profile的配置文件，入参带有过滤器，可以防止重复加载
				load(loader, profileSpecificFile, profile, profileFilter, consumer); // 加载带profile的配置文件，入参带有过滤器，可以防止重复加载
				// Try profile specific sections in files we've already processed
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile + fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);
					}
				}
			}
			// Also try the profile-specific section (if any) of the normal file
			load(loader, prefix + fileExtension, profile, profileFilter, consumer); // 加载不带profile的配置文件
		}

		private void load(PropertySourceLoader loader, String location, Profile profile, DocumentFilter filter, // 加载配置文件最终调用的方法
				DocumentConsumer consumer) {
			try {
				Resource resource = this.resourceLoader.getResource(location); // 调用Resource类加载配置文件
				if (resource == null || !resource.exists()) { // 配置文件资源不存在时直接返回
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped missing config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				if (!StringUtils.hasText(StringUtils.getFilenameExtension(resource.getFilename()))) { // 配置文件扩展名不存在时直接返回
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped empty config extension ", location,
								resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				String name = "applicationConfig: [" + location + "]"; // 属性源的名称
				List<Document> documents = loadDocuments(loader, name, resource); // 读取配置文件内容并将其封装到Document类中，包含PropertySource对象、profiles、activeProfiles、includeProfiles
				if (CollectionUtils.isEmpty(documents)) { // 当documents为空时直接返回
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped unloaded config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				List<Document> loaded = new ArrayList<>();
				for (Document document : documents) { // 遍历Document
					if (filter.match(document)) { // 函数式接口，在ConfigFileApplicationListener.Loader#load()方法中进行设置
						addActiveProfiles(document.getActiveProfiles()); // 将配置文件中配置的spring.profiles.active的值写到集合profiles中
						addIncludedProfiles(document.getIncludeProfiles()); // 将配置文件中配置的spring.profiles.include的值写到集合profiles中
						loaded.add(document);
					}
				}
				Collections.reverse(loaded);
				if (!loaded.isEmpty()) {
					loaded.forEach((document) -> consumer.accept(profile, document)); // 函数式接口，将加载到的文档存放入loaded属性（最终会调用ConfigFileApplicationListener.Loader#addToLoaded方法）
					if (this.logger.isDebugEnabled()) {
						StringBuilder description = getDescription("Loaded config file ", location, resource, profile);
						this.logger.debug(description);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load property source from location '" + location + "'", ex);
			}
		}

		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.profiles);
			this.profiles.clear();
			this.profiles.addAll(includeProfiles);
			this.profiles.removeAll(this.processedProfiles);
			this.profiles.addAll(existingProfiles);
		}

		private List<Document> loadDocuments(PropertySourceLoader loader, String name, Resource resource) // 读取配置文件内容并将其封装到Document类中
				throws IOException {
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource); // 构建缓存Key
			List<Document> documents = this.loadDocumentsCache.get(cacheKey); // 本地缓存
			if (documents == null) {
				List<PropertySource<?>> loaded = loader.load(name, resource); // PropertySource加载器加载指定的配置文件
				documents = asDocuments(loaded); // 将PropertySource属性资源对象列表转换为Document对象列表
				this.loadDocumentsCache.put(cacheKey, documents); // 放入本地缓存
			}
			return documents;
		}

		private List<Document> asDocuments(List<PropertySource<?>> loaded) { // 生成Document列表
			if (loaded == null) {
				return Collections.emptyList();
			}
			return loaded.stream().map((propertySource) -> {
				Binder binder = new Binder(ConfigurationPropertySources.from(propertySource),
						this.placeholdersResolver);
				return new Document(propertySource, binder.bind("spring.profiles", STRING_ARRAY).orElse(null),
						getProfiles(binder, ACTIVE_PROFILES_PROPERTY), getProfiles(binder, INCLUDE_PROFILES_PROPERTY)); // 构建Document对象
			}).collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location, Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet).orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		private void addProfileToEnvironment(String profile) { // 将指定的Profile添加到Environment对象
			for (String activeProfile : this.environment.getActiveProfiles()) { // 遍历集合，如果已经设置过就不再设置
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			this.environment.addActiveProfile(profile); // 设置指定的Profile
		}

		private Set<String> getSearchLocations() { // 获取加载配置文件的路径
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) { // 可以通过spring.config.location配置指定路径，如果没有配置则使用默认路径
				return getSearchLocations(CONFIG_LOCATION_PROPERTY);
			}
			Set<String> locations = getSearchLocations(CONFIG_ADDITIONAL_LOCATION_PROPERTY); // 可以通过spring.config.additional-location配置指定额外的路径
			locations.addAll(
					asResolvedSet(ConfigFileApplicationListener.this.searchLocations, DEFAULT_SEARCH_LOCATIONS)); // 加载默认路径
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			if (this.environment.containsProperty(propertyName)) {
				for (String path : asResolvedSet(this.environment.getProperty(propertyName), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			return locations;
		}

		private Set<String> getSearchNames() { // 获取配置文件名
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) { // 如果指定了spring.config.name，则获取指定的配置文件名（BootstrapApplicationListener中指定为bootstrap）
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				return asResolvedSet(property, null);
			}
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES); // 未指定配置文件名时，使用默认的配置文件名:application
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(StringUtils.commaDelimitedListToStringArray(
					(value != null) ? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list); // 顺序反转
			return new LinkedHashSet<>(list);
		}

		private void addLoadedPropertySources() { // 将配置文件属性源添加到Environment对象中
			MutablePropertySources destination = this.environment.getPropertySources();
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			Collections.reverse(loaded);
			String lastAdded = null;
			Set<String> added = new HashSet<>();
			for (MutablePropertySources sources : loaded) {
				for (PropertySource<?> source : sources) {
					if (added.add(source.getName())) {
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination, String lastAdded,
				PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

		private void applyActiveProfiles(PropertySource<?> defaultProperties) { // 将profile设置到Environment对象中
			List<String> activeProfiles = new ArrayList<>();
			if (defaultProperties != null) {
				Binder binder = new Binder(ConfigurationPropertySources.from(defaultProperties),
						new PropertySourcesPlaceholdersResolver(this.environment));
				activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.include"));
				if (!this.activatedProfiles) {
					activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.active"));
				}
			}
			this.processedProfiles.stream().filter(this::isDefaultProfile).map(Profile::getName)
					.forEach(activeProfiles::add);
			this.environment.setActiveProfiles(activeProfiles.toArray(new String[0]));
		}

		private boolean isDefaultProfile(Profile profile) {
			return profile != null && !profile.isDefaultProfile();
		}

		private List<String> getDefaultProfiles(Binder binder, String property) {
			return binder.bind(property, STRING_LIST).orElse(Collections.emptyList());
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name; // 配置文件名

		private final boolean defaultProfile; // 配置文件是否为默认配置文件，默认为false

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		String getName() {
			return this.name;
		}

		boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader) && this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		private String[] profiles;

		private final Set<Profile> activeProfiles;

		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles, Set<Profile> activeProfiles,
				Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		String[] getProfiles() {
			return this.profiles;
		}

		Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * Filter used to restrict when a {@link Document} is loaded.
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
