package lu.bourse.engine.social;

import java.time.*;

/** Initial heuristic. Neither a probability nor an expected return. */
public final class SocialScoring {
    public static final String METHOD="SOCIAL_EVENT_V1_HEURISTIC";
    private SocialScoring() {}
    public enum Topic { TARIFFS, MONETARY_POLICY, CORPORATE, CRYPTO, GENERAL }
    public enum Kind { ANNOUNCEMENT, RUMOR, ENACTED, OPINION, RETRACTION }
    public enum Confirmation { UNCONFIRMED, AUTHOR_STATEMENT, CORROBORATED, OFFICIAL_DOCUMENT }
    public enum Direction { POSITIVE, NEGATIVE, MIXED, UNKNOWN }
    public enum Horizon { INTRADAY, DAYS, MONTHS, YEARS }
    public record Result(int authority,double potential,int confidence,Double signedScore) {}

    public static Result compute(int authority,int materiality,int relevance,int novelty,int confidence,
                                 Confirmation confirmation,Kind kind,boolean identityConfirmed,boolean retracted,
                                 Direction direction,Horizon horizon,Instant publishedAt,Instant now) {
        int ceiling=switch(confirmation){case UNCONFIRMED->35;case AUTHOR_STATEMENT->65;case CORROBORATED->85;case OFFICIAL_DOCUMENT->95;};
        if(kind==Kind.RUMOR)ceiling=Math.min(ceiling,35);
        if(!identityConfirmed)ceiling=Math.min(ceiling,30);
        retracted=retracted||kind==Kind.RETRACTION;
        int c=retracted?0:Math.min(confidence,ceiling);
        long halfLife=switch(horizon){case INTRADAY->900;case DAYS->86400;case MONTHS->2592000;case YEARS->31536000;};
        double age=Math.max(0,Duration.between(publishedAt,now).toMillis()/1000.0);
        double potential=retracted?0:(0.25*authority+0.50*materiality+0.25*novelty)*relevance/100.0*Math.pow(0.5,age/halfLife);
        Double signed=switch(direction){case POSITIVE->potential*c/100;case NEGATIVE->-potential*c/100;default->null;};
        return new Result(authority,potential,c,signed);
    }
}
