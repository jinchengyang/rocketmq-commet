/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.client.impl.consumer;

import com.alibaba.rocketmq.client.consumer.DefaultMQPushConsumer;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.client.hook.ConsumeMessageContext;
import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.client.stat.ConsumerStatsManager;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.ThreadFactoryImpl;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.body.CMResult;
import com.alibaba.rocketmq.common.protocol.body.ConsumeMessageDirectlyResult;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;


/**
 * @author shijia.wxr
 */
public class ConsumeMessageConcurrentlyService implements ConsumeMessageService {
    private static final Logger log = ClientLogger.getLog();
    private final DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;
    private final DefaultMQPushConsumer defaultMQPushConsumer;
    private final MessageListenerConcurrently messageListener;
    private final BlockingQueue<Runnable> consumeRequestQueue;
    private final ThreadPoolExecutor consumeExecutor;
    private final String consumerGroup;

    private final ScheduledExecutorService scheduledExecutorService;


    public ConsumeMessageConcurrentlyService(DefaultMQPushConsumerImpl defaultMQPushConsumerImpl,
                                             MessageListenerConcurrently messageListener) {
        this.defaultMQPushConsumerImpl = defaultMQPushConsumerImpl;
        this.messageListener = messageListener;

        this.defaultMQPushConsumer = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer();
        this.consumerGroup = this.defaultMQPushConsumer.getConsumerGroup();
        this.consumeRequestQueue = new LinkedBlockingQueue<Runnable>();

        /***
         * 消费消息的线程池
         */
        this.consumeExecutor = new ThreadPoolExecutor(//
                this.defaultMQPushConsumer.getConsumeThreadMin(),//
                this.defaultMQPushConsumer.getConsumeThreadMax(),//
                1000 * 60,//
                TimeUnit.MILLISECONDS,//
                this.consumeRequestQueue,//
                new ThreadFactoryImpl("ConsumeMessageThread_"));

        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("ConsumeMessageScheduledThread_"));
    }


    public void start() {
    }


    public void shutdown() {
        this.scheduledExecutorService.shutdown();
        this.consumeExecutor.shutdown();
    }


    public ConsumerStatsManager getConsumerStatsManager() {
        return this.defaultMQPushConsumerImpl.getConsumerStatsManager();
    }

    /**
     * 消息消费服务
     * <p>
     * 消息消费的业务逻辑都在这里被调用
     */
    class ConsumeRequest implements Runnable {
        private final List<MessageExt> msgs;
        private final ProcessQueue processQueue;
        private final MessageQueue messageQueue;


        /**
         * @param msgs         需要被处理的消息
         * @param processQueue 处理队列
         * @param messageQueue 消息队列
         */
        public ConsumeRequest(List<MessageExt> msgs, ProcessQueue processQueue, MessageQueue messageQueue) {
            this.msgs = msgs;
            this.processQueue = processQueue;
            this.messageQueue = messageQueue;
        }


        @Override
        public void run() {
            if (this.processQueue.isDropped()) {
                log.info("the message queue not be able to consume, because it's dropped {}",
                        this.messageQueue);
                return;
            }

            // 消费者启动的时候设置的listener
            MessageListenerConcurrently listener = ConsumeMessageConcurrentlyService.this.messageListener;
            //设置上下文
            ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(messageQueue);
            ConsumeConcurrentlyStatus status = null;

            /**
             * 如果设置了Hook，则需要执行HOOK
             */
            ConsumeMessageContext consumeMessageContext = null;
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext = new ConsumeMessageContext();
                consumeMessageContext.setConsumerGroup(ConsumeMessageConcurrentlyService.this.defaultMQPushConsumer
                        .getConsumerGroup());
                consumeMessageContext.setMq(messageQueue);
                consumeMessageContext.setMsgList(msgs);
                consumeMessageContext.setSuccess(false);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl
                        .executeHookBefore(consumeMessageContext);
            }

            long beginTimestamp = System.currentTimeMillis();

            try {

                /**
                 * 如果消息的属性中含有重试Topic
                 * 则把消息的Topic设置成对应的重试Topic
                 */
                ConsumeMessageConcurrentlyService.this.resetRetryTopic(msgs);

                /**
                 * 消费消息
                 * 真正的业务逻辑处理在这里被调用
                 */
                status = listener.consumeMessage(Collections.unmodifiableList(msgs), context);
            } catch (Throwable e) {
                log.warn("consumeMessage exception: {} Group: {} Msgs: {} MQ: {}",//
                        RemotingHelper.exceptionSimpleDesc(e),//
                        ConsumeMessageConcurrentlyService.this.consumerGroup,//
                        msgs,//
                        messageQueue);
            }

            long consumeRT = System.currentTimeMillis() - beginTimestamp;

            /**
             * 如果消费过程中抛出了异常，status为RECONSUME_LATER
             */
            if (null == status) {
                log.warn("consumeMessage return null, Group: {} Msgs: {} MQ: {}",//
                        ConsumeMessageConcurrentlyService.this.consumerGroup,//
                        msgs,//
                        messageQueue);
                status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }

            /***
             * 执行HOOK
             */
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext.setStatus(status.toString());
                consumeMessageContext.setSuccess(ConsumeConcurrentlyStatus.CONSUME_SUCCESS == status);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl
                        .executeHookAfter(consumeMessageContext);
            }

            /**
             * 统计数据
             */
            ConsumeMessageConcurrentlyService.this.getConsumerStatsManager().incConsumeRT(
                    ConsumeMessageConcurrentlyService.this.consumerGroup, messageQueue.getTopic(), consumeRT);

            /**
             * 处理执行结果
             */
            if (!processQueue.isDropped()) {
                ConsumeMessageConcurrentlyService.this.processConsumeResult(status, context, this);
            } else {
                log.warn("processQueue is dropped without process consume result. messageQueue={}, msgs={}",
                        messageQueue, msgs);
            }
        }


        public List<MessageExt> getMsgs() {
            return msgs;
        }


        public ProcessQueue getProcessQueue() {
            return processQueue;
        }


        public MessageQueue getMessageQueue() {
            return messageQueue;
        }
    }


    /**
     * 把消费失败的消息发送回去
     *
     * @param msg
     * @param context
     * @return
     */
    public boolean sendMessageBack(final MessageExt msg, final ConsumeConcurrentlyContext context) {
        int delayLevel = context.getDelayLevelWhenNextConsume();

        try {
            this.defaultMQPushConsumerImpl.sendMessageBack(msg, delayLevel, context.getMessageQueue().getBrokerName());
            return true;
        } catch (Exception e) {
            log.error("sendMessageBack exception, group: " + this.consumerGroup + " msg: " + msg.toString(),
                    e);
        }

        return false;
    }


    /**
     * 处理消息消费结果
     *
     * @param status         消息消费状态
     * @param context        上下文数据
     * @param consumeRequest
     */
    public void processConsumeResult(//
                                     final ConsumeConcurrentlyStatus status, //
                                     final ConsumeConcurrentlyContext context, //
                                     final ConsumeRequest consumeRequest//
    ) {


        /**
         * ack用来标记消费成功失败的位置，ackIndex位置后面的数据都是处理失败的
         *
         * 所以，可以通过设置context的ackIndex来标记哪些消息成功了，哪些失败了
         *
         */
        int ackIndex = context.getAckIndex();

        //如果没有消息则不处理
        if (consumeRequest.getMsgs().isEmpty())
            return;

        switch (status) {
            case CONSUME_SUCCESS:
                /**
                 * 消费成功
                 */

                if (ackIndex >= consumeRequest.getMsgs().size()) {
                    ackIndex = consumeRequest.getMsgs().size() - 1;
                }

                //成功数量
                int ok = ackIndex + 1;
                //失败数量
                int failed = consumeRequest.getMsgs().size() - ok;

                /**
                 * 统计
                 */
                this.getConsumerStatsManager().incConsumeOKTPS(consumerGroup, consumeRequest.getMessageQueue().getTopic(), ok);
                this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup, consumeRequest.getMessageQueue().getTopic(), failed);
                break;
            case RECONSUME_LATER:
                /**
                 * 消费发生了异常：
                 * 1. 失败统计
                 * 2. ACK设置为-1，为下面的逻辑使用
                 */
                ackIndex = -1;
                this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup,
                        consumeRequest.getMessageQueue().getTopic(), consumeRequest.getMsgs().size());
                break;
            default:
                break;
        }

        switch (this.defaultMQPushConsumer.getMessageModel()) {
            case BROADCASTING:
                /**
                 * ackIndex后面的数据都是处理失败的
                 * 广播模式下只打印日志
                 */
                for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
                    MessageExt msg = consumeRequest.getMsgs().get(i);
                    log.warn("BROADCASTING, the message consume failed, drop it, {}", msg.toString());
                }
                break;
            case CLUSTERING:
                /**
                 * ackIndex后面的数据都是处理失败的，
                 * 处理失败了把消息重新发回去
                 */
                List<MessageExt> msgBackFailed = new ArrayList<MessageExt>(consumeRequest.getMsgs().size());
                for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
                    MessageExt msg = consumeRequest.getMsgs().get(i);

                    /**
                     * 把消费失败的消息发送回去
                     */
                    boolean result = this.sendMessageBack(msg, context);
                    if (!result) {

                        msg.setReconsumeTimes(msg.getReconsumeTimes() + 1);
                        msgBackFailed.add(msg);
                    }
                }

                /**
                 * 如果发送回去的时候有发送失败的
                 * 则把这些失败的过会儿再消费一次
                 */
                if (!msgBackFailed.isEmpty()) {
                    consumeRequest.getMsgs().removeAll(msgBackFailed);

                    this.submitConsumeRequestLater(msgBackFailed, consumeRequest.getProcessQueue(),
                            consumeRequest.getMessageQueue());
                }
                break;
            default:
                break;
        }

        /**
         * ProcessQueue中移除已经处理过的消息
         * 返回Offset
         */
        long offset = consumeRequest.getProcessQueue().removeMessage(consumeRequest.getMsgs());
        if (offset >= 0) {
            // Offset更新到本地
            this.defaultMQPushConsumerImpl.getOffsetStore().updateOffset(consumeRequest.getMessageQueue(), offset, true);
        }
    }


    private void submitConsumeRequestLater(//
                                           final List<MessageExt> msgs, //
                                           final ProcessQueue processQueue, //
                                           final MessageQueue messageQueue//
    ) {

        this.scheduledExecutorService.schedule(new Runnable() {

            @Override
            public void run() {
                ConsumeMessageConcurrentlyService.this.submitConsumeRequest(msgs, processQueue, messageQueue,
                        true);
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }


    @Override
    public void submitConsumeRequest(//
                                     final List<MessageExt> msgs, //
                                     final ProcessQueue processQueue, //
                                     final MessageQueue messageQueue, //
                                     final boolean dispatchToConsume) {

        final int consumeBatchSize = this.defaultMQPushConsumer.getConsumeMessageBatchMaxSize();
//        System.err.println("batch:" + consumeBatchSize + ",msg:" + msgs.size());
        /**
         * 如果拉取到的消息超过了设置的消费者最大批处理数量，则分批次提交到线程池，确保每次提交的消息数量少于最大批处理数量
         */
        if (msgs.size() <= consumeBatchSize) {
            ConsumeRequest consumeRequest = new ConsumeRequest(msgs, processQueue, messageQueue);
            this.consumeExecutor.submit(consumeRequest);
        } else {
            for (int total = 0; total < msgs.size(); ) {
                List<MessageExt> msgThis = new ArrayList<MessageExt>(consumeBatchSize);
                for (int i = 0; i < consumeBatchSize; i++, total++) {
                    if (total < msgs.size()) {
                        msgThis.add(msgs.get(total));
                    } else {
                        break;
                    }
                }

                ConsumeRequest consumeRequest = new ConsumeRequest(msgThis, processQueue, messageQueue);
                this.consumeExecutor.submit(consumeRequest);
            }
        }
    }


    @Override
    public void updateCorePoolSize(int corePoolSize) {
        if (corePoolSize > 0 //
                && corePoolSize <= Short.MAX_VALUE //
                && corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax()) {
            this.consumeExecutor.setCorePoolSize(corePoolSize);
        }
    }


    @Override
    public void incCorePoolSize() {
//        long corePoolSize = this.consumeExecutor.getCorePoolSize();
//        if (corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax()) {
//            this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize() + 1);
//        }
//
//        log.info("incCorePoolSize Concurrently from {} to {}, ConsumerGroup: {}", //
//            corePoolSize,//
//            this.consumeExecutor.getCorePoolSize(),//
//            this.consumerGroup);
    }


    @Override
    public void decCorePoolSize() {
//        long corePoolSize = this.consumeExecutor.getCorePoolSize();
//        if (corePoolSize > this.defaultMQPushConsumer.getConsumeThreadMin()) {
//            this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize() - 1);
//        }
//
//        log.info("decCorePoolSize Concurrently from {} to {}, ConsumerGroup: {}", //
//            corePoolSize,//
//            this.consumeExecutor.getCorePoolSize(),//
//            this.consumerGroup);
    }


    @Override
    public int getCorePoolSize() {
        return this.consumeExecutor.getCorePoolSize();
    }


    @Override
    public ConsumeMessageDirectlyResult consumeMessageDirectly(MessageExt msg, String brokerName) {
        ConsumeMessageDirectlyResult result = new ConsumeMessageDirectlyResult();
        result.setOrder(false);
        result.setAutoCommit(true);

        List<MessageExt> msgs = new ArrayList<MessageExt>();
        msgs.add(msg);
        MessageQueue mq = new MessageQueue();
        mq.setBrokerName(brokerName);
        mq.setTopic(msg.getTopic());
        mq.setQueueId(msg.getQueueId());

        ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(mq);

        this.resetRetryTopic(msgs);

        final long beginTime = System.currentTimeMillis();

        log.info("consumeMessageDirectly receive new messge: {}", msg);

        try {
            ConsumeConcurrentlyStatus status = this.messageListener.consumeMessage(msgs, context);
            if (status != null) {
                switch (status) {
                    case CONSUME_SUCCESS:
                        result.setConsumeResult(CMResult.CR_SUCCESS);
                        break;
                    case RECONSUME_LATER:
                        result.setConsumeResult(CMResult.CR_LATER);
                        break;
                    default:
                        break;
                }
            } else {
                result.setConsumeResult(CMResult.CR_RETURN_NULL);
            }
        } catch (Throwable e) {
            result.setConsumeResult(CMResult.CR_THROW_EXCEPTION);
            result.setRemark(RemotingHelper.exceptionSimpleDesc(e));

            log.warn(String.format("consumeMessageDirectly exception: %s Group: %s Msgs: %s MQ: %s",//
                    RemotingHelper.exceptionSimpleDesc(e),//
                    ConsumeMessageConcurrentlyService.this.consumerGroup,//
                    msgs,//
                    mq), e);
        }

        result.setSpentTimeMills(System.currentTimeMillis() - beginTime);

        log.info("consumeMessageDirectly Result: {}", result);

        return result;
    }


    public void resetRetryTopic(final List<MessageExt> msgs) {
        final String groupTopic = MixAll.getRetryTopic(consumerGroup);
        for (MessageExt msg : msgs) {
            String retryTopic = msg.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
            if (retryTopic != null && groupTopic.equals(msg.getTopic())) {
                msg.setTopic(retryTopic);
            }
        }
    }
}
