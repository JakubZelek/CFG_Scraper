import asyncio
import json
import hashlib
import logging
from datetime import datetime, timezone
from common.managers.kafka import KafkaConsumerManager
from common.managers.elastic import AsyncElasticSearchManager
from common.config.elastic_upload_settings import ElasticUploadSettings

elastic_upload_settings = ElasticUploadSettings()

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger(__name__)


def yield_single_graph_to_be_loaded_to_elasticsearch(cfg_message: str):
    global_template = {
        "filepath": cfg_message["filepath"],
        "commit_hash": cfg_message["commit_hash"],
        "repo_url": cfg_message["repo_url"],
        "language": cfg_message["language"],
    }

    for graph in cfg_message["graph_list"]:
        to_hash = graph["name"] + global_template["repo_url"] + global_template["commit_hash"]
        graph_id = hashlib.md5(to_hash.encode("utf-8")).hexdigest()

        yield graph_id, {**global_template, **graph}


async def upload_to_elasticsearch():
    elastic_manager = AsyncElasticSearchManager(hostname=elastic_upload_settings.elastic_host)
    kafka_consumer = KafkaConsumerManager(kafka_topic=elastic_upload_settings.graph_kafka_topic,
                                          kafka_broker=elastic_upload_settings.kafka_broker)

    for message in kafka_consumer.get_messages():
        try:
            for graph_id, graph_data in yield_single_graph_to_be_loaded_to_elasticsearch(message):
                logger.info(f"Processing graph: {graph_data['name']} from {graph_data['filepath']}")
                logger.debug(f"Degree sequence - in: {graph_data.get('in_degrees')}, out: {graph_data.get('out_degrees')}")

                if await elastic_manager.document_exists(elastic_upload_settings.cfg_index, graph_id):
                    logger.info(f"Graph already exists in cfg index, skipping: {graph_id}")
                    continue
                if await elastic_manager.document_exists(elastic_upload_settings.cfg_isomorphism_index, graph_id):
                    logger.info(f"Graph already exists in isomorphism index, skipping: {graph_id}")
                    continue

                graph_data["graph_dict"] = json.dumps(graph_data["graph_dict"])
                found_isomorphism = await elastic_manager.get_isomorphic_graph_id(
                    graph=graph_data,
                    index=elastic_upload_settings.cfg_index
                )

                if found_isomorphism:
                    logger.info(f"Found isomorphism: {graph_id} is isomorphic to {found_isomorphism}")
                    isomorphism_data = {
                        "isomorphic_to": found_isomorphism,
                        "filepath": graph_data["filepath"],
                        "commit_hash": graph_data["commit_hash"],
                        "repo_url": graph_data["repo_url"],
                        "language": graph_data["language"],
                        "name": graph_data["name"],
                    }
                    await elastic_manager.insert_document(elastic_upload_settings.cfg_isomorphism_index,
                                                          graph_id,
                                                          isomorphism_data)
                else:
                    logger.info(f"No isomorphism found, inserting as new graph: {graph_id}")
                    await elastic_manager.insert_document(elastic_upload_settings.cfg_index,
                                                          graph_id,
                                                          graph_data)

        except Exception as e:
            logger.error(f"Failed to process message: {e}")

            error_timestamp = datetime.now(timezone.utc).isoformat()
            error_doc = {
                "timestamp": error_timestamp,
                "service": "elasticsearch_upload",
                "message": str(message),
                "error": str(e)
            }
            response = await elastic_manager.insert_document(
                elastic_upload_settings.error_index,
                f"upload_{error_timestamp}",
                error_doc
            )
            logger.info(f"ES insert response: {response}")
            continue

if __name__ == "__main__":
    asyncio.run(upload_to_elasticsearch())
