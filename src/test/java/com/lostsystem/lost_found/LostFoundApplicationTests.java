package com.lostsystem.lost_found;

import com.lostsystem.aiservice.AiAssistant;
import com.lostsystem.aiservice.AiIntentionAssistant;
import com.lostsystem.aiservice.LostRegisterAssistant;
import com.lostsystem.aiservice.LostPropertyAssistant;
import com.lostsystem.config.ChatConfig;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LostFoundApplicationTests {

	@Autowired
	private ApplicationContext context;
	@Autowired
	private Environment environment;

	@Test
	void contextLoads() {
		assertNotNull(context.getBean(AiIntentionAssistant.class));
		assertNotNull(context.getBean(LostRegisterAssistant.class));
		assertNotNull(context.getBean(LostPropertyAssistant.class));
	}

	@Test
	void deepSeekStreamingModelIsRegisteredOnceAndWiredToAssistant() {
		var models = context.getBeansOfType(StreamingChatModel.class);
		assertEquals(1, models.size());
		assertTrue(models.containsKey(ChatConfig.DEEPSEEK_STREAMING_CHAT_BEAN));
		var model = assertInstanceOf(OpenAiStreamingChatModel.class,
				models.get(ChatConfig.DEEPSEEK_STREAMING_CHAT_BEAN));
		assertEquals(environment.getProperty("langchain4j.open-ai.chat-model.model-name"),
				model.defaultRequestParameters().modelName());
		assertEquals(ChatConfig.DEEPSEEK_STREAMING_CHAT_BEAN,
				AiAssistant.class.getAnnotation(dev.langchain4j.service.spring.AiService.class).streamingChatModel());
		assertEquals(ChatConfig.DEEPSEEK_STREAMING_CHAT_BEAN,
				LostPropertyAssistant.class.getAnnotation(dev.langchain4j.service.spring.AiService.class).streamingChatModel());
		assertNotNull(context.getBean(AiAssistant.class));
	}

}
