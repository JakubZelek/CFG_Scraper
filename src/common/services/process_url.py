import logging
from common.schemas.url_app import RepoUrl, RepoUrlList
from common.managers.elastic import AsyncElasticSearchManager
from common.managers.kafka import KafkaProducerManager
from common.config.url_app_settings import UrlAppSettings

logger = logging.getLogger(__name__)


class ProcessUrlService:
    def __init__(self, elastic_manager: AsyncElasticSearchManager, kafka_manager: KafkaProducerManager, url_app_settings: UrlAppSettings):
        self.kafka_manager = kafka_manager
        self.elastic_manager = elastic_manager
        self.elastic_repository_index = url_app_settings.repos_index
        self.language_topics_list = url_app_settings.language_topics_list()

    async def scrap_single_repo(self, repo_input: RepoUrl):
        if repo_input.language_topic not in self.language_topics_list:
            return {"status": "topic not available"}
    
        if await self.elastic_manager.is_repo_in_the_index(self.elastic_repository_index, repo_input.url):
            return {"status": "repo already exists"}

        print(f"[DEBUG] Sending to Kafka topic: {repo_input.language_topic}")
        future = self.kafka_manager.push_to_the_topic(topic=repo_input.language_topic, message=repo_input.model_dump())
        try:
            result = future.get(timeout=10)
            print(f"[DEBUG] Kafka send result: {result}")
        except Exception as e:
            print(f"[DEBUG] Kafka send failed: {e}")
        
        await self.elastic_manager.insert_repo_info(self.elastic_repository_index, repo_input.url)
        self.kafka_manager.flush()
    
        return {"status": "repo sent to queue"}
        

    async def scrap_multiple_repos(self, repo_input: RepoUrlList):
        if repo_input.language_topic not in self.language_topics_list:
            return {"status": "topic not available"}
        
        repos_already_exists = []
        repos_processed = []
    
        for url in repo_input.url_list:
            if not await self.elastic_manager.is_repo_in_the_index(self.elastic_repository_index, url):            
                message = repo_input.model_dump()
                message["url"] = url
                message.pop("url_list")

                self.kafka_manager.push_to_the_topic(topic=repo_input.language_topic, message=message)
                await self.elastic_manager.insert_repo_info(self.elastic_repository_index, url)
                repos_processed.append(url)
            else:
                repos_already_exists.append(url)

        self.kafka_manager.flush()
        return {"status": {"repos_sent_to_queue": repos_processed, "repos_already_exists": repos_already_exists}}
