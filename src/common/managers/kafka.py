import json
from typing import Optional
from kafka import KafkaProducer, KafkaConsumer


KAFKA_GROUP = "cfg_group_v2"
AUTO_OFFSET_RESET = "earliest"

class KafkaProducerManager:
    def __init__(self, kafka_broker: str):
        self.producer = KafkaProducer(
            bootstrap_servers=kafka_broker,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            key_serializer=lambda k: k.encode("utf-8") if isinstance(k, str) else k,
        )

    def push_to_the_topic(self, topic: str, message: dict, key: Optional[str] = None):
        # Passing a key is critical for parallelism: kafka hashes the key to
        # pick a partition, so a stream of distinct keys distributes evenly
        # across partitions (and therefore evenly across consumer replicas in
        # the same group). Without a key, kafka-python's sticky batching tends
        # to dump consecutive sends onto a single partition, leaving the other
        # cfg-processor replicas idle.
        future = self.producer.send(topic, value=message, key=key)
        return future

    def flush(self):
        self.producer.flush()

class KafkaConsumerManager:
    def __init__(self, kafka_broker: str, kafka_topic: str, group_id: str = KAFKA_GROUP):
        self.consumer = KafkaConsumer(
            kafka_topic,
            bootstrap_servers=kafka_broker,
            auto_offset_reset=AUTO_OFFSET_RESET,
            enable_auto_commit=True,
            group_id=group_id,
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
            # CFG generation for one repo can take many minutes on large
            # codebases (e.g. facebook/react). Each Kafka message == one repo,
            # so we must allow plenty of time between poll() calls or the
            # broker will rebalance the partition away mid-processing and
            # reject the offset commit (CommitFailedError -> duplicate work).
            max_poll_records=1,
            max_poll_interval_ms=1_800_000,
        )

    def get_messages(self):
        while True:
            records = self.consumer.poll(timeout_ms=1000)
            for _, messages in records.items():
                for message in messages:
                    yield message.value
