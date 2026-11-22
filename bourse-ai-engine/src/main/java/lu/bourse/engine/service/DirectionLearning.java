package lu.bourse.engine.service;

import java.util.*;

/**
 * Small deterministic logistic regression baseline.
 * Uses only price-derived features known at signal time.
 * Train-only normalization and a label-availability embargo prevent lookahead.
 */
public final class DirectionLearning {
 private DirectionLearning(){}
 public static final String VERSION="LOGIT_PRICE_V1";
 public static final String FEATURES="LOG_RETURN_5,LOG_RETURN_20,REALIZED_VOLATILITY_20";
 public record Observation(double[] features,int up,int featureIndex,int outcomeIndex){}
 public record Fold(int trainingEndIndex,int testStartIndex,int trained,int tested,
                    double accuracy,double baselineAccuracy,double brier,double baselineBrier){}
 public record Evaluation(int horizonSessions,int observations,int trainSamples,int testSamples,
                          double accuracy,double baselineAccuracy,double brier,double baselineBrier,
                          List<Fold> folds){}
 private record Model(double[] weights,double[] means,double[] scales){}

 public static Evaluation evaluate(List<Double> closes,int horizon) {
  if(!Set.of(5,10,20).contains(horizon)||closes==null||closes.size()<240||closes.size()>10000)
   throw new IllegalArgumentException("Need 240..10000 valid daily closes and horizon 5,10 or 20");
  for(Double close:closes)if(close==null||!Double.isFinite(close)||close<=0)
   throw new IllegalArgumentException("Invalid close");
  List<Observation> examples=new ArrayList<>();
  for(int i=25;i+horizon<closes.size();i++){
   double m5=Math.log(closes.get(i)/closes.get(i-5));
   double m20=Math.log(closes.get(i)/closes.get(i-20));
   double mean=0,squares=0;
   for(int j=i-19;j<=i;j++){
    double r=Math.log(closes.get(j)/closes.get(j-1));
    mean+=r;squares+=r*r;
   }
   mean/=20;
   double volatility=Math.sqrt(Math.max(0,squares/20-mean*mean));
   examples.add(new Observation(new double[]{m5,m20,volatility},closes.get(i+horizon)>closes.get(i)?1:0,i,i+horizon));
  }
  int n=closes.size();
  int[] boundaries={n*55/100,n*70/100,n*85/100,n-horizon};
  List<Fold> folds=new ArrayList<>();
  double correct=0,baselineCorrect=0,brierSum=0,baselineBrierSum=0;
  int totalTest=0,totalTrain=0;
  for(int k=0;k<3;k++){
   int trainEnd=boundaries[k],testStart=trainEnd;
   int testEnd=boundaries[k+1];
   // Training labels must be observable strictly before test starts.
   List<Observation> train=new ArrayList<>(),test=new ArrayList<>();
   for(Observation obs:examples){
    if(obs.outcomeIndex()<trainEnd)train.add(obs);
    if(obs.featureIndex()>=testStart && obs.featureIndex()<testEnd && obs.outcomeIndex()<n)test.add(obs);
   }
   if(train.size()<80||test.size()<20)
    throw new IllegalArgumentException("Insufficient leakage-safe train/test observations");
   Model model=fit(train);
   double prevalence=train.stream().mapToInt(Observation::up).average().orElse(.5);
   double foldCorrect=0,foldBaseCorrect=0,foldBrier=0,foldBaseBrier=0;
   for(Observation obs:test){
    double p=predict(model,obs.features());
    foldCorrect+=(p>=.5?1:0)==obs.up()?1:0;
    foldBaseCorrect+=(prevalence>=.5?1:0)==obs.up()?1:0;
    foldBrier+=Math.pow(p-obs.up(),2);
    foldBaseBrier+=Math.pow(prevalence-obs.up(),2);
   }
   int count=test.size();
   folds.add(new Fold(trainEnd,testStart,train.size(),count,
    foldCorrect/count,foldBaseCorrect/count,foldBrier/count,foldBaseBrier/count));
   correct+=foldCorrect;baselineCorrect+=foldBaseCorrect;brierSum+=foldBrier;
   baselineBrierSum+=foldBaseBrier;totalTest+=count;totalTrain=train.size();
  }
  return new Evaluation(horizon,n,totalTrain,totalTest,correct/totalTest,
   baselineCorrect/totalTest,brierSum/totalTest,baselineBrierSum/totalTest,List.copyOf(folds));
 }
 private static Model fit(List<Observation> samples){
  final int size=3;
  double[] means=new double[size],scales=new double[size],weights=new double[size+1];
  for(Observation s:samples)for(int j=0;j<size;j++)means[j]+=s.features()[j];
  for(int j=0;j<size;j++)means[j]/=samples.size();
  for(Observation s:samples)for(int j=0;j<size;j++)scales[j]+=Math.pow(s.features()[j]-means[j],2);
  for(int j=0;j<size;j++)scales[j]=Math.max(1e-8,Math.sqrt(scales[j]/samples.size()));
  // Fixed iterations and shrinkage ensure deterministic, bounded training.
  for(int iter=0;iter<350;iter++){
   double[] grad=new double[size+1];
   for(Observation s:samples){
    double z=weights[0];
    for(int j=0;j<size;j++)z+=weights[j+1]*clamp((s.features()[j]-means[j])/scales[j],-8,8);
    double error=sigmoid(z)-s.up();
    grad[0]+=error;
    for(int j=0;j<size;j++)grad[j+1]+=error*clamp((s.features()[j]-means[j])/scales[j],-8,8);
   }
   weights[0]-=.08*grad[0]/samples.size();
   for(int j=1;j<weights.length;j++)weights[j]-=.08*(grad[j]/samples.size()+.02*weights[j]);
  }
  return new Model(weights,means,scales);
 }
 private static double predict(Model model,double[] features){
  double z=model.weights()[0];
  for(int j=0;j<features.length;j++)
   z+=model.weights()[j+1]*clamp((features[j]-model.means()[j])/model.scales()[j],-8,8);
  return sigmoid(z);
 }
 private static double sigmoid(double x){
  if(x>=0){double e=Math.exp(-Math.min(50,x));return 1/(1+e);}
  double e=Math.exp(Math.max(-50,x));return e/(1+e);
 }
 private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
}
