package com.linkedin.gms.factory.search;

import com.google.common.collect.ImmutableList;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.spring.cache.HazelcastCacheManager;
import com.linkedin.common.urn.CorpuserUrn;
import com.linkedin.data.template.StringArray;
import com.linkedin.metadata.graph.EntityLineageResult;
import com.linkedin.metadata.graph.LineageDirection;
import com.linkedin.metadata.graph.LineageRelationship;
import com.linkedin.metadata.graph.LineageRelationshipArray;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.Criterion;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.EntityLineageResultCacheKey;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.metadata.search.SearchResult;
import com.linkedin.metadata.search.SearchResultMetadata;
import com.linkedin.metadata.search.cache.CacheableSearcher;
import com.linkedin.metadata.search.cache.CachedEntityLineageResult;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.javatuples.Quintet;
import org.javatuples.Sextet;
import org.springframework.cache.Cache;
import org.testng.Assert;
import org.testng.annotations.Test;

import static com.datahub.util.RecordUtils.*;
import static com.linkedin.metadata.search.client.CachingEntitySearchService.*;


public class CacheTest extends JetTestSupport {

    HazelcastCacheManager cacheManager1;
    HazelcastCacheManager cacheManager2;
    HazelcastInstance instance1;
    HazelcastInstance instance2;

    public CacheTest() {
        Config config = new Config();

        instance1 = createHazelcastInstance(config);
        instance2 = createHazelcastInstance(config);

        cacheManager1 = new HazelcastCacheManager(instance1);
        cacheManager2 = new HazelcastCacheManager(instance2);
    }

    @Test
    public void hazelcastTest() {
        CorpuserUrn corpuserUrn = new CorpuserUrn("user");
        SearchEntity searchEntity = new SearchEntity().setEntity(corpuserUrn);
        SearchResult searchResult = new SearchResult()
            .setEntities(new SearchEntityArray(List.of(searchEntity)))
            .setNumEntities(1)
            .setFrom(0)
            .setPageSize(1)
            .setMetadata(new SearchResultMetadata());

        Quintet<List<String>, String, Filter, SortCriterion, CacheableSearcher.QueryPagination>
            quintet = Quintet.with(List.of(corpuserUrn.toString()), "*", null, null,
            new CacheableSearcher.QueryPagination(0, 1));

        CacheableSearcher<Quintet<List<String>, String, Filter, SortCriterion, CacheableSearcher.QueryPagination>> cacheableSearcher1 =
            new CacheableSearcher<>(cacheManager1.getCache("test"), 10,
            querySize -> searchResult,
            querySize -> quintet, null, true);

        CacheableSearcher<Quintet<List<String>, String, Filter, SortCriterion, CacheableSearcher.QueryPagination>> cacheableSearcher2 =
            new CacheableSearcher<>(cacheManager2.getCache("test"), 10,
                querySize -> searchResult,
                querySize -> quintet, null, true);

        // Cache result
        SearchResult result = cacheableSearcher1.getSearchResults(0, 1);
        Assert.assertNotEquals(result, null);

        Assert.assertEquals(instance1.getMap("test").get(quintet), instance2.getMap("test").get(quintet));
        Assert.assertEquals(cacheableSearcher1.getSearchResults(0, 1), searchResult);
        Assert.assertEquals(cacheableSearcher1.getSearchResults(0, 1), cacheableSearcher2.getSearchResults(0, 1));
    }

    @Test
    public void hazelcastTestScroll() {
        CorpuserUrn corpuserUrn = new CorpuserUrn("user");
        SearchEntity searchEntity = new SearchEntity().setEntity(corpuserUrn);
        SearchResult searchResult = new SearchResult()
            .setEntities(new SearchEntityArray(List.of(searchEntity)))
            .setNumEntities(1)
            .setFrom(0)
            .setPageSize(1)
            .setMetadata(new SearchResultMetadata());

        final Criterion filterCriterion =  new Criterion()
            .setField("platform")
            .setCondition(Condition.EQUAL)
            .setValue("hive")
            .setValues(new StringArray(ImmutableList.of("hive")));

        final Criterion subtypeCriterion =  new Criterion()
            .setField("subtypes")
            .setCondition(Condition.EQUAL)
            .setValue("")
            .setValues(new StringArray(ImmutableList.of("view")));

        final Filter filterWithCondition = new Filter().setOr(
            new ConjunctiveCriterionArray(
                new ConjunctiveCriterion().setAnd(
                    new CriterionArray(ImmutableList.of(filterCriterion))),
                new ConjunctiveCriterion().setAnd(
                    new CriterionArray(ImmutableList.of(subtypeCriterion)))
            ));

        Sextet<List<String>, String, String, String, String, Integer>
            sextet = Sextet.with(List.of(corpuserUrn.toString()), "*", toJsonString(filterWithCondition), null, null, 1);

        Cache cache1 = cacheManager1.getCache(ENTITY_SEARCH_SERVICE_SCROLL_CACHE_NAME);
        Cache cache2 = cacheManager2.getCache(ENTITY_SEARCH_SERVICE_SCROLL_CACHE_NAME);

        // Cache result
        String json = toJsonString(searchResult);
        cache1.put(sextet, json);
        Assert.assertEquals(instance1.getMap(ENTITY_SEARCH_SERVICE_SCROLL_CACHE_NAME).get(sextet),
            instance2.getMap(ENTITY_SEARCH_SERVICE_SCROLL_CACHE_NAME).get(sextet));
        String cachedResult1 = cache1.get(sextet, String.class);
        String cachedResult2 = cache2.get(sextet, String.class);
        Assert.assertEquals(cachedResult1, cachedResult2);
        Assert.assertEquals(cache1.get(sextet, String.class), json);
        Assert.assertEquals(cache2.get(sextet, String.class), json);
    }

    @Test
    public void testLineageCaching() {
        CorpuserUrn corpuserUrn = new CorpuserUrn("user");
        EntityLineageResult lineageResult = new EntityLineageResult();
        LineageRelationshipArray array = new LineageRelationshipArray();
        LineageRelationship lineageRelationship = new LineageRelationship().setEntity(corpuserUrn).setType("type");
        for (int i = 0; i < 10000; i++) {
            array.add(lineageRelationship);
        }
        lineageResult.setRelationships(array).setCount(1).setStart(0).setTotal(1);
        CachedEntityLineageResult cachedEntityLineageResult = new CachedEntityLineageResult(lineageResult,
            System.currentTimeMillis());

        Cache cache1 = cacheManager1.getCache("relationshipSearchService");
        Cache cache2 = cacheManager2.getCache("relationshipSearchService");

        EntityLineageResultCacheKey key = new EntityLineageResultCacheKey(corpuserUrn, LineageDirection.DOWNSTREAM,
            0L, 1L, 1, ChronoUnit.DAYS);

        cache1.put(key, cachedEntityLineageResult);

        Assert.assertEquals(instance1.getMap("relationshipSearchService").get(key),
            instance2.getMap("relationshipSearchService").get(key));
        CachedEntityLineageResult cachedResult1 = cache1.get(key, CachedEntityLineageResult.class);
        CachedEntityLineageResult cachedResult2 = cache2.get(key, CachedEntityLineageResult.class);
        Assert.assertEquals(cachedResult1, cachedResult2);
        Assert.assertEquals(cache1.get(key, CachedEntityLineageResult.class), cachedEntityLineageResult);
        Assert.assertEquals(cache2.get(key, CachedEntityLineageResult.class).getEntityLineageResult(), lineageResult);
    }
}
