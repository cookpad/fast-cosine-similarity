package com.staysense.fastcosinesimilarity;

import org.apache.lucene.search.Explanation;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FastCosineIT extends FastCosIntegTestCase {
    private static final String INDEX = "test";

    public void testQueryEncodedVec() throws IOException {
        setupIndex(INDEX);

        List<Double> docVector = Arrays.asList(0.1d, 0.2d);

        byte[] docVectorBytes = getBinaryVec(docVector);
        String docVectorBytesB64 = base64StringVector(docVectorBytes);
        logger.info(
                "[{}] docVector [Bytes = {}, B64 = {}]",
                getLogPrefix(),
                docVectorBytes,
                docVectorBytesB64
        );
        String id = "swedish-payments";
        String name = randomDocumentName();
        client().prepareIndex(INDEX, "_doc", id)
                .setSource(buildDoc(name, docVectorBytes))
                .execute().actionGet();

        refresh(INDEX);

        List<Double> queryVector = Arrays.asList(0.2d, 0.1d);
        String queryVectorB64 = base64StringVector(queryVector);

        Map<String, Object> params = new HashMap<>();
        params.put("field", "vec");
        params.put(
                "encoded_vector",
                queryVectorB64
        );

        SearchResponse searchResponse = client().prepareSearch(INDEX)
                .setQuery(
                        QueryBuilders.functionScoreQuery(
                                ScoreFunctionBuilders.scriptFunction(new Script(
                                        ScriptType.INLINE,
                                        "fast_cosine",
                                        "staysense",
                                        params
                                ))
                        )
                )
                .setExplain(true)
                .execute().actionGet();
        assertEquals(1, searchResponse.getHits().getTotalHits().value);

        SearchHit searchHit = searchResponse.getHits().getAt(0);

        assertEquals(id, searchHit.getId());
        float expectedScore = 0.9f;
        assertEquals(expectedScore, searchHit.getScore(), 0.001d);

        Map<String, Object> expectedHitSource = new HashMap<>();
        expectedHitSource.put("name", name);
        expectedHitSource.put("vec", docVectorBytesB64);
        assertEquals(expectedHitSource, searchHit.getSourceAsMap());

        // Top level Explanation example:
        //
        //  0.8 = min of:
        //      0.8 = cosineSimilarity(...)
        //          1.0 = _score:
        //              1.0 = *:*
        //      3.4028235E38 = maxBoost
        Explanation expectedExplanation =
            Explanation.match(expectedScore,
                    "function score, product of:",
                    Explanation.match(
                            1.0f,
                            "*:*"
                    ),
                    Explanation.match(
                            expectedScore,
                            "min of:",

                            Explanation.match(
                                    expectedScore,
                                    String.format(
                                            Locale.ROOT,
                                            "cosineSimilarity(doc['%s'].value, %s)",
                                            "vec",
                                            Arrays.toString(queryVector.toArray())
                                    ),
                                    Explanation.match(
                                            1.0f,
                                            "_score:",
                                            Explanation.match(
                                                    1.0f,
                                                    "*:*"
                                            )
                                    )
                            ),
                            Explanation.match(
                                    Float.MAX_VALUE,
                                    "maxBoost"
                            )
                    )
            );

        Explanation actualExplanation = searchHit.getExplanation();

        assertEquals(expectedExplanation, actualExplanation);
    }
}
