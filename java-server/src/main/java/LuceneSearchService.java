import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Lucene searcher per process. Opened on first search (or at startup),
 * refreshed when the index commits, closed on shutdown.
 */
public class LuceneSearchService {
    /** Lucene already ranks; Python only re-sorts this page. Override with LUCENE_TOP_K. */
    private static final int DEFAULT_TOP_K = 20;

    static SearcherManager searcherManager;
    static Directory openedDirectory;
    static String openedIndexDir;

    /** Overridable for tests via -Dchess.indexDir=/path/to/index */
    static String indexDir() {
        String fromEnv = System.getenv("LUCENE_INDEX_DIR");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv;
        }
        return System.getProperty("chess.indexDir", "indexedFiles");
    }

    static int topK() {
        String raw = System.getenv("LUCENE_TOP_K");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty("chess.topK", Integer.toString(DEFAULT_TOP_K));
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : DEFAULT_TOP_K;
        } catch (NumberFormatException ex) {
            return DEFAULT_TOP_K;
        }
    }

    static void requireIndexPresent() {
        Path dir = Paths.get(indexDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "Lucene index directory missing: " + dir.toAbsolutePath()
                            + ". Build it with `make index` (see README).");
        }
    }

    public static class MyAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(tokenizer);
        }
    }

    /**
     * BM25 search over static/other/dynamic; hydrate hits from Lucene stored fields
     * (no Mongo round-trip). Reuses the process-wide {@link SearcherManager}.
     */
    public static List<Map<String, Object>> searchSimilar(String input) throws Exception {
        return searchSimilar(input, SearchFilter.none());
    }

    /**
     * BM25 search over static/other/dynamic. A non-empty {@code filter} is a Lucene
     * filter on the same query, so the top-K are the top matches that pass it.
     * Explanations stay on the unfiltered text query.
     */
    public static List<Map<String, Object>> searchSimilar(String input, SearchFilter filter) throws Exception {
        ensureSearcher();
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            String escapedText = escapeCharacters(input);
            Query textQuery = parseQuery(escapedText);
            SearchFilter applied = filter == null ? SearchFilter.none() : filter;
            Query searched = applied.isEmpty() ? textQuery : withFilter(textQuery, applied);
            TopDocs foundDocs = searcher.search(searched, topK());

            List<Map<String, Object>> result = new ArrayList<>();
            for (ScoreDoc sd : foundDocs.scoreDocs) {
                Document d = searcher.doc(sd.doc);
                // Insertion order is the plain-text field order (AnalyseText walks entrySet).
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", nullToEmpty(d.get("name")));
                row.put("endgameFEN", nullToEmpty(d.get("endgameFEN")));
                row.put("White", nullToEmpty(d.get("White")));
                row.put("Black", nullToEmpty(d.get("Black")));
                row.put("Result", nullToEmpty(d.get("Result")));
                row.put("WhiteElo", nullToEmpty(d.get("WhiteElo")));
                row.put("BlackElo", nullToEmpty(d.get("BlackElo")));
                row.put("PGN", nullToEmpty(d.get("PGN")));
                row.put("PV1", nullToEmpty(d.get("PV1")));
                row.put("PV2", nullToEmpty(d.get("PV2")));
                row.put("score", sd.score);
                row.put("explanation", MatchExplanation.forDoc(searcher, textQuery, sd.doc));
                result.add(row);
            }
            return result;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private static Query withFilter(Query textQuery, SearchFilter filter) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(textQuery, BooleanClause.Occur.MUST);
        if (filter.result != null) {
            builder.add(new TermQuery(new Term("Result", filter.result)), BooleanClause.Occur.FILTER);
        }
        if (filter.minElo != null || filter.maxElo != null) {
            int min = filter.minElo == null ? 0 : filter.minElo;
            int max = filter.maxElo == null ? Integer.MAX_VALUE : filter.maxElo;
            builder.add(IntPoint.newRangeQuery("elo", min, max), BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }

    /** @deprecated Prefer {@link #searchSimilar(String)}; kept for older unit tests. */
    @Deprecated
    public static Map<String, Float> queryLucene(String input) throws Exception {
        Map<String, Float> scores = new HashMap<>();
        for (Map<String, Object> row : searchSimilar(input)) {
            Object name = row.get("name");
            Object score = row.get("score");
            if (name != null && score instanceof Number) {
                scores.put(name.toString(), ((Number) score).floatValue());
            }
        }
        return scores;
    }

    public static String escapeCharacters(String input) {
        return input.replace("!", "\\!")
                .replace("?", "\\?")
                .replace("-", "\\-")
                .replace("+", "\\+");
    }

    /**
     * Opens the shared searcher when missing, or when {@link #indexDir()} changed
     * (tests point at a new fixture). Safe to call on every request.
     */
    static void ensureSearcher() throws IOException {
        String dir = indexDir();
        synchronized (LuceneSearchService.class) {
            if (searcherManager != null && dir.equals(openedIndexDir)) {
                return;
            }
            closeSearchResourcesLocked();
            openedDirectory = FSDirectory.open(Paths.get(dir));
            searcherManager = new SearcherManager(openedDirectory, new Bm25SearcherFactory());
            openedIndexDir = dir;
        }
    }

    /** Closes the shared reader and directory. Idempotent. */
    static void closeSearchResources() throws IOException {
        synchronized (LuceneSearchService.class) {
            closeSearchResourcesLocked();
        }
    }

    private static void closeSearchResourcesLocked() throws IOException {
        IOException failure = null;
        if (searcherManager != null) {
            try {
                searcherManager.close();
            } catch (IOException ex) {
                failure = ex;
            }
            searcherManager = null;
        }
        if (openedDirectory != null) {
            try {
                openedDirectory.close();
            } catch (IOException ex) {
                if (failure == null) {
                    failure = ex;
                }
            }
            openedDirectory = null;
        }
        openedIndexDir = null;
        if (failure != null) {
            throw failure;
        }
    }

    /** Reader currently held by the shared manager (tests assert it is reused). */
    static IndexReader sharedReader() throws IOException {
        ensureSearcher();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader();
        } finally {
            searcherManager.release(searcher);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Query parseQuery(String textToFind) throws Exception {
        MyAnalyzer analyzer = new MyAnalyzer();
        String[] fields = { "static", "other", "dynamic" };
        Map<String, Float> boosts = new HashMap<>();
        boosts.put("static", 1.0f);
        boosts.put("other", 1.0f);
        boosts.put("dynamic", 1.0f);
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(fields, analyzer, boosts);
        return queryParser.parse(textToFind);
    }

    static final class Bm25SearcherFactory extends SearcherFactory {
        @Override
        public IndexSearcher newSearcher(IndexReader reader, IndexReader previous) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            return searcher;
        }
    }
}
