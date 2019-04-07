package com.sda.weather;

import com.sda.weather.weather.Weather;
import com.sda.weather.weather.WeatherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aggregator.CorrelationStrategy;
import org.springframework.integration.aggregator.MessageCountReleaseStrategy;
import org.springframework.integration.aggregator.MessageGroupProcessor;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.integration.splitter.AbstractMessageSplitter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.GenericMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class IntegrationConfig {

    @Bean
    public IntegrationFlow weatherChecker(WeatherService weatherService, @Value("${mail.gmail.user}") String gmailUser, @Value("${mail.gmail.password}") String gmailPassword, @Value("${mail.address}") String mailAddres) {
        AtomicInteger atomicInteger = new AtomicInteger(0);

        MessageSource<Weather> wroclawWeather = () -> {
            Weather weather = weatherService.getWeather("Wroclaw", "C");
            return new GenericMessage<>(weather, new MessageHeaders(Collections.singletonMap("seq", atomicInteger.getAndIncrement())));
        };

        MessageGroupProcessor temperatureDiff = messageGroup -> {
            ArrayList<Message<?>> messages = new ArrayList<>(messageGroup.getMessages());
            if (messages.size() < 2) {
                return 0;
            }
            int oldTemp = ((Weather) messages.get(0).getPayload()).getTemperature();
            int newTemp = ((Weather) messages.get(1).getPayload()).getTemperature();
            int diff = newTemp - oldTemp;
            return (100 * diff) / oldTemp;
        };

        CorrelationStrategy weatherCorrelation = message -> {
            Weather weather = (Weather) message.getPayload();
            return weather.getLocationName();
        };

        AbstractMessageSplitter weatherDuplication = new AbstractMessageSplitter() {
            @Override
            protected Object splitMessage(Message<?> message) {
                if (message.getHeaders().get("seq").equals(0)) {
                    return message;
                }
                return Arrays.asList(message, message);
            }
        };

        return IntegrationFlows.from(wroclawWeather, configurer -> configurer.poller(Pollers.fixedDelay(2000)))
                .split(weatherDuplication)
                .aggregate(aggregatorSpec -> aggregatorSpec
                        .outputProcessor(temperatureDiff)
                        .correlationStrategy(weatherCorrelation)
                        .releaseStrategy(new MessageCountReleaseStrategy(2))
                        .expireGroupsUponCompletion(true)
                )
                .filter(diff -> Math.abs((Integer) diff) > 5)
                .transform(diff -> "High temperature diff: " + diff)
                .enrichHeaders(Mail.headers()
                        .subject("Weather mail")
                        .to(mailAddres)
                        .from(gmailUser))
                .handle(Mail.outboundAdapter("smtp.gmail.com")
                        .port(465)
                        .protocol("smtps")
                        .credentials(gmailUser, gmailPassword))
                .get();
    }
}