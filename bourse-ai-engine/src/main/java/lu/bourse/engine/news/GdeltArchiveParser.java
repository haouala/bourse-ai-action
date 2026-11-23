package lu.bourse.engine.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded streaming parsers for GDELT open data. No external HTTP or database side effects. */
public final class GdeltArchiveParser {
    private static final int MAX_ROWS = 1_200_000;
    private static final int MAX_LINE = 120_000;
    private static final int MAX_MATCHES = 20_000;
    private final ObjectMapper json;
    public GdeltArchiveParser(ObjectMapper json) { this.json=json; }
    public record Article(String symbol,String title,String url,String source,Instant publishedAt) {}
    public record Result(List<Article> articles,int examined,int matches) {}
    private record Company(String symbol,Pattern name) {}

    private List<Company> companies(Map<String,String> names) {
        List<Company> companies=new ArrayList<>();
        for(var entry:names.entrySet()) {
            String name=entry.getValue()==null?"":entry.getValue().trim();
            if(name.length()<4 || name.length()>100) continue;
            companies.add(new Company(entry.getKey(),Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{N}])"+Pattern.quote(name)+"(?![\\p{L}\\p{N}])")));
        }
        return companies;
    }

    /** The quadgram stream is tab separated: DOCID TAB QUADGRAM TAB COUNT. */
    public Result ngrams(InputStream gzNgrams,InputStream gzToc,Map<String,String> names)
        throws IOException {
        List<Company> companies=companies(names);
        Map<Integer,Set<String>> matching=new HashMap<>();
        int examined=0,found=0;
        try(var br=new BufferedReader(new InputStreamReader(new InflatedLimit(new GZIPInputStream(gzNgrams)),StandardCharsets.UTF_8))) {
            String line;
            while((line=br.readLine())!=null) {
                if(++examined>MAX_ROWS || line.length()>MAX_LINE)throw new IOException("ARCHIVE_LIMIT");
                int first=line.indexOf('\t'),last=line.lastIndexOf('\t');
                if(first<1 || last<=first)continue;
                int id;
                try{id=Integer.parseInt(line.substring(0,first));}catch(NumberFormatException ex){continue;}
                String words=line.substring(first+1,last);
                for(Company c:companies)if(c.name().matcher(words).find()) {
                    matching.computeIfAbsent(id,k->new HashSet<>()).add(c.symbol());
                    if(++found>MAX_MATCHES)throw new IOException("MATCH_LIMIT");
                }
            }
        }
        List<Article> articles=new ArrayList<>();int tocRows=0;
        try(var br=new BufferedReader(new InputStreamReader(new InflatedLimit(new GZIPInputStream(gzToc)),StandardCharsets.UTF_8))) {
            String line;
            while((line=br.readLine())!=null) {
                if(++tocRows>MAX_ROWS || line.length()>MAX_LINE)throw new IOException("ARCHIVE_LIMIT");
                JsonNode node;
                try{node=json.readTree(line);}catch(Exception ignored){continue;}
                int id=node.path("ID").asInt(-1);
                Set<String> symbols=matching.get(id);
                if(symbols==null)continue;
                String url=node.path("url").asText("");
                if(!safeUrl(url))continue;
                Instant published=parseDate(node.path("date").asText(""));
                String title=node.path("title").asText("").strip();
                if(title.isEmpty())continue;
                for(String symbol:symbols)articles.add(new Article(symbol,cut(title,1500),url,"GDELT_NGRAMS",published));
            }
        }
        return new Result(List.copyOf(articles),examined,found);
    }

    /** GKG v2.1 is a tab-separated UTF-8 file inside a ZIP archive. */
    public Result gkg(InputStream zip,Map<String,String> names) throws IOException {
        List<Company> companies=companies(names);List<Article> articles=new ArrayList<>();
        Set<String> unique=new HashSet<>();int examined=0,found=0;
        try(var zin=new ZipInputStream(zip,StandardCharsets.UTF_8)) {
            ZipEntry e=zin.getNextEntry();
            if(e==null || e.isDirectory() || !e.getName().endsWith(".gkg.csv"))throw new IOException("INVALID_GKG_ARCHIVE");
            // Do not close a reader over zin: ZipInputStream owns the enclosing download.
            var br=new BufferedReader(new InputStreamReader(new InflatedLimit(zin),StandardCharsets.UTF_8));
            String line;
            while((line=br.readLine())!=null) {
                if(++examined>MAX_ROWS || line.length()>MAX_LINE)throw new IOException("ARCHIVE_LIMIT");
                String[] cols=line.split("\\t",-1);
                if(cols.length<15)continue;
                String url=cols[3];
                if(!safeUrl(url))continue;
                String organizations=cols[12]+";"+cols[13];
                Instant date=parseGkgDate(cols[0]);
                for(Company c:companies)if(c.name().matcher(organizations).find()) {
                    if(++found>MAX_MATCHES)throw new IOException("MATCH_LIMIT");
                    if(unique.add(c.symbol()+"|"+url)) {
                        // GKG has no original publisher title: never pretend the URL slug is a verified title.
                        articles.add(new Article(c.symbol(),"Mention GKG : "+c.symbol()+" (titre non fourni)",url,"GDELT_GKG",date));
                    }
                }
            }
        }
        return new Result(List.copyOf(articles),examined,found);
    }

    /** Hard byte bound after decompression; row-count bounds alone cannot stop zip bombs. */
    private static final class InflatedLimit extends FilterInputStream {
        private long remaining=90_000_000;
        InflatedLimit(InputStream source){super(source);}
        @Override public int read()throws IOException {
            if(remaining==0)throw new IOException("DECOMPRESSED_LIMIT");
            int b=in.read();if(b!=-1)remaining--;return b;
        }
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {
            if(remaining==0)throw new IOException("DECOMPRESSED_LIMIT");
            int n=in.read(bytes,offset,(int)Math.min(length,remaining));
            if(n>0)remaining-=n;return n;
        }
    }

    private static String cut(String value,int max) {return value.length()>max?value.substring(0,max):value;}
    static boolean safeUrl(String raw) {
        try {
            URI u=URI.create(raw);
            String host=u.getHost();
            return raw.length()<=2000 && ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))
                && host!=null && host.contains(".") && !host.matches("[0-9.]+") && u.getUserInfo()==null
                && !host.equalsIgnoreCase("localhost") && !host.endsWith(".local");
        } catch(Exception e){return false;}
    }
    private static Instant parseDate(String value) {
        try{return Instant.parse(value);}catch(DateTimeParseException e){return null;}
    }
    private static Instant parseGkgDate(String value) {
        try {
            if(!value.matches("[0-9]{14}"))return null;
            return java.time.LocalDateTime.parse(value,
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(java.time.ZoneOffset.UTC);
        }catch(Exception e){return null;}
    }
}
