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
 * <p>
 * 拓扑结构：
 * <pre>
 *   vote.exchange ──(vote.persist)──▶ vote.persist.queue ──(消费失败重发)──┐
 *        ▲                                                                │
 *        │                                                                ▼
 *        └──(vote.persist，延迟 TTL 到期后回流)── vote.persist.retry ◀──────┘
 *
 *   超过最大重试次数 ──▶ vote.dlx.exchange ──(vote.persist.dlq)──▶ vote.persist.dlq
 * </pre>
 * <p>
 * <b>为什么不用 basicNack(requeue=true) 做重试：</b>
 * 该方式由 Broker 重投【原始消息】，消费者在本地修改的 header 不会保存到 Broker，
 * 导致重试计数永远是 1，永远到不了上限，形成热循环且死信队列一条都收不到。
 * 这里改为「消费失败 → 显式重发一条附带了重试计数的新消息 → ACK 原消息」，
 * 计数随新消息持久化，并在重试队列中延迟后回流，天然带退避。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Configuration
public class RabbitMQConfig {

    /** 投票主队列名称 */
    public static final String VOTE_MAIN_QUEUE = "vote.persist.queue";

    /** 投票交换机名称 */
    public static final String VOTE_EXCHANGE = "vote.exchange";

    /** 投票路由键 */
    public static final String VOTE_ROUTING_KEY = "vote.persist";

    /** 重试队列名称（带 TTL，消息在其内延迟后自动回流主队列） */
    public static final String VOTE_RETRY_QUEUE = "vote.persist.retry";

    /** 重试路由键 */
    public static final String VOTE_RETRY_ROUTING_KEY = "vote.persist.retry";

    /** 重试延迟（毫秒）：失败后等待多久再重投 */
    public static final int VOTE_RETRY_TTL_MS = 5000;

    /** 死信交换机名称 */
    public static final String VOTE_DL_EXCHANGE = "vote.dlx.exchange";

    /** 死信队列名称（重试耗尽后的最终归属，由 DeadLetterConsumer 落库） */
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

    /**
     * 重试队列：消息在此滞留 {@link #VOTE_RETRY_TTL_MS} 毫秒后，
     * 由 Broker 按 x-dead-letter-* 参数自动回流到主交换机，重新进入主队列。
     */
    @Bean
    public Queue voteRetryQueue() {
        return QueueBuilder.durable(VOTE_RETRY_QUEUE)
                .withArgument("x-message-ttl", VOTE_RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", VOTE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", VOTE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding voteRetryBinding() {
        return BindingBuilder.bind(voteRetryQueue()).to(voteExchange()).with(VOTE_RETRY_ROUTING_KEY);
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
     * 配置并发消费者数、预取数量以及手动 ACK 模式，实现平滑消费。
     * <p>
     * 注意：使用本工厂的监听器不受 application.yml 中 spring.rabbitmq.listener.simple.* 影响。
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
        // 死信消费者同样使用手动 ACK
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
