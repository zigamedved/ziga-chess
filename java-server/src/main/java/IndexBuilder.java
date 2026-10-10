import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds a Lucene index from NDJSON produced by {@code scripts/prepare_lucene_docs.py}.
 *
 * <pre>
 *   java -cp target/my-maven-docker-project.jar IndexBuilder \
 *     --docs /tmp/docs.ndjson --out indexedFiles
 * </pre>
 *
 * Indexed (tokenized) fields: static, other, dynamic (WhitespaceTokenizer, same as search).
 * Stored metadata: name, White, Black, Result, PGN, PV1, PV2, endgameFEN.
 */
public class IndexBuilder {
    public static class WhitespaceAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(tokenizer);
        }
    }

    public static void main(String[] args) throws Exception {
        Path docsPath = null;
        Path outPath = Paths.get("indexedFiles");
        for (int i = 0; i < args.length; i++) {
            if ("--docs".equals(args[i]) && i + 1 < args.length) {
                docsPath = Paths.get(args[++i]);
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                outPath = Paths.get(args[++i]);
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage();
                return;
            }
        }
        if (docsPath == null || !Files.isRegularFile(docsPath)) {
            printUsage();
            throw new IllegalArgumentException("--docs NDJSON file is required");
        }

        Files.createDirectories(outPath);
        long written = buildIndex(docsPath, outPath);
        System.out.println("Indexed " + written + " docs into " + outPath.toAbsolutePath());
    }

    private static void printUsage() {
        System.out.println("Usage: IndexBuilder --docs docs.ndjson [--out indexedFiles]");
    }

    static long buildIndex(Path docsPath, Path outPath) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new WhitespaceAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        long written = 0;
        try (FSDirectory directory = FSDirectory.open(outPath);
             IndexWriter writer = new IndexWriter(directory, config);
             BufferedReader reader = Files.newBufferedReader(docsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject obj;
                try {
                    obj = new JsonParser().parse(line).getAsJsonObject();
                } catch (JsonSyntaxException | IllegalStateException ex) {
                    System.err.println("skip bad json line: " + ex.getMessage());
                    continue;
                }
                Document doc = toDocument(obj);
                if (doc == null) {
                    continue;
                }
                writer.addDocument(doc);
                written++;
                if (written % 10000 == 0) {
                    System.out.println("... " + written + " indexed");
                }
            }
            writer.commit();
        }
        return written;
    }

    static Document toDocument(JsonObject obj) {
        if (!obj.has("name") || !obj.has("static")) {
            return null;
        }
        Document doc = new Document();
        // Searchable feature fields (tokenized on whitespace).
        doc.add(new TextField("static", text(obj, "static"), Field.Store.NO));
        doc.add(new TextField("other", text(obj, "other"), Field.Store.NO));
        doc.add(new TextField("dynamic", text(obj, "dynamic"), Field.Store.NO));

        // Stored metadata for response hydration (no Mongo).
        String name = text(obj, "name");
        doc.add(new StringField("name", name, Field.Store.YES));
        doc.add(new StoredField("White", text(obj, "White")));
        doc.add(new StoredField("Black", text(obj, "Black")));
        doc.add(new StoredField("Result", text(obj, "Result")));
        doc.add(new StoredField("PGN", text(obj, "PGN")));
        doc.add(new StoredField("PV1", text(obj, "PV1")));
        doc.add(new StoredField("PV2", text(obj, "PV2")));
        doc.add(new StoredField("endgameFEN", text(obj, "endgameFEN")));
        return doc;
    }

    private static String text(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        return obj.get(field).getAsString();
    }
}
