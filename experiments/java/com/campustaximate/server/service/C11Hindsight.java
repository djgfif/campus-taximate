package com.campustaximate.server.service;
import java.nio.file.*;import java.io.*;import java.util.*;
/** C8's exhaustive production-verified offline feasible universe, for new 300/day cohort. */
public final class C11Hindsight {
 public static void main(String[] args)throws Exception {
  var cases=ExperimentRunner.readCases(Path.of(args[0]));var configs=DetailedSimulation.configs(Path.of(args[0]));
  var matrix=C7RoadMatrix.read(Path.of(args[1]));double[] errors=Files.readAllLines(Path.of(args[2])).stream().filter(s->!s.isBlank()).mapToDouble(Double::parseDouble).toArray();
  try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])))) {
   for(var c:cases)if(c.requests.size()==300){var d=new OptimalityExport.Day(c,configs.get(c.id),matrix,errors,out);
    out.println("D\t"+c.id+"\t"+d.all.size()+"\t"+d.cfg.seed());
    for(var r:d.all)out.println("I\t"+r.id()+"\t"+(r.requestedAt().getEpochSecond()-OptimalityExport.BASE.getEpochSecond())+"\t"+d.expiry.get(r.id())+"\t"+OptimalityExport.window(r)[0]+"\t"+OptimalityExport.window(r)[1]);
    d.combinations(d.all,0,new ArrayList<>(),OptimalityExport.BASE,d.verifier(OptimalityExport.BASE),new ArrayList<>(),true,Long.MIN_VALUE,Long.MAX_VALUE);
    out.println("H\t"+String.join(";",new TreeSet<>(d.hindsight)));out.println("END");out.flush();System.err.println("C11 hindsight "+c.id+" groups="+d.stats.size());
   }
  }
 }
}
