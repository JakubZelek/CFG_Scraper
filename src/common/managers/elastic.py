import json
import logging
import networkx as nx
from networkx.algorithms import isomorphism
from elasticsearch import AsyncElasticsearch, NotFoundError

logger = logging.getLogger(__name__)


class AsyncElasticSearchManager:
    def __init__(self, hostname: str):
        self.elasticsearch = AsyncElasticsearch(hosts=[hostname])

    async def index_exists(self, index: str):
        return await self.elasticsearch.indices.exists(index=index)

    async def create_index(self, index: str):
        return await self.elasticsearch.indices.create(index=index)

    async def is_repo_in_the_index(self, index: str, repo_url: str):
        return await self.document_exists(index=index, doc_id=repo_url)

    async def document_exists(self, index: str, doc_id: str) -> bool:
        return await self.elasticsearch.exists(index=index, id=doc_id)

    async def insert_repo_info(self, index: str, repo_url: str):
        return await self.elasticsearch.index(index=index, id=repo_url, document={})

    async def delete_repo_info(self, index: str, repo_url: str):
        return await self.elasticsearch.delete(index=index, id=repo_url)
    
    async def insert_document(self, index: str, doc_id: str, document: dict, refresh: bool = True):
        """Insert document with refresh=True by default to ensure immediate searchability."""
        return await self.elasticsearch.index(index=index, id=doc_id, document=document, refresh=refresh)

    async def get_isomorphic_graph_id(
        self, graph: dict, index: str, scroll_size: int = 10000
    ) -> str | None:
        scroll_id = None
        try:
            query = self.check_isomorphism_query(
                out_degrees=graph["out_degrees"],
                in_degrees=graph["in_degrees"]
            )
            logger.debug(f"Searching for isomorphism with query: {query}")

            response = await self.elasticsearch.search(
                index=index,
                body=query,
                scroll="2m",
                size=scroll_size
            )

            scroll_id = response.get("_scroll_id")
            total_candidates = response["hits"]["total"]["value"]
            logger.debug(f"Found {total_candidates} candidates with matching degree sequence")

            candidates_checked = 0
            while response["hits"]["hits"]:
                for hit in response["hits"]["hits"]:
                    candidates_checked += 1
                    candidate_data = json.loads(hit["_source"]["graph_dict"])
                    isomorphism_candidate = nx.DiGraph(candidate_data)

                    input_graph = nx.DiGraph(json.loads(graph["graph_dict"]))
                    matcher = isomorphism.DiGraphMatcher(input_graph, isomorphism_candidate)

                    if matcher.is_isomorphic():
                        logger.debug(f"Isomorphism found after checking {candidates_checked} candidates")
                        return hit["_id"]

                response = await self.elasticsearch.scroll(
                    scroll_id=scroll_id,
                    scroll="2m"
                )
                scroll_id = response.get("_scroll_id")

            logger.debug(f"No isomorphism found after checking {candidates_checked} candidates")
            return None

        except NotFoundError:
            logger.debug(f"Index {index} not found, returning None")
            return None

        finally:
            if scroll_id:
                await self.elasticsearch.clear_scroll(scroll_id=scroll_id)

    
    @staticmethod
    def check_isomorphism_query(out_degrees: str, in_degrees: str):
        query = {
            "query": {
                "bool": {
                    "must": [
                        {"term": {"out_degrees.keyword": out_degrees}},
                        {"term": {"in_degrees.keyword": in_degrees}},
                    ]
                }
            }
        }
        return query
