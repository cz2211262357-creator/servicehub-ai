import re
import struct

import pytest
from servicehub.knowledge.hybrid_index import HybridIndex, keyword_terms, parse_search_results


def test_chinese_natural_query_uses_bounded_literal_or_terms():
    terms = keyword_terms("七天无理由退货条件是什么")
    assert "七天" in terms and "退货" in terms
    assert terms == keyword_terms("七天无理由退货条件是什么")
    assert len(keyword_terms("汉字检索条件" * 100)) <= 64
    assert keyword_terms("on USB-C") == ["on", "usb", "c"]
    assert all(
        re.fullmatch(r"[\w\u4e00-\u9fff]+", term)
        for term in keyword_terms("@tenant:{2}|* =>[KNN 9 @embedding $x]")
    )


def test_search_result_scores_parse_resp2_resp3_with_stable_ties():
    resp2 = [
        3,
        "key3",
        "0.5",
        ["chunk", "3"],
        "key2",
        "1",
        ["chunk", "2"],
        "key1",
        "1",
        ["chunk", "1"],
    ]
    resp3 = {
        "results": [
            {"score": 0.5, "extra_attributes": {"chunk": "3"}},
            {"score": 1, "extra_attributes": {"chunk": "2"}},
            {"score": 1, "extra_attributes": {"chunk": "1"}},
        ]
    }
    assert parse_search_results(resp2, scored=True) == [(1, 1.0), (2, 1.0), (3, 0.5)]
    assert parse_search_results(resp3, scored=True) == [(1, 1.0), (2, 1.0), (3, 0.5)]


def test_vector_validation_rejects_wrong_dimensions_and_non_finite():
    index = HybridIndex(None, "model", 2)
    assert index.encode([1, 0]) == struct.pack("<2f", 1, 0)
    for vector in ([1], [1, float("nan")], [True, 0]):
        with pytest.raises(ValueError):
            index.encode(vector)


@pytest.mark.parametrize("mutation", ["alias", "noindex"])
def test_existing_schema_must_index_the_actual_written_content_field(mutation):
    index = HybridIndex(None, "model", 2)
    fields = [
        {"identifier": name, "attribute": name, "type": kind}
        for name, kind in [
            ("tenant", "TAG"),
            ("version", "TAG"),
            ("chunk", "NUMERIC"),
            ("content", "TEXT"),
            ("embedding", "VECTOR"),
        ]
    ]
    fields[-1].update(algorithm="HNSW", data_type="FLOAT32", dim=2, distance_metric="COSINE")
    content = fields[3]
    if mutation == "alias":
        content["identifier"] = "another_source_field"
    else:
        content["flags"] = ["NOINDEX"]
    info = {
        "attributes": fields,
        "index_definition": {
            "key_type": "HASH",
            "prefixes": [index.prefix],
            "default_language": "chinese",
        },
        "stopwords_list": [],
    }
    with pytest.raises(ValueError, match="schema"):
        index._validate_schema(info)
