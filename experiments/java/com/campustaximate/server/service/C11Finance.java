package com.campustaximate.server.service;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;import java.nio.file.*;import java.util.*;

/** Operator reserve and separate objection bridge; no real financial transaction. */
public final class C11Finance {
    record Fixed(List<Long> shares,long grossPayerReimbursement,long netPayerTransfer,long reserveDelta,long objectionDelta,int extra,int refund) {}
    static Fixed fixed(long quote,long meter,List<Long> units,int payer,boolean exceptional) {
        var shares=SettlementCalculator.shares(quote,SettlementCalculator.ratioUnits(units),payer);
        long delta=quote-meter;
        var result=new Fixed(shares,meter,meter-shares.get(payer),exceptional?0:delta,exceptional?delta:0,0,0);
        if(shares.stream().mapToLong(Long::longValue).sum()-meter!=result.reserveDelta()+result.objectionDelta())throw new AssertionError("operator conservation");
        if(shares.get(payer)+meter-result.grossPayerReimbursement()!=shares.get(payer))throw new AssertionError("payer net cost");
        return result;
    }
    static List<Long> longs(String s){return Arrays.stream(s.split(";")).map(Long::parseLong).toList();}
    static String join(Collection<?> x){return String.join(";",x.stream().map(Object::toString).toList());}
    static long actual(long quote,double error){return DetailedSimulation.actual(quote,Math.max(-.9999999999999999,error));}
    /**
     * Widen or narrow an empirical fare error around its mean, kept inside the observed error range.
     * The plain additive mean+spread*(error-mean) pushed 16 of 41,391 TIMS residuals below -100%
     * at spread 2; actual() then clamped those fares to 1 KRW and broke per-rider saving ratios.
     * Clamping to [min, max] of the observed residuals keeps every fare within what TIMS ever showed.
     * spread == 1 returns the observed error unchanged, so baseline results are identical.
     */
    static double scaled(double error,double mean,double spread,double lo,double hi){
        if(spread==1)return error;
        return Math.min(hi,Math.max(lo,mean+spread*(error-mean)));
    }
    static void export(String[] args)throws Exception {
        double b=Double.parseDouble(args[3]);double[] errors=Files.readAllLines(Path.of(args[2])).stream().filter(x->!x.isBlank()).mapToDouble(Double::parseDouble).toArray();
        double mean=Arrays.stream(errors).average().orElseThrow(),lo=Arrays.stream(errors).min().orElseThrow(),hi=Arrays.stream(errors).max().orElseThrow();
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[4])))) {
            out.println("source,n,day,group_id,confirm,finish,margin,spread,raw_quote,fixed_quote,zero_quote,meter,size,exceptional,reserve_delta,objection_delta,min_saving_violations,fixed_harmed_quote,fixed_harmed_actual,fixed_saving_quote_sum,fixed_saving_actual_sum,current_saving_actual_sum,fixed_saving_krw,current_saving_krw,payer_net_transfer,extra,refund,conservation_errors");
            for(String line:Files.readAllLines(Path.of(args[1]))){var p=line.split("	");if(p[0].equals("source"))continue;
                long raw=Long.parseLong(p[6]);int ei=Integer.parseInt(p[7]);var units=longs(p[8]);var se=Arrays.stream(p[9].split(";")).mapToDouble(Double::parseDouble).toArray();
                for(double margin:C11Simulation.MARGINS){if(!p[10].equals("all")&&Double.parseDouble(p[10])!=margin)continue;
                    long quote=Math.round(raw*(1+b+margin)),zero=Math.round(raw*(1+b));
                    // Expected solo cost carries the same quote bias b; the min-saving baseline and ratio units use it.
                    var expectedSolo=units.stream().map(u->Math.max(1,Math.round(u*(1+b)))).toList();
                    for(double spread:new double[]{.5,1,1.5,2}) {
                        long meter=actual(raw,scaled(errors[ei],mean,spread,lo,hi));boolean exceptional=meter>raw*1.5;
                        var f=fixed(quote,meter,expectedSolo,units.size()-1,exceptional);var current=SettlementCalculator.shares(meter,units,units.size()-1);
                        int violations=0,hq=0,ha=0;double sq=0,sa=0,cs=0;long sk=0,ck=0;
                        for(int i=0;i<units.size();i++) {long solo=actual(units.get(i),scaled(se[i],mean,spread,lo,hi));long cost=f.shares().get(i);
                            long es=expectedSolo.get(i);if(cost*10>es*9)violations++;if(cost>es)hq++;if(cost>solo)ha++;
                            sq+=1-cost/(double)es;sa+=1-cost/(double)solo;cs+=1-current.get(i)/(double)solo;
                            sk+=solo-cost;ck+=solo-current.get(i);
                        }
                        if((p[0].equals("PRECHECK")||p[0].equals("CURRENT"))&&violations!=0)throw new AssertionError("marked price saving guard");
                        out.printf(Locale.ROOT,"%s,%s,%s,%s,%s,%s,%.3f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.15f,%.15f,%.15f,%d,%d,%d,0,0,0%n",
                            p[0],p[1],p[2],p[3],p[4],p[5],margin,spread,raw,quote,zero,meter,units.size(),exceptional?1:0,
                            f.reserveDelta(),f.objectionDelta(),violations,hq,ha,sq,sa,cs,sk,ck,f.netPayerTransfer());
                    }
                }
            }
        }
    }
    static final class Ride {int confirm,finish,size;long normal,zero,meter;boolean exceptional;}
    static final class Day {List<Ride> rides=new ArrayList<>();List<int[]> events;void prepare(){events=new ArrayList<>();for(int i=0;i<rides.size();i++){var r=rides.get(i);events.add(new int[]{r.confirm,0,i});events.add(new int[]{r.finish,1,i});}events.sort(Comparator.<int[]>comparingInt(e->e[0]).thenComparingInt(e->-e[1]).thenComparingInt(e->e[2]));}}
    static void surplus(String[] args)throws Exception {
        Map<String,Day[]> cohorts=new LinkedHashMap<>();
        for(String line:Files.readAllLines(Path.of(args[1]))){var p=line.split("	");if(p[0].equals("key"))continue;
            var days=cohorts.computeIfAbsent(p[0],k->{var a=new Day[30];for(int i=0;i<30;i++)a[i]=new Day();return a;});
            var r=new Ride();r.confirm=Integer.parseInt(p[2]);r.finish=Integer.parseInt(p[3]);r.normal=Long.parseLong(p[4]);r.zero=Long.parseLong(p[5]);r.meter=Long.parseLong(p[6]);r.exceptional=p[7].equals("1");r.size=Integer.parseInt(p[8]);days[Integer.parseInt(p[1])].rides.add(r);
        }
        for(var ds:cohorts.values())for(var d:ds)d.prepare();
        Map<String,Long> seeds=new HashMap<>();for(String line:Files.readAllLines(Path.of(args[2]))){var p=line.split("	");seeds.put(p[0],Long.parseLong(p[1]));}
        var paths=Files.readAllLines(Path.of(args[3])).stream().map(line->Arrays.stream(line.split("	")).mapToInt(Integer::parseInt).toArray()).toList();
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[4])))) {
            out.println("key,path,ending,min_balance,zero_margin_rides,total_rides,price_reduction,active_boardings,conservation_errors");
            for(var entry:cohorts.entrySet()) {
                String key=entry.getKey();long seed=seeds.get(key),cap=seed*2;int pi=0;
                for(var path:paths) {long balance=seed,minimum=seed,reduction=0,boardings=0,operatorDelta=0,objectionDelta=0;int low=0,total=0;
                    for(int day:path) {var d=entry.getValue()[day];long[] prices=new long[d.rides.size()];
                        for(var event:d.events){var r=d.rides.get(event[2]);if(event[1]==0){boolean zero=balance>cap;prices[event[2]]=zero?r.zero:r.normal;if(zero)low++;total++;boardings+=r.size;reduction+=r.normal-prices[event[2]];}
                            else {long delta=prices[event[2]]-r.meter;operatorDelta+=delta;if(r.exceptional)objectionDelta+=delta;else balance+=delta;minimum=Math.min(minimum,balance);}}
                    }
                    if(balance-seed+objectionDelta!=operatorDelta)throw new AssertionError("surplus operator cash conservation");
                    out.println(key+","+(pi++)+","+balance+","+minimum+","+low+","+total+","+reduction+","+boardings+",0");
                }
                out.flush();System.err.println("C11 surplus "+key);
            }
        }
    }
    static void selfTest(){
        var f=fixed(22000,25000,List.of(10000L,10000L),1,false);
        if(f.extra()!=0||f.refund()!=0||f.grossPayerReimbursement()!=25000||f.netPayerTransfer()!=14000||f.reserveDelta()!=-3000||f.objectionDelta()!=0)throw new AssertionError("fixed price cash ledger");
        var e=fixed(22000,35000,List.of(10000L,10000L),1,true);
        if(e.reserveDelta()!=0||e.objectionDelta()!=-13000||e.grossPayerReimbursement()!=35000)throw new AssertionError("objection bridge");
        var low=fixed(22000,5000,List.of(10000L,10000L),1,false);
        if(low.netPayerTransfer()!=-6000||low.reserveDelta()!=17000)throw new AssertionError("negative net transfer");
        // Error scaling: baseline unchanged, widened errors stay inside the observed range (no 1 KRW clamp).
        if(scaled(.25,0,1,-.8,47)!=.25)throw new AssertionError("spread 1 must keep the observed error");
        if(scaled(-.7,.07,2,-.8193,47.27)!=-.8193)throw new AssertionError("widened error must stop at the observed minimum");
        if(Math.abs(scaled(.4,0,2,-.8,47)-.8)>1e-12||Math.abs(scaled(.4,0,.5,-.8,47)-.2)>1e-12)throw new AssertionError("spread must widen or narrow around the mean");
        System.out.println("C11 fixed shares, exact payer reimbursement and operator conservation passed");
    }
    public static void main(String[] args)throws Exception {switch(args[0]){case "--self-test"->selfTest();case "--fixed"->export(args);case "--surplus"->surplus(args);default->throw new IllegalArgumentException("unknown mode");}}
}
