package com.example.announcement_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
@Slf4j
public class RabbitMQConfig {

    @Value("${results.rabbitmq.exchange}")
    private String exchange;

    @Value("${results.rabbitmq.queue}")
    private String queue;

    @Value("${results.rabbitmq.routing-key}")
    private String routingKey;

    // Dead Letter Queue configuration
    public static final String DLX_EXCHANGE = "results.dlx.exchange";
    public static final String DLQ_QUEUE = "results.fetch.dlq";
    public static final String DLQ_ROUTING_KEY = "results.fetch.dead";

    // Delayed Retry Queue configuration
    // Messages wait here for 5 hours, then automatically move back to main queue
    public static final String RETRY_QUEUE = "results.fetch.retry";
    public static final String RETRY_ROUTING_KEY = "results.fetch.retry";
    public static final long RETRY_DELAY_MS = 5 * 60 * 60 * 1000L; // 5 hours in milliseconds

    @Bean
    public DirectExchange resultsExchange() {
        return new DirectExchange(exchange);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue resultsQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-message-ttl", 604800000) // 7 days TTL
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE)
                .withArgument("x-message-ttl", 2592000000L) // 30 days TTL for DLQ
                .build();
    }

    /**
     * Delayed retry queue - messages wait here for RETRY_DELAY_MS (5 hours),
     * then automatically dead-letter back to the main queue for reprocessing.
     */
    @Bean
    public Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", RETRY_DELAY_MS) // 5 hours
                .withArgument("x-dead-letter-exchange", exchange) // Send back to main exchange
                .withArgument("x-dead-letter-routing-key", routingKey) // With main routing key
                .build();
    }

    @Bean
    public Binding resultsBinding(Queue resultsQueue, DirectExchange resultsExchange) {
        return BindingBuilder.bind(resultsQueue)
                .to(resultsExchange)
                .with(routingKey);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding retryBinding(Queue retryQueue, DirectExchange resultsExchange) {
        return BindingBuilder.bind(retryQueue)
                .to(resultsExchange)
                .with(RETRY_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Register Java 8 date/time module for LocalDateTime serialization
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("=== RabbitMQ Initialization ===");
        try {
            RabbitAdmin admin = event.getApplicationContext().getBean(RabbitAdmin.class);

            // Get exchanges
            DirectExchange mainExchange = event.getApplicationContext().getBean("resultsExchange", DirectExchange.class);
            DirectExchange dlxExchange = event.getApplicationContext().getBean("deadLetterExchange", DirectExchange.class);

            // Get queues
            Queue mainQueue = event.getApplicationContext().getBean("resultsQueue", Queue.class);
            Queue dlq = event.getApplicationContext().getBean("deadLetterQueue", Queue.class);
            Queue retry = event.getApplicationContext().getBean("retryQueue", Queue.class);

            // Force declare exchanges
            admin.declareExchange(mainExchange);
            log.info("Declared exchange: {}", mainExchange.getName());

            admin.declareExchange(dlxExchange);
            log.info("Declared exchange: {}", dlxExchange.getName());

            // Force declare queues
            admin.declareQueue(mainQueue);
            log.info("Declared queue: {}", mainQueue.getName());

            admin.declareQueue(dlq);
            log.info("Declared queue: {}", dlq.getName());

            admin.declareQueue(retry);
            log.info("Declared queue: {} (5 hour delay)", retry.getName());

            log.info("=== RabbitMQ Setup Complete ===");
        } catch (Exception e) {
            log.error("Failed to initialize RabbitMQ: {}", e.getMessage(), e);
        }
    }
}
