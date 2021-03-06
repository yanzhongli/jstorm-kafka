package storm.kafka;

import backtype.storm.Config;
import backtype.storm.metric.api.CombinedMetric;
import backtype.storm.metric.api.CountMetric;
import backtype.storm.metric.api.MeanReducer;
import backtype.storm.metric.api.ReducedMetric;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.utils.Utils;
import com.google.common.collect.ImmutableMap;
import kafka.api.OffsetRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.MessageAndOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.kafka.KafkaSpout.EmitState;
import storm.kafka.KafkaSpout.MessageAndRealOffset;
import storm.kafka.trident.MaxMetric;

import java.util.*;

public class PartitionManager {
    public static final Logger LOG = LoggerFactory
            .getLogger(PartitionManager.class);
    private final CombinedMetric _fetchAPILatencyMax;
    private final ReducedMetric _fetchAPILatencyMean;
    private final CountMetric _fetchAPICallCount;
    private final CountMetric _fetchAPIMessageCount;

    static class KafkaMessageId {
        public Partition partition;
        public long offset;

        public KafkaMessageId(Partition partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }
    }

    Long _emittedToOffset;// 已经发送的offset
    Object _emittedToOffsetLock = new Object();// 使用synchronized
                                               // statment锁定_emittedToOffset和_pending的对应关系
    SortedSet<Long> _pending = Collections
            .synchronizedSortedSet(new TreeSet<Long>());// _pending的并发访问，未对其做外部同步
    Long _committedTo;// 已经写入zk代表完成发送的offset
    LinkedList<MessageAndRealOffset> _waitingToEmit = new LinkedList<MessageAndRealOffset>();
    Partition _partition;
    SpoutConfig _spoutConfig;
    String _topologyInstanceId;
    SimpleConsumer _consumer;
    DynamicPartitionConnections _connections;
    ZkState _state;
    Map _stormConf;

    public PartitionManager(DynamicPartitionConnections connections,
            String topologyInstanceId, ZkState state, Map stormConf,
            SpoutConfig spoutConfig, Partition id) {
        _partition = id;// 本manager对应的partition
        _connections = connections;// host到kafka客户端映射
        _spoutConfig = spoutConfig;
        _topologyInstanceId = topologyInstanceId;// spout uuid
        _consumer = connections.register(id.host, id.partition);// 注册partition，如果没有建立连接建立连接
        _state = state;
        _stormConf = stormConf;

        String jsonTopologyId = null;
        Long jsonOffset = null;
        String path = committedPath();// 获取zk中记录kafkaspout相关信息的路径
        try {
            Map<Object, Object> json = _state.readJSON(path);// 这个信息是哪个阶段被写入的？
            LOG.info("Read partition information from: " + path + "  --> "
                    + json);
            if (json != null) {
                jsonTopologyId = (String) ((Map<Object, Object>) json
                        .get("topology")).get("id");
                jsonOffset = (Long) json.get("offset");
            }
        } catch (Throwable e) {
            LOG.warn("Error reading and/or parsing at ZkNode: " + path, e);
        }

        if (jsonTopologyId == null || jsonOffset == null) { // failed to parse
                                                            // JSON?第一次启动没有设置的时候？
            _committedTo = KafkaUtils.getOffset(_consumer, spoutConfig.topic,
                    id.partition, spoutConfig);
            LOG.info("No partition information found, using configuration to determine offset");
        } else if (!topologyInstanceId.equals(jsonTopologyId)
                && spoutConfig.forceFromStart) {// 或者Topology变化了且要求重头开始
            _committedTo = KafkaUtils.getOffset(_consumer, spoutConfig.topic,
                    id.partition, spoutConfig.startOffsetTime);
            LOG.info("Topology change detected and reset from start forced, using configuration to determine offset");
        } else {// 否则使用上次的offset
            _committedTo = jsonOffset;
            LOG.info("Read last commit offset from zookeeper: " + _committedTo
                    + "; old topology_id: " + jsonTopologyId
                    + " - new topology_id: " + topologyInstanceId);
        }

        LOG.info("Starting Kafka " + _consumer.host() + ":" + id.partition
                + " from offset " + _committedTo);
        _emittedToOffset = _committedTo;

        // 一些度量，用来统计运行状态
        _fetchAPILatencyMax = new CombinedMetric(new MaxMetric());
        _fetchAPILatencyMean = new ReducedMetric(new MeanReducer());
        _fetchAPICallCount = new CountMetric();
        _fetchAPIMessageCount = new CountMetric();
    }

    public Map getMetricsDataMap() {
        Map ret = new HashMap();
        ret.put(_partition + "/fetchAPILatencyMax",
                _fetchAPILatencyMax.getValueAndReset());
        ret.put(_partition + "/fetchAPILatencyMean",
                _fetchAPILatencyMean.getValueAndReset());
        ret.put(_partition + "/fetchAPICallCount",
                _fetchAPICallCount.getValueAndReset());
        ret.put(_partition + "/fetchAPIMessageCount",
                _fetchAPIMessageCount.getValueAndReset());
        return ret;
    }

