import json
from unittest.mock import AsyncMock
import networkx as nx
import pytest
from diskcache import Timeout
from app.config import settings
from app.chat import response_cache as answers
from app.services import chat
from app.retrieval import community_context as communities


@pytest.fixture
def store(monkeypatch, tmp_path):
    import diskcache
    with diskcache.Cache(str(tmp_path/'cache')) as cache:
        monkeypatch.setattr(answers, 'cache', cache)
        monkeypatch.setattr(settings, 'CHAT_ANSWER_CACHE_TTL_SECONDS', 300)
        yield cache


@pytest.mark.asyncio
async def test_answer_cache_reuses_complete_answer_and_current_citations(store, monkeypatch):
    calls=[]
    async def generate(*args, **kwargs):
        calls.append(args)
        yield 'Explanation [1] [999]'
    monkeypatch.setattr(chat.llm_client,'generate_stream',generate)
    chunks=[{'id':'chunk','course_id':'7','text':'source'}]
    first=[frame async for frame in chat._answer_events('question',chunks,['7'])]
    second=[frame async for frame in chat._answer_events('question',chunks,['7'])]
    assert len(calls)==1
    for frames in [first,second]:
        answer=json.loads(next(f.splitlines()[1][6:] for f in frames if f.startswith('event: answer')))
        assert answer['content']=='Explanation [1] '
        assert any(f.startswith('event: sources') and 'chunk' in f for f in frames)
        assert frames[-1].startswith('event: done')


def test_cache_key_preserves_history_order_scope_evidence_and_settings(store):
    chunks=[{'id':'x','text':'original'}]
    key=answers.response_key('history A then B; mastered: A; depth 2',chunks,['7'])
    variants=[('history B then A; mastered: A; depth 2',chunks,['7']),
              ('history A then B; mastered: B; depth 2',chunks,['7']),
              ('history A then B; mastered: A; depth 3',chunks,['7']),
              ('history A then B; mastered: A; depth 2',[{'id':'x','text':'updated'}],['7']),
              ('history A then B; mastered: A; depth 2',chunks,['8'])]
    assert all(answers.response_key(*values)!=key for values in variants)
    assert answers.response_key('question',chunks,[]) is None
    answers.save_answer(key,'answer')
    store.expire()
    assert answers.read_answer(key)=='answer'
    store.set(key,'expired',expire=-1)
    assert answers.read_answer(key) is None


@pytest.mark.asyncio
async def test_failed_or_cancelled_stream_is_not_cached(store,monkeypatch):
    async def failed(*args,**kwargs):
        yield 'Partial'
        yield '\n\n[ERROR: failed]'
    monkeypatch.setattr(chat.llm_client,'generate_stream',failed)
    frames=[f async for f in chat._answer_events('failed',[],['7'])]
    assert any(f.startswith('event: error') for f in frames)
    assert len(store)==0
    async def partial(*args,**kwargs):
        yield 'Partial'
        yield 'more'
    monkeypatch.setattr(chat.llm_client,'generate_stream',partial)
    stream=chat._answer_events('cancelled',[],['7'])
    await anext(stream); await stream.aclose()
    assert len(store)==0


@pytest.mark.asyncio
async def test_communities_are_scoped_cached_and_rebuilt_after_graph_version_change(monkeypatch):
    communities._SUMMARIES.clear()
    graph=nx.DiGraph([('a','b')]); graph.nodes['a']['name']='Basic'
    loader=AsyncMock(return_value=(graph,1,{},[]))
    monkeypatch.setattr(communities,'get_course_graph',loader)
    chunks=[{'course_id':'7'},{'course_id':'secret'}]
    first=await communities.retrieve_community_context(['b'],['7'],chunks)
    assert first[0]['course_id']=='7'
    assert 'Basic' in str(first)
    assert loader.call_args.args==('7',)
    # Same version reuses the summary rather than recomputing it.
    graph.nodes['a']['name']='Updated'
    assert await communities.retrieve_community_context(['b'],['7'],chunks)==first
    loader.return_value=(graph,2,{},[])
    assert 'Updated' in str(await communities.retrieve_community_context(['b'],['7'],chunks))
    assert await communities.retrieve_community_context(['b'],[],chunks)==[]
    assert await communities.retrieve_community_context(['unknown'],['7'],chunks)==[]
    communities._SUMMARIES.clear()


@pytest.mark.parametrize('error', [OSError, Timeout])
def test_cache_storage_failure_does_not_break_answer_generation(store, monkeypatch, error):
    def broken(*args, **kwargs):
        raise error('unavailable')
    monkeypatch.setattr(store,'get',broken)
    monkeypatch.setattr(store,'set',broken)
    assert answers.read_answer('key') is None
    answers.save_answer('key','answer')


@pytest.mark.asyncio
async def test_optional_summary_failure_falls_back_to_evidence(monkeypatch):
    monkeypatch.setattr(chat, 'retrieve_community_context', AsyncMock(side_effect=RuntimeError('unavailable')))
    assert await chat._optional_community_context(['a'], ['7'], [{'course_id':'7'}]) == []
