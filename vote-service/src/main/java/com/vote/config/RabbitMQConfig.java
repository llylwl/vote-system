package com.vote.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 核心配置类
 * 定义投票主队列（持久化 + 绑定死信）、交换机、绑定关系以及消费者监听容器工厂
 * @author hzp
 * @since 2026-9-15
 */
@Configuration
public class RabbitMQConfig {

    /** 投票主队列名称 */
    public static final String VOTE_MAIN_QUEUE = "vote.persist.queue";

    /** 投票交换机名称 */
    public static final String VOTE_EXCHANGE = "vote.exchange";

    /** 投票路由键 */
    public static final String VOTE_ROUTING_KEY = "vote.persist";

    /** 死信交换机名称 */
    public static final String VOTE_DL_EXCHANGE = "vote.dlx.exchange";

    /** 死信队列名称 */
    public static final String VOTE_DL_QUEUE = "vote.persist.dlq";

    /** 死信路由键 */
    public static final String VOTE_DL_ROUTING_KEY = "vote.persist.dlq";

    /** 投票主队列：持久化 + 绑定死信交换机 */
    @Bean
    public Queue voteMainQueue() {
        return QueueBuilder.durable(VOTE_MAIN_QUEUE)
                .withArgument("x-dead-letter-exchange", VOTE_DL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", VOTE_DL_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange voteExchange() {
        return new DirectExchange(VOTE_EXCHANGE, true, false);
    }

    @Bean
    public Binding voteBinding() {
        return BindingBuilder.bind(voteMainQueue()).to(voteExchange()).with(VOTE_ROUTING_KEY);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(VOTE_DL_QUEUE).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(VOTE_DL_EXCHANGE, true, false);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(VOTE_DL_ROUTING_KEY);
    }

    /** RabbitAdmin：用于运行时查询队列状态（消息积压 / 消费者数） */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    /**
     * 自定义 RabbitMQ 监听容器工厂
     * 配置并发消费者数、预取数量以及手动 ACK 模式，实现平滑消费
     */
    @Bean
    public SimpleRabbitListenerContainerFactory voteRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 并发消费者数量：最小 5，最大 10
        factory.setConcurrentConsumers(5);
        factory.setMaxConcurrentConsumers(10);
        // 每次从队列领取 1 条消息，处理完再取下一条
        factory.setPrefetchCount(1);
        // 手动 ACK
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
