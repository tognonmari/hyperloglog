package com.github.prasanthj.hll;

import java.util.Arrays;
import java.util.Random;

public class WHLLDenseRegister extends HLLDenseRegister{
    /*
    * // 2^p number of bytes for register
    private byte[] register;

    // max value stored in registered is cached to determine the bit width for
    // bit packing
    private int maxRegisterValue;

    // number of register bits
    private int p;

    // m = 2^p
    private int m;
    * */


    public WHLLDenseRegister(int p) {
        this(p, true);
    }

    // TODO: Valutare se basta inserire il ".super()" o se serve altro
    public WHLLDenseRegister(int p, boolean bitPack) {
        super(p,bitPack);
    }


    public boolean add(long hashcode, double weight) {//TODO: riscrivi da capo
        if( weight<=0){
            throw new IllegalArgumentException("Weigths must be strictly positive. Provided Weight: "+ weight);
        }

        // LSB p bits
        final int registerIdx = (int) (hashcode & (m - 1));

        // MSB 64 - p bits

        final long w = hashcode >>> p;

        final int lr = Long.numberOfTrailingZeros(w) + 1;

        //return set(registerIdx, (byte) lr);

        //WEIGHTED VERSION
        double unif01 = (w+1)/(Math.pow(2, 64-p));

        double h2Tilde = 1 - Math.pow(1.0 - unif01, 1.0/weight);

        //System.out.println("I am printing the unif01 value: "+ unif01);
        double log2Unif = Math.log(h2Tilde)/Math.log(2);

        int toBeInserted = ((int) Math.floor(-log2Unif));
        System.out.println("I am inserting in the registers the value :"+ toBeInserted + ", rather than the tail of trailing 0's : "+ lr);
        return set(registerIdx, (byte) (toBeInserted + 1));


    }

    //TODO: CHECK computePi for correctness!!
    public boolean yet_another_add(long hashcode, double weight){

        if( weight<=0){
            throw new IllegalArgumentException("Weigths must be strictly positive. Provided Weight: "+ weight);
        }

        // LSB p bits
        final int registerIdx = (int) (hashcode & (m - 1));

        // MSB 64 - p bits

        final long w = hashcode >>> p;

        double wToDecimal = (double) (w+1)/(Math.pow(2.0,(64-p)));
        double elevatedW = Math.pow(wToDecimal, 1.0/( weight));
        long eWToLong = (long) (elevatedW * Math.pow(2.0,(64-p)));
        long lr = Long.numberOfTrailingZeros(eWToLong)+1;
        //System.out.println("My rarity Measure is : "+ rarityMeasure);
        return set(registerIdx, (byte) lr);
    }
    public boolean my_add(long hashcode, double weight){
        if (weight<= 0){
            throw new IllegalArgumentException("Weights must be stricly positive. Provided weight : "+ weight);
        }

        final int registerIdx = (int) (hashcode & (m-1));

        final long w = hashcode >>> p;

        int lr = Long.numberOfTrailingZeros(w)+1;

        boolean obtainedNewTail = false;

        int i = 0;

        double p_i = computePi(lr, i, weight); //p_0
        System.out.println("My p_i is : "+ p_i);
        Random rnd = new Random(); //seed is very likely ot be different from one invocation of the constructor to another
        double f = rnd.nextDouble();
        while(f>p_i){

            System.out.println("My pi is now  for iteration "+i+" : "+p_i);
            i++;
            p_i = computePi(lr,i,weight);

            f = rnd.nextDouble();
        }
        int newTail = lr + i;
        return set(registerIdx, (byte) (newTail)); //Il + 1 l'ho aggiunto per analogia all'implementazione di base
    }
    // this is a lossy invert of the function above, which produces a hashcode
    // which collides with the current winner of the register (we lose all higher
    // bits, but we get all bits useful for lesser p-bit options)

    // +-------------|-------------+
    // |xxxx100000000|1000000000000|  (lr=9 + idx=1024)
    // +-------------|-------------+
    //                \
    // +---------------|-----------+
    // |xxxx10000000010|00000000000|  (lr=2 + idx=0)
    // +---------------|-----------+

    // This shows the relevant bits of the original hash value
    // and how the conversion is moving bits from the index value
    // over to the leading zero computation
    private static double computePi(int lr, int iteration, double weight){
        double base = 1 - Math.pow(1.0/2.0,  (lr + iteration) );

        return Math.pow(base, weight-1);
    }
    public void extractLowBitsTo(HLLRegister dest) {
        for (int idx = 0; idx < register.length; idx++) {
            byte lr = register[idx]; // this can be a max of 65, never > 127
            if (lr != 0) {
                dest.add((long) ((1 << (p + lr - 1)) | idx));
            }
        }
    }

    public boolean set(int idx, byte value) {
        boolean updated = false;
        if (idx < register.length && value > register[idx]) {

            // update max register value
            if (value > maxRegisterValue) {
                maxRegisterValue = value;
            }

            // set register value and compute inverse pow of 2 for register value
            register[idx] = value;

            updated = true;
        }
        return updated;
    }

    public int size() {
        return register.length;
    }

    public int getNumZeroes() {
        int numZeroes = 0;
        for (byte b : register) {
            if (b == 0) {
                numZeroes++;
            }
        }
        return numZeroes;
    }

    public void merge(HLLRegister hllRegister) {
        if (hllRegister instanceof HLLDenseRegister) {
            HLLDenseRegister hdr = (HLLDenseRegister) hllRegister;
            byte[] inRegister = hdr.getRegister();

            // merge only if the register length matches
            if (register.length != inRegister.length) {
                throw new IllegalArgumentException(
                        "The size of register sets of HyperLogLogs to be merged does not match.");
            }

            // compare register values and store the max register value
            for (int i = 0; i < inRegister.length; i++) {
                final byte cb = register[i];
                final byte ob = inRegister[i];
                register[i] = ob > cb ? ob : cb;
            }

            // update max register value
            if (hdr.getMaxRegisterValue() > maxRegisterValue) {
                maxRegisterValue = hdr.getMaxRegisterValue();
            }
        } else {
            throw new IllegalArgumentException("Specified register is not instance of HLLDenseRegister");
        }
    }

    public byte[] getRegister() {
        return register;
    }

    public void setRegister(byte[] register) {
        this.register = register;
    }

    public int getMaxRegisterValue() {
        return maxRegisterValue;
    }

    public double getSumInversePow2() {
        double sum = 0;
        for (byte b : register) {
            sum += HLLConstants.inversePow2Data[b];
        }
        return sum;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HLLDenseRegister - ");
        sb.append("p: ");
        sb.append(p);
        sb.append(" numZeroes: ");
        sb.append(getNumZeroes());
        sb.append(" maxRegisterValue: ");
        sb.append(maxRegisterValue);
        return sb.toString();
    }

    public String toExtendedString() {
        return toString() + " register: " + Arrays.toString(register);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HLLDenseRegister)) {
            return false;
        }
        HLLDenseRegister other = (HLLDenseRegister) obj;
        return getNumZeroes() == other.getNumZeroes() && maxRegisterValue == other.maxRegisterValue
                && Arrays.equals(register, other.register);
    }

    @Override
    public int hashCode() {
        int hashcode = 0;
        hashcode += 31 * getNumZeroes();
        hashcode += 31 * maxRegisterValue;
        hashcode += Arrays.hashCode(register);
        return hashcode;
    }
}
