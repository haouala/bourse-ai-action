package lu.bourse.engine.information;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class FeedParserTest {
    FeedParser parser=new FeedParser();byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void rssKeepsOriginalDateAuthorAndPlainText(){var result=parser.parse(bytes("<rss><channel><item><guid>1</guid><title>A &amp; B</title><link>https://example.org/1</link><description><![CDATA[<p>Statement <b>here</b></p>]]></description><author>Official</author><pubDate>Thu, 19 Nov 2026 14:00:00 GMT</pubDate></item></channel></rss>"));assertThat(result).hasSize(1);assertThat(result.getFirst().summary()).isEqualTo("Statement here");assertThat(result.getFirst().publishedAt().toString()).isEqualTo("2026-11-19T14:00:00Z");}
    @Test void atomUpdatedDateIsNotSubstitutedForMissingPublicationDate(){var r=parser.parse(bytes("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>id</id><title>Title</title><link href='https://example.org/a'/><updated>2026-11-19T14:00:00Z</updated></entry></feed>"));assertThat(r).hasSize(1);assertThat(r.getFirst().publishedAt()).isNull();}
    @Test void externalEntitiesAndDtdsAreRejected(){assertThatThrownBy(()->parser.parse(bytes("<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void unsupportedHtmlAndMalformedXmlAreRejected(){assertThatThrownBy(()->parser.parse(bytes("<html><body>login</body></html>"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->parser.parse(bytes("<rss>"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void unsafeArticleLinksAreSkipped(){var r=parser.parse(bytes("<rss><channel><item><title>x</title><link>javascript:alert(1)</link></item></channel></rss>"));assertThat(r).isEmpty();}
    @Test void podcastKeepsAudioAndTranscriptWithoutFetchingThem(){
        var r=parser.parse(bytes("<rss xmlns:podcast='https://podcastindex.org/namespace/1.0'><channel><item><title>Episode</title><link>https://example.org/e</link><enclosure type='audio/mpeg' url='https://example.org/a.mp3'/><podcast:transcript url='https://example.org/t.vtt'/></item></channel></rss>"));
        assertThat(r.getFirst().audioUrl()).isEqualTo("https://example.org/a.mp3");assertThat(r.getFirst().transcriptUrl()).isEqualTo("https://example.org/t.vtt");
    }
    @Test void oversizedResponsesAreRejected(){assertThatThrownBy(()->parser.parse(new byte[FeedParser.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class).hasMessage("FEED_TOO_LARGE");}
}
