import pytest
from uuid import uuid4

from pipeline.service.post_reranker_service import PostRerankerService

def test_rerank_empty_list():
    service = PostRerankerService()
    user_id = uuid4()
    
    assert service.rerank(user_id, []) == []

def test_rerank_passes_list_through():
    service = PostRerankerService()
    user_id = uuid4()
    post_ids = [uuid4(), uuid4()]
    
    assert service.rerank(user_id, post_ids) == post_ids
