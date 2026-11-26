package lu.bourse.engine.service;

import java.time.Instant;
import java.util.*;
import static lu.bourse.engine.service.CandlestickPatterns.Bar;

/** Causal features: every pivot has a separate availability date, two bars after its extremum. */
public final class TechnicalAnalysis {
    private TechnicalAnalysis() {}
    public static final String VERSION="TECHNICAL_FEATURES_V1";
    public static final List<String> FEATURES=List.of("hammer","doji","engulf_bull","engulf_bear",
            "confirmed_bull","confirmed_bear","relative_volume_20","volume_available","triple_bottom",
            "triple_top","elliott_impulse","elliott_abc","donchian_breakout","atr14_pct",
            "bollinger_position","bollinger_width","sma20_50_gap","ohlc_available");
    public record Point(String label,Instant time,Instant availableAt,double price) {}
    public record Signal(String code,String direction,String status,Instant availableAt,List<Point> points,
                         Double support,Double resistance) {}
    public record Contribution(String code,int opportunity,int risk,String explanation) {}
    public record Result(String method,int continuousCandles,Map<String,Double> features,List<Signal> signals,
                         int opportunityDelta,int riskDelta,List<Contribution> contributions) {}
    private record Pivot(int index,int type,double price,int knownIndex) {}

    public static Result analyze(List<Bar> input) {
        // A gap, invalid OHLC or provider change resets the usable suffix, identically in Python.
        int from=Math.max(0,input.size()-250);
        for(int i=from;i<input.size();i++) {
            Bar b=input.get(i);
            if(!valid(b))from=i+1;
            else if(i>from&&(!b.followsPrevious()||!Objects.equals(b.provider(),input.get(i-1).provider())
                    ||!b.openTime().isAfter(input.get(i-1).openTime())||b.openTime().isBefore(input.get(i-1).closeTime())))from=i;
        }
        List<Bar> bars=input.subList(from,input.size());int n=bars.size();
        var f=new LinkedHashMap<String,Double>();FEATURES.forEach(k->f.put(k,null));
        f.put("ohlc_available",n>=5?1.:0.);f.put("volume_available",0.);
        var signals=new ArrayList<Signal>();var contributions=new ArrayList<Contribution>();
        if(n==0)return result(n,f,signals,contributions);
        int last=n-1;
        if(n>=5) {
            for(String k:List.of("hammer","doji","engulf_bull","engulf_bear","confirmed_bull","confirmed_bear"))f.put(k,0.);
            for(var p:CandlestickPatterns.analyze(bars).patterns()) {
                if(p.openTime().equals(bars.getLast().openTime())) {
                    String key=switch(p.code()){case "HAMMER"->"hammer";case "DOJI"->"doji";case "BULLISH_ENGULFING"->"engulf_bull";default->"engulf_bear";};
                    f.put(key,1.);
                }
                if("PRICE_CONFIRMED".equals(p.confirmation())&&bars.getLast().closeTime().equals(p.confirmationAt()))
                    f.put(p.direction().equals("BULLISH")?"confirmed_bull":"confirmed_bear",1.);
            }
            int candle=(on(f,"hammer")||on(f,"engulf_bull")?2:0)-(on(f,"engulf_bear")?2:0)
                    +(on(f,"confirmed_bull")?3:0)-(on(f,"confirmed_bear")?3:0);
            int risk=(on(f,"doji")?1:0)+(on(f,"engulf_bear")?2:0)+(on(f,"confirmed_bear")?3:0);
            contributions.add(new Contribution("CANDLE_PATTERNS",clamp(candle,-3,3),Math.min(3,risk),
                    "Figures des deux dernières bougies : hausse +2, baisse −2, confirmation par la suivante ±3 ; doji +1 de risque. Plafond par famille."));
        }
        if(n>=21) {
            double sum=0;boolean volume=true;
            for(int i=n-21;i<n;i++){var v=bars.get(i).volume();if(v==null||v.signum()<=0){volume=false;break;}if(i<last)sum+=v.doubleValue();}
            if(volume&&sum>0){f.put("relative_volume_20",Math.min(20,bars.getLast().volume().doubleValue()/(sum/20)));f.put("volume_available",1.);}
            double upper=-Double.MAX_VALUE,lower=Double.MAX_VALUE;
            for(int i=n-21;i<last;i++){upper=Math.max(upper,bars.get(i).high().doubleValue());lower=Math.min(lower,bars.get(i).low().doubleValue());}
            double close=bars.getLast().close().doubleValue();double breakout=close>upper?1:close<lower?-1:0;
            f.put("donchian_breakout",breakout);
            contributions.add(new Contribution("DONCHIAN_20",(int)breakout*2,breakout<0?2:0,
                    "Clôture au-delà du plus haut ou du plus bas des 20 bougies précédentes, bougie actuelle exclue."));
        }
        if(n>=15) {
            // SMA of the last 14 true ranges; explicit variant, not claimed to be Wilder-smoothed ATR.
            double sum=0;for(int i=n-14;i<n;i++) {
                Bar b=bars.get(i);double h=b.high().doubleValue(),l=b.low().doubleValue(),previous=bars.get(i-1).close().doubleValue();
                sum+=Math.max(h-l,Math.max(Math.abs(h-previous),Math.abs(l-previous)));
            }
            double atr=sum/14/bars.getLast().close().doubleValue();f.put("atr14_pct",atr);
            contributions.add(new Contribution("ATR_14",0,atr>=.03?2:0,"Amplitude vraie moyenne sur 14 périodes ; au moins 3 % du prix ajoute 2 points de risque selon cette règle expérimentale."));
        }
        if(n>=20) {
            double mean=mean(bars,n-20,n),variance=0;
            for(int i=n-20;i<n;i++)variance+=Math.pow(bars.get(i).close().doubleValue()-mean,2);
            double sd=Math.sqrt(variance/20);double position=sd>0?(bars.getLast().close().doubleValue()-mean)/(2*sd):0;
            f.put("bollinger_position",position);f.put("bollinger_width",4*sd/mean);
            contributions.add(new Contribution("BOLLINGER_20",0,Math.abs(position)>1?1:0,
                    "Bandes : moyenne sur 20 périodes ±2 écarts-types. Une clôture hors bande ajoute 1 point de risque, sans ordre de vente ou d’achat automatique."));
        }
        if(n>=50) {
            double gap=mean(bars,n-20,n)/mean(bars,n-50,n)-1;f.put("sma20_50_gap",gap);
            contributions.add(new Contribution("SMA_20_50",gap>0?2:gap<0?-2:0,gap<0?1:0,
                    "Tendance : moyenne 20 au-dessus de la moyenne 50, +2 ; en dessous, −2 et +1 de risque."));
        }
        var pivots=pivots(bars);
        f.put("triple_bottom",n>=20?0.:null);f.put("triple_top",n>=20?0.:null);
        f.put("elliott_impulse",n>=20?0.:null);f.put("elliott_abc",n>=20?0.:null);
        for(int sign:new int[]{1,-1}) {
            Signal triple=triple(bars,pivots,sign);
            if(triple!=null){signals.add(triple);if(triple.status().equals("BREAKOUT_OBSERVED")) {
                f.put(sign>0?"triple_bottom":"triple_top",1.);
                contributions.add(new Contribution(triple.code(),sign*3,sign<0?3:0,
                        "Trois creux ou sommets comparables ; cassure observée du niveau intermédiaire. Contribution bornée à 3 points."));
            }}
            Signal impulse=elliott(bars,pivots,sign,false);
            if(impulse!=null){signals.add(impulse);f.put("elliott_impulse",(double)sign);}
            Signal abc=elliott(bars,pivots,sign,true);
            if(abc!=null){signals.add(abc);f.put("elliott_abc",(double)sign);
                contributions.add(new Contribution(abc.code(),sign,sign<0?1:0,
                        "Structure Elliott 1–5 puis A–B–C compatible avec les règles simplifiées ; piste exploratoire limitée à 1 point."));}
        }
        return result(n,f,signals,contributions);
    }
    private static Result result(int n,Map<String,Double> f,List<Signal> s,List<Contribution> c) {
        return new Result(VERSION,n,Collections.unmodifiableMap(f),List.copyOf(s),
                clamp(c.stream().mapToInt(Contribution::opportunity).sum(),-8,8),
                clamp(c.stream().mapToInt(Contribution::risk).sum(),0,8),List.copyOf(c));
    }
    private static boolean on(Map<String,Double> f,String k){return Double.valueOf(1).equals(f.get(k));}
    private static int clamp(int n,int min,int max){return Math.max(min,Math.min(max,n));}
    private static double mean(List<Bar>b,int from,int to){double sum=0;for(int i=from;i<to;i++)sum+=b.get(i).close().doubleValue();return sum/(to-from);}
    private static boolean valid(Bar b){return b!=null&&b.open()!=null&&b.close()!=null&&b.high()!=null&&b.low()!=null
            &&b.openTime()!=null&&b.closeTime()!=null&&b.closeTime().isAfter(b.openTime())&&b.provider()!=null&&!b.provider().isBlank()
            &&b.low().signum()>0&&b.high().compareTo(b.open().max(b.close()))>=0&&b.low().compareTo(b.open().min(b.close()))<=0;}
    private static List<Pivot> pivots(List<Bar> b) {
        var points=new ArrayList<Pivot>();
        for(int i=2;i<b.size()-2;i++) {
            double v=b.get(i).close().doubleValue();boolean low=true,high=true;
            for(int j=i-2;j<=i+2;j++)if(j!=i){double other=b.get(j).close().doubleValue();low&=v<other;high&=v>other;}
            if(!low&&!high)continue;Pivot p=new Pivot(i,low?-1:1,v,i+2);
            if(!points.isEmpty()&&points.getLast().type()==p.type()) {
                // A later same-side extremum is a new candidate, never backdated to its earlier confirmation.
                if((p.price()-points.getLast().price())*p.type()>0)points.set(points.size()-1,p);
            }else points.add(p);
        }
        return points;
    }
    private static boolean alternating(List<Pivot> p,int sign) {
        for(int i=0;i<p.size();i++)if(p.get(i).type()*sign!=(i%2==0?-1:1))return false;return true;
    }
    private static List<Point> points(List<Bar>b,List<Pivot>p,String[] labels) {
        var result=new ArrayList<Point>();for(int i=0;i<p.size();i++){var x=p.get(i);result.add(new Point(labels[i],b.get(x.index()).openTime(),b.get(x.knownIndex()).closeTime(),x.price()));}return List.copyOf(result);
    }
    private static Signal triple(List<Bar>b,List<Pivot> pivots,int sign) {
        if(b.size()<20||pivots.size()<5)return null;var p=pivots.subList(pivots.size()-5,pivots.size());
        if(!alternating(p,sign)||b.size()-1-p.getLast().knownIndex()>20||p.getFirst().index()<4)return null;
        double a=p.get(0).price()*sign,c=p.get(2).price()*sign,e=p.get(4).price()*sign;
        double base=(a+c+e)/3,scale=Math.abs(base),floor=Math.min(a,Math.min(c,e)),ceiling=Math.max(a,Math.max(c,e));
        double peak=Math.max(p.get(1).price()*sign,p.get(3).price()*sign);
        if(ceiling-floor>scale*.02||Math.min(p.get(1).price()*sign,p.get(3).price()*sign)<ceiling+scale*.02
                ||b.get(p.getFirst().index()-4).close().doubleValue()*sign<ceiling+scale*.03)return null;
        int breakout=-1;
        for(int i=p.getLast().index()+1;i<b.size();i++) {
            double price=b.get(i).close().doubleValue()*sign;
            if(price<floor-scale*.01)return null;
            if(i>=p.getLast().knownIndex()&&breakout<0&&price>peak)breakout=i;
        }
        if(breakout>=0&&b.size()-1-breakout>3)return null;
        return new Signal(sign>0?"TRIPLE_BOTTOM":"TRIPLE_TOP",sign>0?"BULLISH":"BEARISH",
                breakout>=0?"BREAKOUT_OBSERVED":"FORMING",b.get(breakout>=0?breakout:p.getLast().knownIndex()).closeTime(),
                points(b,p,new String[]{"1","2","3","4","5"}),sign>0?floor:peak*sign,sign>0?peak:floor*sign);
    }
    private static boolean impulse(List<Pivot> p,int sign) {
        if(!alternating(p,sign))return false;double[] v=p.stream().mapToDouble(x->x.price()*sign).toArray();
        double w1=v[1]-v[0],w3=v[3]-v[2],w5=v[5]-v[4];
        return v[2]>v[0]&&v[3]>v[1]&&v[4]>v[1]&&v[5]>v[3]&&w3>=Math.min(w1,w5);
    }
    private static Signal elliott(List<Bar>b,List<Pivot> pivots,int sign,boolean correction) {
        int count=correction?9:6;if(b.size()<20||pivots.size()<count)return null;
        var p=pivots.subList(pivots.size()-count,pivots.size());
        if(b.size()-1-p.getLast().knownIndex()>3||!impulse(p.subList(0,6),sign))return null;
        if(correction) {
            if(!alternating(p,sign))return null;
            double top=p.get(5).price()*sign,a=p.get(6).price()*sign,bb=p.get(7).price()*sign,c=p.get(8).price()*sign;
            if(!(a<top&&bb>a&&bb<top&&c<a&&c>p.getFirst().price()*sign))return null;
        }
        return new Signal("ELLIOTT_"+(correction?"ABC_":"IMPULSE_")+(sign>0?"UP":"DOWN"),
                correction?(sign>0?"BULLISH":"BEARISH"):"NEUTRAL","EXPLORATORY",b.get(p.getLast().knownIndex()).closeTime(),
                points(b,p,correction?new String[]{"0","1","2","3","4","5","A","B","C"}:new String[]{"0","1","2","3","4","5"}),null,null);
    }
}
