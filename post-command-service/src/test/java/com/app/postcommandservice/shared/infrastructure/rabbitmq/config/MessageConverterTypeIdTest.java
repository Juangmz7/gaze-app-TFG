package com.app.postcommandservice.shared.infrastructure.rabbitmq.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import com.app.postcommandservice.like.application.commands.ValidatePostLikeCommand;
import com.app.postcommandservice.post.infrastructure.events.UserFollowedEvent;

import static org.assertj.core.api.Assertions.assertThat;

class MessageConverterTypeIdTest {

    private final JacksonJsonMessageConverter converter =
            (JacksonJsonMessageConverter) new RabbitMQConfig(new RabbitMQProperties()).messageConverter();

    @Test
    void shouldResolveOwnOutboxTypeIdToCommandClass() {
        assertThat(converter.getJavaTypeMapper().toClass(propertiesWithTypeId("ValidatePostLikeCommand")))
                .isEqualTo(ValidatePostLikeCommand.class);
    }

    @Test
    void shouldResolveSocialServiceTypeIdToLocalEventClass() {
        assertThat(converter.getJavaTypeMapper().toClass(propertiesWithTypeId("UserFollowedEvent")))
                .isEqualTo(UserFollowedEvent.class);
    }

    private static MessageProperties propertiesWithTypeId(String typeId) {
        var props = new MessageProperties();
        props.setHeader(DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, typeId);
        return props;
    }
}