    // returns false if it's reached the end of current batch
    public EmitState next(SpoutOutputCollector collector) {
        if (_waitingToEmit.isEmpty()) {
            fill();
        }
        while (true) {
            MessageAndRealOffset toEmit = _waitingToEmit.pollFirst();
            if (toEmit == null) {
                return EmitState.NO_EMITTED;
            }
            // 对于kafka中的每条消息可以生成多个tuples
            Iterable<List<Object>> tups = KafkaUtils.generateTuples(
                    _spoutConfig, toEmit.msg);
            if (tups != null) {
                for (List<Object> tup : tups) {
                    // 输出tuple
                    collector.emit(tup, new KafkaMessageId(_partition,
                            toEmit.offset));
                }
                // 发送出一条kafka消息后便完成输出
                break;
            } else {
                // 如果从kafka消息中未能解析出tuple则直接确认该消息完成
                ack(toEmit.offset);
            }
        }
        if (!_waitingToEmit.isEmpty()) {
            return EmitState.EMITTED_MORE_LEFT;
        } else {
            return EmitState.EMITTED_END;
        }
    }

    private void fill() {
        // 从kafka broker获取数据
        long start = System.nanoTime();
        synchronized (_emittedToOffsetLock) {// 多线程下_pending和_emittedToOffset一致
            ByteBufferMessageSet msgs = KafkaUtils.fetchMessages(_spoutConfig,
                    _consumer, _partition, _emittedToOffset);
            long end = System.nanoTime();
            long millis = (end - start) / 1000000;
            _fetchAPILatencyMax.update(millis);
            _fetchAPILatencyMean.update(millis);
            _fetchAPICallCount.incr();
            int numMessages = countMessages(msgs);
            _fetchAPIMessageCount.incrBy(numMessages);

            if (numMessages > 0) {
                LOG.info("Fetched " + numMessages + " messages from Kafka: "
                        + _consumer.host() + ":" + _partition.partition);
            }
            for (MessageAndOffset msg : msgs) {
                // 对于每条读出的数据，将offset加入pending，把消息本身加入_waitingToEmit链表
                _pending.add(_emittedToOffset);// 表示已从kafka读出数据，等待spout ack被调用
                _waitingToEmit.add(new MessageAndRealOffset(msg.message(),
                        _emittedToOffset));
                _emittedToOffset = msg.nextOffset();
            }

            if (numMessages > 0) {
                LOG.info("Added " + numMessages + " messages from Kafka: "
                        + _consumer.host() + ":" + _partition.partition
                        + " to internal buffers");
            }
        }
    }

    private int countMessages(ByteBufferMessageSet messageSet) {
        int counter = 0;
        for (MessageAndOffset messageAndOffset : messageSet) {
            counter = counter + 1;
        }
        return counter;
    }

    public void ack(Long offset) {
        _pending.remove(offset);
    }

    // 如果一个offset失败，则认为其后的offset全部失败，都需要重发
    public void fail(Long offset) {
        // TODO: should it use in-memory ack set to skip anything that's been
        // acked but not committed???
        // things might get crazy with lots of timeouts
        synchronized (_emittedToOffsetLock) {// 多线程下_pending和_emittedToOffset一致
            if (_emittedToOffset > offset) {
                _emittedToOffset = offset;
                _pending.tailSet(offset).clear();
            }
        }
    }

    public void commit() {
        LOG.info("Committing offset for " + _partition);
        long committedTo;
        committedTo = lastCompletedOffset();// 调用此函数和原代码一样功能，synchronized
                                            // statement保护_pending和_emittedToOffset一致
        if (committedTo != _committedTo) {
            LOG.info("Writing committed offset to ZK: " + committedTo);

            Map<Object, Object> data = (Map<Object, Object>) ImmutableMap
                    .builder()
                    .put("topology",
                            ImmutableMap.of("id", _topologyInstanceId, "name",
                                    _stormConf.get(Config.TOPOLOGY_NAME)))
                    .put("offset", committedTo)
                    .put("partition", _partition.partition)
                    .put("broker",
                            ImmutableMap.of("host", _partition.host.host,
                                    "port", _partition.host.port))
                    .put("topic", _spoutConfig.topic).build();
            _state.writeJSON(committedPath(), data);

            LOG.info("Wrote committed offset to ZK: " + committedTo);
            _committedTo = committedTo;
        }
        LOG.info("Committed offset " + committedTo + " for " + _partition
                + " for topology: " + _topologyInstanceId);
    }

    private String committedPath() {
        return _spoutConfig.zkRoot + "/" + _spoutConfig.id + "/"
                + _partition.getId();
    }

    public long queryPartitionOffsetLatestTime() {
        return KafkaUtils.getOffset(_consumer, _spoutConfig.topic,
                _partition.partition, OffsetRequest.LatestTime());
    }

    public long lastCommittedOffset() {
        return _committedTo;
    }

    public long lastCompletedOffset() {
        synchronized (_emittedToOffsetLock) {// 多线程下_pending和_emittedToOffset一致
            if (_pending.isEmpty()) {
                return _emittedToOffset;
            } else {
                return _pending.first();
            }
        }
    }

    public Partition getPartition() {
        return _partition;
    }

    public void close() {
        _connections.unregister(_partition.host, _partition.partition);
    }
}
