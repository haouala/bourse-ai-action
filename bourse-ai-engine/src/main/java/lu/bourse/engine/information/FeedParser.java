package lu.bourse.engine.information;

import org.w3c.dom.*;
import org.springframework.web.util.HtmlUtils;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** RSS/Atom summaries only. XML entities, DTDs and external fetches are forbidden. */
public final class FeedParser {
    public static final int MAX_BYTES=2_000_000;
    public record Item(String key,String title,String summary,String url,String author,Instant publishedAt){}
    public List<Item> parse(byte[] bytes){
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("FEED_TOO_LARGE");
        try{
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setXIncludeAware(false);f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var b=f.newDocumentBuilder();b.setErrorHandler(new DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws SAXException{throw e;}});
            var d=b.parse(new ByteArrayInputStream(bytes));String root=d.getDocumentElement().getLocalName();
            if(root==null)root=d.getDocumentElement().getNodeName();
            boolean atom=root.equals("feed");if(!atom&&!Set.of("rss","RDF").contains(root))throw new IllegalArgumentException("UNSUPPORTED_FEED");
            var nodes=d.getElementsByTagNameNS("*",atom?"entry":"item");if(nodes.getLength()>500)throw new IllegalArgumentException("TOO_MANY_FEED_ITEMS");
            var items=new ArrayList<Item>();
            for(int i=0;i<nodes.getLength();i++){
                var e=(Element)nodes.item(i);String title=clean(child(e,"title"),1000),summary=clean(first(child(e,"description"),child(e,"summary"),child(e,"content"),child(e,"encoded")),27000);
                String link=child(e,"link");
                if(atom){link="";for(Element x:children(e,"link"))if(x.getAttribute("rel").isBlank()||x.getAttribute("rel").equals("alternate")){link=x.getAttribute("href");break;}}
                if(title.isBlank()||!https(link))continue;
                String key=first(child(e,atom?"id":"guid"),link);
                String author=clean(first(child(e,"creator"),child(e,"author")),300);
                // An update timestamp does not establish the original publication date.
                String time=first(child(e,"pubDate"),child(e,"published"),child(e,"date"));
                items.add(new Item(key,title,summary,link,author.isBlank()?null:author,date(time)));
            }
            return List.copyOf(items);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("INVALID_OR_UNSAFE_XML");}
    }
    private static List<Element> children(Element e,String name){var result=new ArrayList<Element>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element x&&(name.equals(x.getLocalName())||name.equals(x.getNodeName())))result.add(x);return result;}
    private static String child(Element e,String name){var nodes=children(e,name);return nodes.isEmpty()?"":nodes.getFirst().getTextContent().trim();}
    private static String first(String... values){for(String v:values)if(v!=null&&!v.isBlank())return v;return "";}
    private static String clean(String value,int max){String text=HtmlUtils.htmlUnescape(value.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>"," ").replaceAll("(?s)<[^>]*>"," ")).replaceAll("\\s+"," ").trim();return text.substring(0,Math.min(text.length(),max));}
    private static boolean https(String value){try{var u=URI.create(value);return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&value.length()<=1500;}catch(Exception e){return false;}}
    private static Instant date(String value){if(value.isBlank())return null;try{return Instant.parse(value);}catch(Exception ignored){}try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception ignored){}try{return OffsetDateTime.parse(value).toInstant();}catch(Exception ignored){}return null;}
}
