package com.github.prasanthj.hll;

import java.util.*;

public class Main {

    public static void main(String[] args){

        int elements = 10;
        int seed = 111;

        ArrayList<Integer> myList = new ArrayList<Integer>();
        Random rnd = new Random(seed);

        for (int i =0; i<elements; i++){
            myList.add(Integer.valueOf(rnd.nextInt(20)+1));
        }

        //count actual distinct numbers
        int realCardinality = countActualDistinctList(myList);
        System.out.println("The cardinality is :"+ realCardinality);

        HyperLogLog hll1 = HyperLogLog.builder()
                .setEncoding(HyperLogLog.EncodingType.DENSE)
                .setNumRegisterIndexBits(14)
                .build();

        HyperLogLog hll2 = HyperLogLog.builder()
                .setEncoding(HyperLogLog.EncodingType.SPARSE)
                .setNumRegisterIndexBits(4)
                .build();

        //adding the elements
        int current;
        Iterator<Integer> iter = myList.iterator();
        while (iter.hasNext()){
            current = iter.next();
            hll1.addInt(current);
            hll2.addInt(current);
        }
        long counter1 = hll1.count();
        long counter2 = hll2.count();

        System.out.println("The counter for Sketch 1 is:"+ counter1);
        System.out.println("The counter for Sketch 2 is:"+ counter2);



        WeightedHyperLogLog whll = WeightedHyperLogLog.builder()
                .setEncoding(WeightedHyperLogLog.EncodingType.DENSE)
                .setNumRegisterIndexBits(6)
                .build();
        System.out.println("Number of registers: "+ whll.getHLLDenseRegister());
        Iterator<Integer> iter2 = myList.iterator();
        while(iter2.hasNext()){
            current = iter2.next();

            whll.addInt(current,5);
        }
        long wCounter = whll.count();
        System.out.println("The counter for Sketch Weighted is:"+ wCounter);
        System.out.println("The actual weighted sum is :"+ countWeightedAggregatedSum(myList) );
    }

    private static int countActualDistinctList(ArrayList<Integer> myList){
        HashMap<Integer, Integer> counter = new HashMap<>();
        Iterator<Integer> iter = myList.iterator();
        while(iter.hasNext()){

            counter.put(iter.next(), 0);
        }

        return counter.size();
    }

    private static int countWeightedAggregatedSum(ArrayList<Integer> myList){
        HashMap<Integer, Integer> counter = new HashMap<>();
        Iterator<Integer> iter = myList.iterator();
        int wSum = 0;
        while(iter.hasNext()){
            int current = iter.next();
            counter.put(current, current);
        }
        Iterator<Integer> coll = counter.values().iterator();
        while (coll.hasNext()){
            int current =coll.next();
            wSum += 5;
        }
        return wSum;
    }
}
