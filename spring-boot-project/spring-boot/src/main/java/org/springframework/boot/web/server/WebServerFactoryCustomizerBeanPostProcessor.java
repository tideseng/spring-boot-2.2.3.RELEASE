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

package org.springframework.boot.web.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.util.LambdaSafe;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;

/**
 * {@link BeanPostProcessor} that applies all {@link WebServerFactoryCustomizer} beans
 * from the bean factory to {@link WebServerFactory} beans.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 2.0.0
 */
public class WebServerFactoryCustomizerBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware { // 在ServletWebServerFactoryAutoConfiguration.BeanPostProcessorsRegistrar中进行注册

	private ListableBeanFactory beanFactory;

	private List<WebServerFactoryCustomizer<?>> customizers; // 容器中的所有WebServerFactoryCustomizer集合

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		Assert.isInstanceOf(ListableBeanFactory.class, beanFactory,
				"WebServerCustomizerBeanPostProcessor can only be used with a ListableBeanFactory");
		this.beanFactory = (ListableBeanFactory) beanFactory;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException { // 初始化前置操作
		if (bean instanceof WebServerFactory) { // 当前初始化的对象为WebServerFactory类型时，遍历所有WebServerFactoryCustomizer并调用customize方法对WebServerFactory进行自定义操作
			postProcessBeforeInitialization((WebServerFactory) bean); // 初始化前置操作
		}
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@SuppressWarnings("unchecked")
	private void postProcessBeforeInitialization(WebServerFactory webServerFactory) { // 初始化前置操作
		LambdaSafe.callbacks(WebServerFactoryCustomizer.class, getCustomizers(), webServerFactory) // 获取所有WebServerFactoryCustomizer，并进行遍历，调用其customize方法
				.withLogger(WebServerFactoryCustomizerBeanPostProcessor.class)
				.invoke((customizer) -> customizer.customize(webServerFactory)); // 调用WebServerFactoryCustomizer#customize方法
	}

	private Collection<WebServerFactoryCustomizer<?>> getCustomizers() { // 获取所有WebServerFactoryCustomize
		if (this.customizers == null) {
			// Look up does not include the parent context
			this.customizers = new ArrayList<>(getWebServerFactoryCustomizerBeans()); // 从容器中获取所有WebServerFactoryCustomize
			this.customizers.sort(AnnotationAwareOrderComparator.INSTANCE); // 排序
			this.customizers = Collections.unmodifiableList(this.customizers); // 封装成不可变集合
		}
		return this.customizers;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Collection<WebServerFactoryCustomizer<?>> getWebServerFactoryCustomizerBeans() { // 从容器中获取所有WebServerFactoryCustomize
		return (Collection) this.beanFactory.getBeansOfType(WebServerFactoryCustomizer.class, false, false).values();
	}

}
