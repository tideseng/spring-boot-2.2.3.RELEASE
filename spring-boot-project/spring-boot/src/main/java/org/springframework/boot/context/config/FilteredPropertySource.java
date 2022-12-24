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

import java.util.Set;
import java.util.function.Consumer;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Internal {@link PropertySource} implementation used by
 * {@link ConfigFileApplicationListener} to filter out properties for specific operations.
 *
 * @author Phillip Webb
 */
class FilteredPropertySource extends PropertySource<PropertySource<?>> {

	private final Set<String> filteredProperties;

	FilteredPropertySource(PropertySource<?> original, Set<String> filteredProperties) {
		super(original.getName(), original);
		this.filteredProperties = filteredProperties;
	}

	@Override
	public Object getProperty(String name) { // 获取属性，会先进行过滤
		if (this.filteredProperties.contains(name)) { // 如果为要过滤的属性，则进行过滤、返回null
			return null;
		}
		return getSource().getProperty(name);
	}

	static void apply(ConfigurableEnvironment environment, String propertySourceName, Set<String> filteredProperties, // propertySourceName值为defaultProperties
			Consumer<PropertySource<?>> operation) {
		MutablePropertySources propertySources = environment.getPropertySources();
		PropertySource<?> original = propertySources.get(propertySourceName); // 获取Environment中已经设置的defaultProperties默认属性源
		if (original == null) { // 如果没有配置默认属性源
			operation.accept(null); // 函数式接口回调，详见ConfigFileApplicationListener.Loader#load()
			return;
		}
		propertySources.replace(propertySourceName, new FilteredPropertySource(original, filteredProperties)); // 如果配置了默认属性源，则添加过滤属性
		try {
			operation.accept(original); // 函数式接口回调，详见ConfigFileApplicationListener.Loader#load()
		}
		finally {
			propertySources.replace(propertySourceName, original); // 还原默认属性源
		}
	}

}
