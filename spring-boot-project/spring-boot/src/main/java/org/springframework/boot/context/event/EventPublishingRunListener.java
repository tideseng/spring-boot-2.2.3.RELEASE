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

package org.springframework.boot.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @since 1.0.0
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered { // SpringApplicationRunListener接口的默认实现，对运行各阶段的方法发布相应的事件

	private final SpringApplication application; // SpringApplication对象

	private final String[] args; // 指定传入的参数

	private final SimpleApplicationEventMulticaster initialMulticaster; // 事件广播器

	public EventPublishingRunListener(SpringApplication application, String[] args) { // 初始化EventPublishingRunListener，注入SpringApplication对象，调用时机在SpringApplication#getRunListeners(...)
		this.application = application;
		this.args = args;
		this.initialMulticaster = new SimpleApplicationEventMulticaster(); // 创建SimpleApplicationEventMulticaster
		for (ApplicationListener<?> listener : application.getListeners()) { // 遍历SpringApplication在初始化阶段设置的事件监听器
			this.initialMulticaster.addApplicationListener(listener); // 将SpringApplication在初始化阶段设置的事件监听器添加到事件广播器中
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void starting() { // Spring应用刚启动
		this.initialMulticaster.multicastEvent(new ApplicationStartingEvent(this.application, this.args)); // 广播ApplicationStartingEvent事件
	}

	@Override
	public void environmentPrepared(ConfigurableEnvironment environment) { // ConfigurableEnvironment准备完毕，允许将其调整
		this.initialMulticaster
				.multicastEvent(new ApplicationEnvironmentPreparedEvent(this.application, this.args, environment)); // 广播ApplicationEnvironmentPreparedEvent事件
	}

	@Override
	public void contextPrepared(ConfigurableApplicationContext context) { // ConfigurableApplicationContext准备完毕，允许将其调整
		this.initialMulticaster
				.multicastEvent(new ApplicationContextInitializedEvent(this.application, this.args, context)); // 广播ApplicationContextInitializedEvent事件
	}

	@Override
	public void contextLoaded(ConfigurableApplicationContext context) { // ConfigurableApplicationContext已装载，但还未启动
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		this.initialMulticaster.multicastEvent(new ApplicationPreparedEvent(this.application, this.args, context)); // 广播ApplicationPreparedEvent事件
	}

	@Override
	public void started(ConfigurableApplicationContext context) { // ConfigurableApplicationContext已启动，此时Spring Bean已初始化完成
		context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context)); // 广播ApplicationStartedEvent事件
	}

	@Override
	public void running(ConfigurableApplicationContext context) { // Spring应用正在运行
		context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context));
	}

	@Override
	public void failed(ConfigurableApplicationContext context, Throwable exception) { // Spring应用运行失败
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application, this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event); // 广播ApplicationFailedEvent事件
		}
		else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static final Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
