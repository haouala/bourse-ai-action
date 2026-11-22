package lu.bourse.ai.demo;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.text.DecimalFormat;

/**
 * Completely offline fixture-only demo. No backend, network permission or broker integration.
 * Fictional tickers prefixed DEMO to avoid confusion with live listed assets.
 */
public class DemoActivity extends Activity {
    private static final int NAVY = Color.rgb(12, 21, 42);
    private static final int SURFACE = Color.rgb(25, 38, 62);
    private static final int CARD = Color.rgb(30, 45, 71);
    private static final int WHITE = Color.rgb(244, 249, 255);
    private static final int MUTED = Color.rgb(167, 183, 207);
    private static final int TEAL = Color.rgb(76, 225, 184);
    private static final int RED = Color.rgb(255, 118, 136);
    private static final int GOLD = Color.rgb(255, 203, 119);

    private LinearLayout main, tabs;
    private String page = "Marchés";
    private String selection = "DEMO-CHIP";
    private int horizon = 30;

    private static class Asset {
        final String ticker, name, industry;
        final int opportunity, risk;
        final double price, move;
        Asset(String ticker, String name, String industry, double price, double move, int opportunity, int risk){
            this.ticker=ticker;this.name=name;this.industry=industry;this.price=price;this.move=move;
            this.opportunity=opportunity;this.risk=risk;
        }
    }
    private static final Asset[] ASSETS={
       new Asset("DEMO-CHIP","Aster Silicon","Semi-conducteurs",124.60,2.8,74,43),
       new Asset("DEMO-CLOUD","Nebula Cloud","Infrastructure IA",86.25,-1.4,68,51),
       new Asset("DEMO-POWER","VoltGrid Energy","Énergie & data centers",53.10,1.2,71,47),
       new Asset("DEMO-ROBOT","Orion Robotics","Automatisation",41.80,-2.1,59,68),
       new Asset("DEMO-FIBER","Photon Networks","Réseaux optiques",32.40,3.4,77,54)
    };
    private Asset chosen(){for(Asset a:ASSETS)if(a.ticker.equals(selection))return a;return ASSETS[0];}

    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);render();}

    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);
        return v;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams layout(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private LinearLayout.LayoutParams grow(){return new LinearLayout.LayoutParams(0,-2,1);}
    private void gap(LinearLayout l,int height){View v=new View(this);l.addView(v,layout(-1,height));}
    private void padding(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
    private LinearLayout card(){
       LinearLayout c=column();c.setBackground(shape(CARD,18));padding(c,17,16,17,16);
       LinearLayout.LayoutParams p=layout(-1,-2);p.bottomMargin=dp(12);main.addView(c,p);return c;
    }
    private String money(double x){return new DecimalFormat("#,##0.00").format(x)+" $";}
    private void render(){
       LinearLayout root=column();root.setBackgroundColor(NAVY);setContentView(root);
       LinearLayout top=column();padding(top,20,21,20,10);root.addView(top);
       TextView brand=text("◈  BOURSE AI",20,WHITE,true);top.addView(brand);
       gap(top,5);
       TextView sub=text("LABORATOIRE D'INVESTISSEMENT · MODE DÉMO",10,TEAL,true);top.addView(sub);
       gap(top,11);
       TextView banner=text("DONNÉES 100 % FICTIVES  •  AUCUN ORDRE RÉEL",11,GOLD,true);
       banner.setBackground(shape(SURFACE,10));padding(banner,11,10,11,10);top.addView(banner);
       ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);
       root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
       main=column();padding(main,18,18,18,8);scroller.addView(main);
       switch(page){
           case "Prévisions": forecasts();break;
           case "Portefeuille": portfolio();break;
           case "Alertes": alerts();break;
           default: markets();break;
       }
       tabs=row();tabs.setBackgroundColor(SURFACE);padding(tabs,7,11,7,12);root.addView(tabs);
       for(String tab:new String[]{"Marchés","Prévisions","Portefeuille","Alertes"}){
          TextView v=text(tab,12,tab.equals(page)?TEAL:MUTED,tab.equals(page));
          v.setGravity(Gravity.CENTER);v.setPadding(dp(2),dp(7),dp(2),dp(7));
          tabs.addView(v,new LinearLayout.LayoutParams(0,dp(44),1));
          v.setOnClickListener(x->{page=tab;render();});
       }
    }
    private void heading(String title,String subtitle){
       main.addView(text(title,23,WHITE,true));gap(main,4);main.addView(text(subtitle,12,MUTED,false));gap(main,18);
    }
    private void pair(LinearLayout c,String left,String right,int color){
       LinearLayout r=row();TextView a=text(left,13,MUTED,false);r.addView(a,grow());
       TextView b=text(right,14,color,true);r.addView(b);c.addView(r);gap(c,12);
    }
    private void markets(){
       heading("Panorama du marché","5 sociétés imaginaires pour tester les écrans et les scores");
       LinearLayout hero=card();
       hero.addView(text("OPPORTUNITÉS DE RECHERCHE",11,TEAL,true));gap(hero,7);
       hero.addView(text("5 actifs fictifs analysés",20,WHITE,true));gap(hero,8);
       hero.addView(text("Scores indicatifs sans rendement garanti. Ils ne constituent pas une recommandation.",12,MUTED,false));
       for(Asset a:ASSETS){
          LinearLayout c=card();
          LinearLayout first=row();LinearLayout names=column();first.addView(names,grow());
          names.addView(text(a.ticker,16,WHITE,true));gap(names,4);names.addView(text(a.name+" · "+a.industry,12,MUTED,false));
          TextView price=text(money(a.price),16,WHITE,true);first.addView(price);c.addView(first);gap(c,13);
          pair(c,"Variation fictive (jour)",(a.move>=0?"+":"")+a.move+" %",a.move>=0?TEAL:RED);
          pair(c,"Score opportunité",a.opportunity+" / 100",TEAL);
          pair(c,"Score risque",a.risk+" / 100",a.risk>60?RED:GOLD);
          TextView action=text("Explorer les scénarios  ›",13,TEAL,true);c.addView(action);
          c.setOnClickListener(v->{selection=a.ticker;page="Prévisions";render();});
       }
    }
    private void forecasts(){
       Asset a=chosen();
       heading("Prévisions fictives","Sélectionnez l'actif et l'horizon de simulation");
       HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);
       LinearLayout choices=row();
       for(Asset x:ASSETS){
          TextView button=text(x.ticker,12,x==a?NAVY:MUTED,true);
          button.setBackground(shape(x==a?TEAL:SURFACE,11));padding(button,12,9,12,9);
          LinearLayout.LayoutParams p=layout(-2,-2);p.rightMargin=dp(7);choices.addView(button,p);
          button.setOnClickListener(v->{selection=x.ticker;render();});
       }
       scroll.addView(choices);main.addView(scroll);gap(main,15);
       LinearLayout c=card();
       c.addView(text(a.name,18,WHITE,true));gap(c,5);c.addView(text("Dernier cours simulé : "+money(a.price),13,MUTED,false));gap(c,12);
       main.addView(new View(this),layout(-1,1));
       c.addView(new LineChart(this,a.price,120));
       c.addView(text("Courbe historique générée pour la démo — aucune donnée de marché réelle",11,MUTED,false));
       gap(main,6);
       LinearLayout horizons=row();for(int h:new int[]{7,30,90,180}){
          TextView b=text(h+" j",13,h==horizon?NAVY:MUTED,true);b.setGravity(Gravity.CENTER);
          b.setBackground(shape(h==horizon?TEAL:SURFACE,10));
          LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.rightMargin=dp(6);horizons.addView(b,p);
          b.setOnClickListener(v->{horizon=h;render();});
       }main.addView(horizons);gap(main,14);
       double factor=horizon/30.0;
       double central=a.price*(1+.036*Math.sqrt(factor));
       double pess=a.price*(1-.083*Math.sqrt(factor));
       double opt=a.price*(1+.129*Math.sqrt(factor));
       LinearLayout scenario=card();
       scenario.addView(text("SCÉNARIOS · HORIZON "+horizon+" JOURS",12,TEAL,true));gap(scenario,14);
       pair(scenario,"Pessimiste",money(pess),RED);
       pair(scenario,"Central",money(central),WHITE);
       pair(scenario,"Optimiste",money(opt),TEAL);
       scenario.addView(text("Ces valeurs sont inventées pour tester le design. Aucun modèle n'a calculé ces prévisions.",12,GOLD,false));
       LinearLayout evidence=card();evidence.addView(text("CONFIANCE / VALIDATION",12,TEAL,true));gap(evidence,9);
       evidence.addView(text("Non disponible en mode démo",17,WHITE,true));gap(evidence,6);
       evidence.addView(text("Les scores de fiabilité réels nécessitent des prédictions évaluées et des prix vérifiés.",12,MUTED,false));
    }
    private void portfolio(){
       heading("Portefeuille virtuel","Exemple fictif · aucun courtier connecté");
       LinearLayout hero=card();
       hero.addView(text("VALEUR TOTALE SIMULÉE",11,TEAL,true));gap(hero,7);
       hero.addView(text("1 087,40 $",32,WHITE,true));gap(hero,5);
       hero.addView(text("+87,40 $  (+8,74 %) · scénario inventé",13,TEAL,true));gap(hero,15);
       pair(hero,"Capital de départ","1 000,00 $",WHITE);
       pair(hero,"Solde disponible","242,00 $",WHITE);
       pair(hero,"Exposition simulée","845,40 $",GOLD);
       gap(main,6);
       LinearLayout chart=card();chart.addView(text("ÉVOLUTION DU PORTEFEUILLE",12,TEAL,true));
       chart.addView(new LineChart(this,1000,135));
       chart.addView(text("Trajectoire graphique fictive",11,MUTED,false));
       LinearLayout positions=card();positions.addView(text("POSITIONS DE TEST",12,TEAL,true));gap(positions,14);
       pair(positions,"DEMO-CHIP · 4 parts","498,40 $",WHITE);
       pair(positions,"DEMO-CLOUD · 2 parts","172,50 $",WHITE);
       pair(positions,"DEMO-POWER · 3 parts","159,30 $",WHITE);
       pair(positions,"Frais fictifs cumulés","1,25 $",GOLD);
       LinearLayout risk=card();risk.addView(text("DIAGNOSTIC DU RISQUE",12,TEAL,true));gap(risk,10);
       pair(risk,"Score risque simulé","48 / 100",GOLD);
       pair(risk,"Volatilité simulée","24,6 %",WHITE);
       risk.addView(text("Les positions, performances et statistiques sont exclusivement des données de démonstration.",12,MUTED,false));
    }
    private void alerts(){
       heading("Signaux & alertes","Exemples d'événements générés localement");
       String[][] entries={
          {"PÉNURIE IA · TEST","DEMO-CHIP","Signal fictif : capacité de fabrication sous tension","POSITIF"},
          {"VOLATILITÉ · TEST","DEMO-ROBOT","Signal fictif : volatilité élevée sur l'historique","RISQUE"},
          {"SEUIL DE PRIX · TEST","DEMO-FIBER","Signal fictif : cours supérieur au seuil simulé","INFO"},
          {"ÉVÉNEMENT ML · TEST","DEMO-CLOUD","Évaluation fictive : modèle à surveiller","REVUE"}
       };
       for(String[] e:entries){
          LinearLayout c=card();c.addView(text(e[0],11,TEAL,true));gap(c,9);
          c.addView(text(e[1],18,WHITE,true));gap(c,8);
          c.addView(text(e[2],13,MUTED,false));gap(c,8);
          c.addView(text(e[3],12,e[3].equals("RISQUE")?RED:GOLD,true));
       }
       LinearLayout note=card();note.addView(text("NOTIFICATIONS DÉSACTIVÉES",13,GOLD,true));gap(note,7);
       note.addView(text("Cette APK ne consulte aucun serveur, ne collecte aucune donnée personnelle et n'envoie aucun ordre. Les alertes sont des exemples fixes.",12,MUTED,false));
    }
    /** Deterministic local drawing; labels never imply market authenticity. */
    private class LineChart extends View {
       private final double base;private final int chartHeight;
       private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
       LineChart(Activity context,double base,int h){super(context);this.base=base;this.chartHeight=h;setLayoutParams(layout(-1,h));}
       @Override protected void onDraw(Canvas canvas){
          super.onDraw(canvas);
          float w=getWidth(),h=getHeight();if(w==0||h==0)return;
          paint.setStrokeWidth(dp(1));paint.setColor(Color.rgb(62,80,108));
          for(int i=1;i<=3;i++){float y=h*i/4;canvas.drawLine(0,y,w,y,paint);}
          Path path=new Path();
          for(int i=0;i<70;i++){
             float x=(i/69f)*w;
             double value=Math.sin(i*.3)*.11+Math.cos(i*.11)*.13+i*.008;
             float y=(float)(h*.68-value*h*.22);
             if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
          }
          paint.setColor(TEAL);paint.setStrokeWidth(dp(2.7f));paint.setStyle(Paint.Style.STROKE);
          canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
       }
    }
}
