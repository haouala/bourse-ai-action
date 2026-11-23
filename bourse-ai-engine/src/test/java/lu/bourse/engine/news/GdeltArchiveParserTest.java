package lu.bourse.engine.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class GdeltArchiveParserTest {
    private final GdeltArchiveParser parser = new GdeltArchiveParser(new ObjectMapper());

    @Test void ngramsJoinDocumentIdsToRealTitlesAndRejectFalseCompanyMatches() throws Exception {
        byte[] text=gzip("12\tNVIDIA announced new processors\t2\n13\tCarrot prices are rising\t1\n");
        byte[] toc=gzip("""
            {"ID":12,"title":"NVIDIA announces processors","url":"https://example.org/nvidia","date":"2026-11-22T10:00:00Z"}
            {"ID":13,"title":"Food prices","url":"https://example.org/carrot","date":"2026-11-22T10:00:00Z"}
            """);
        var result=parser.ngrams(new ByteArrayInputStream(text),new ByteArrayInputStream(toc),
            Map.of("NVDA","NVIDIA"));
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().symbol()).isEqualTo("NVDA");
        assertThat(result.articles().getFirst().source()).isEqualTo("GDELT_NGRAMS");
        assertThat(result.articles().getFirst().title()).isEqualTo("NVIDIA announces processors");
        assertThat(result.matches()).isEqualTo(1);
    }

    @Test void gkgParsesCompanyOrganizationsWithoutInventingOriginalHeadlines() throws Exception {
        String[] columns=new String[27];
        java.util.Arrays.fill(columns,"");
        columns[0]="20261122120000";
        columns[3]="https://example.org/technology";
        columns[12]="NVIDIA;Some Other Company";
        byte[] zip=zip("sample.gkg.csv",String.join("\t",columns)+"\n");
        var result=parser.gkg(new ByteArrayInputStream(zip),Map.of("NVDA","NVIDIA"));
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().title()).contains("titre non fourni");
        assertThat(result.articles().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-11-22T12:00:00Z"));
    }

    @Test void rejectsUnsafeArticleUrls() {
        for(String url:java.util.List.of("file:///etc/passwd","http://localhost/private","https://127.0.0.1/"))
            assertThat(GdeltArchiveParser.safeUrl(url)).isFalse();
        assertThat(GdeltArchiveParser.safeUrl("https://example.org/news")).isTrue();
    }

    private static byte[] gzip(String input)throws IOException {
        var out=new ByteArrayOutputStream();
        try(var zip=new GZIPOutputStream(out)){zip.write(input.getBytes(StandardCharsets.UTF_8));}
        return out.toByteArray();
    }
    private static byte[] zip(String name,String text)throws IOException {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
