package com.github.prasanthj.hll;

import it.unimi.dsi.fastutil.doubles.Double2IntAVLTreeMap;
import it.unimi.dsi.fastutil.doubles.Double2IntSortedMap;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Map;

public class WeightedHyperLogLog{
    private final static int DEFAULT_HASH_BITS = 64;
    private final static long HASH64_ZERO = Murmur3.hash64(new byte[]{0});
    private final static long HASH64_ONE = Murmur3.hash64(new byte[]{1});
    private final static ByteBuffer SHORT_BUFFER = ByteBuffer.allocate(Short.BYTES);
    private final static ByteBuffer INT_BUFFER = ByteBuffer.allocate(Integer.BYTES);
    private final static ByteBuffer LONG_BUFFER = ByteBuffer.allocate(Long.BYTES);

    public enum EncodingType {
        SPARSE, DENSE
    }

    // number of bits to address registers
    private final int p;

    // number of registers - 2^p
    private final int m;

    // refer paper
    private float alphaMM;

    // enable/disable bias correction using table lookup
    private final boolean noBias;

    // enable/disable bitpacking
    private final boolean bitPacking;

    // Not making it configurable for perf reasons (avoid checks)
    private final int chosenHashBits = DEFAULT_HASH_BITS;

    private WHLLDenseRegister denseRegister;
    private HLLSparseRegister sparseRegister;

    // counts are cached to avoid repeated complex computation. If register value
    // is updated the count will be computed again.
    private long cachedCount;
    private boolean invalidateCount;

    private WeightedHyperLogLog.EncodingType encoding;

    // threshold to switch from SPARSE to DENSE encoding
    private int encodingSwitchThreshold;
    public static class WHLLBuilder {
        private int numRegisterIndexBits = 14;
        private WeightedHyperLogLog.EncodingType encoding = WeightedHyperLogLog.EncodingType.SPARSE;
        private boolean bitPacking = true;
        private boolean noBias = true;

        public WHLLBuilder() {
        }

        public WeightedHyperLogLog.WHLLBuilder setNumRegisterIndexBits(int b) {
            this.numRegisterIndexBits = b;
            return this;
        }

        public WeightedHyperLogLog.WHLLBuilder setEncoding(WeightedHyperLogLog.EncodingType enc) {
            this.encoding = enc;
            return this;
        }

        public WeightedHyperLogLog.WHLLBuilder enableBitPacking(boolean b) {
            this.bitPacking = b;
            return this;
        }

        public WeightedHyperLogLog.WHLLBuilder enableNoBias(boolean nb) {
            this.noBias = nb;
            return this;
        }

        public WeightedHyperLogLog build() {
            return new WeightedHyperLogLog(this);
        }
    }

    private WeightedHyperLogLog(WHLLBuilder hllBuilder){
        if (hllBuilder.numRegisterIndexBits < HLLConstants.MIN_P_VALUE
                || hllBuilder.numRegisterIndexBits > HLLConstants.MAX_P_VALUE) {
            throw new IllegalArgumentException("p value should be between " + HLLConstants.MIN_P_VALUE
                    + " to " + HLLConstants.MAX_P_VALUE);
        }
        this.p = hllBuilder.numRegisterIndexBits;
        this.m = 1 << p;
        this.noBias = hllBuilder.noBias;
        this.bitPacking = hllBuilder.bitPacking;

        // the threshold should be less than 12K bytes for p = 14.
        // The reason to divide by 5 is, in sparse mode after serialization the
        // entriesin sparse map are compressed, and delta encoded as varints. The
        // worst case size of varints are 5 bytes. Hence, 12K/5 ~= 2400 entries in
        // sparse map.
        if (bitPacking) {
            this.encodingSwitchThreshold = ((m * 6) / 8) / 5;
        } else {
            // if bitpacking is disabled, all register values takes 8 bits and hence
            // we can be more flexible with the threshold. For p=14, 16K/5 = 3200
            // entries in sparse map can be allowed.
            this.encodingSwitchThreshold = m / 3;
        }

        // initializeAlpha(DEFAULT_HASH_BITS);
        // alphaMM value for 128 bits hash seems to perform better for default 64 hash bits
        this.alphaMM = 0.7213f / (1 + 1.079f / m);
        // For efficiency alpha is multiplied by m^2
        this.alphaMM = this.alphaMM * m * m;

        this.cachedCount = -1;
        this.invalidateCount = false;
        this.encoding = hllBuilder.encoding;
        if (encoding.equals(HyperLogLog.EncodingType.SPARSE)) {
            this.sparseRegister = new HLLSparseRegister(p, HLLConstants.P_PRIME_VALUE,
                    HLLConstants.Q_PRIME_VALUE);
            this.denseRegister = null;
        } else {
            this.sparseRegister = null;
            this.denseRegister = new WHLLDenseRegister(p, bitPacking);
        }
    }

    public static WHLLBuilder builder(){ return new WHLLBuilder();}

    private void initializeAlpha(final int hashBits) {
        if (hashBits <= 16) {
            alphaMM = 0.673f;
        } else if (hashBits <= 32) {
            alphaMM = 0.697f;
        } else if (hashBits <= 64) {
            alphaMM = 0.709f;
        } else {
            alphaMM = 0.7213f / (float) (1 + 1.079f / m);
        }

        // For efficiency alpha is multiplied by m^2
        alphaMM = alphaMM * m * m;
    }

    public void addBoolean(boolean val, int weight) {
        add(val ? HASH64_ONE : HASH64_ZERO, weight);
    }

    public void addByte(byte val, int weight) {
        add(Murmur3.hash64(new byte[]{val}), weight);
    }

    public void addBytes(byte[] val, int weight) {
        add(Murmur3.hash64(val), weight);
    }

    public void addShort(short val, int weight) {
        SHORT_BUFFER.putShort(0, val);
        add(Murmur3.hash64(SHORT_BUFFER.array()), weight);
    }

    public void addInt(int val, double weight) {
        INT_BUFFER.putInt(0, val);
        try{
        add(Murmur3.hash64(INT_BUFFER.array()), weight);}
        catch (NullPointerException e){
            System.out.println("this elemnt's weight is "+ weight);
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void addLong(long val, int weight) {
        LONG_BUFFER.putLong(0, val);
        add(Murmur3.hash64(LONG_BUFFER.array()),weight);
    }

    public void addFloat(float val, int weight) {
        INT_BUFFER.putFloat(0, val);
        add(Murmur3.hash64(INT_BUFFER.array()), weight);
    }

    public void addDouble(double val, int weight) {
        LONG_BUFFER.putDouble(0, val);
        add(Murmur3.hash64(LONG_BUFFER.array()),weight);
    }

    public void addChar(char val, int weight) {
        SHORT_BUFFER.putChar(0, val);
        add(Murmur3.hash64(SHORT_BUFFER.array()),weight);
    }

    /**
     * Java's default charset will be used for strings.
     * @param val
     *          - input string
     */
    public void addString(String val, int weight) {
        add(Murmur3.hash64(val.getBytes()),weight);
    }

    public void addString(String val, Charset charset, int weight) {
        add(Murmur3.hash64(val.getBytes(charset)),weight);
    }

    public void add(long hashcode, double weight) {
        if (encoding.equals(HyperLogLog.EncodingType.SPARSE)) {
            if (sparseRegister.add(hashcode)) {
                invalidateCount = true;
            }

            // if size of sparse map excess the threshold convert the sparse map to
            // dense register and switch to DENSE encoding
            if (sparseRegister.getSize() > encodingSwitchThreshold) {
                encoding = WeightedHyperLogLog.EncodingType.DENSE;
                denseRegister = sparseToDenseRegister(sparseRegister); //TODO: solve
                sparseRegister = null;
                invalidateCount = true;
            }
        } else {
            if (denseRegister.my_add(hashcode, weight)) {
                invalidateCount = true;
            }
        }
    }

    public long count() {

        // compute count only if the register values are updated else return the
        // cached count
        if (invalidateCount || cachedCount < 0) {
            if (encoding.equals(HyperLogLog.EncodingType.SPARSE)) {

                // if encoding is still SPARSE use linear counting with increase
                // accuracy (as we use pPrime bits for register index)
                int mPrime = 1 << sparseRegister.getPPrime();
                cachedCount = linearCount(mPrime, mPrime - sparseRegister.getSparseMap().size());
            } else {
                System.out.println("I am here");
                // for DENSE encoding, use bias table lookup for HLLNoBias algorithm
                // else fallback to HLLOriginal algorithm
                double sum = denseRegister.getSumInversePow2();
                long numZeros = denseRegister.getNumZeroes();
                System.out.println("The estimators denominator is :"+ sum);
                // cardinality estimate from normalized bias corrected harmonic mean on
                // the registers
                cachedCount = (long) (alphaMM * (1.0 / sum));

                long pow = (long) Math.pow(2, chosenHashBits);
                // when bias correction is enabled
                if (noBias) {
                    System.out.println("I am correcting a bias for cachedCount "+cachedCount);
                    cachedCount = cachedCount <= 5 * m ? (cachedCount - estimateBias(cachedCount))
                            : cachedCount;
                    long h = cachedCount;
                    if (numZeros != 0) {
                        h = linearCount(m, numZeros);
                    }

                    if (h < getThreshold()) {
                        cachedCount = h;
                    }
                } else {
                    // HLL algorithm shows stronger bias for values in (2.5 * m) range.
                    // To compensate for this short range bias, linear counting is used
                    // for values before this short range. The original paper also says
                    // similar bias is seen for long range values due to hash collisions
                    // in range >1/30*(2^32). For the default case, we do not have to
                    // worry about this long range bias as the paper used 32-bit hashing
                    // and we use 64-bit hashing as default. 2^64 values are too high to
                    // observe long range bias (hash collisions).
                    if (cachedCount <= 2.5 * m) {

                        // for short range use linear counting
                        if (numZeros != 0) {
                            System.out.println("here");
                            cachedCount = linearCount(m, numZeros);
                        }
                    } else if (chosenHashBits < 64 && cachedCount > (0.033333 * pow)) {

                        // long range bias for 32-bit hashcodes
                        if (cachedCount > (1 / 30) * pow) {
                            cachedCount = (long) (-pow * Math.log(1.0 - (double) cachedCount / (double) pow));
                        }
                    }
                }
            }
            invalidateCount = false;
        }

        return cachedCount;
    }

    private long getThreshold() {
        return (long) (HLLConstants.thresholdData[p - 4] + 0.5);
    }

    /**
     * Estimate bias from lookup table
     * @param count
     *          - cardinality before bias correction
     * @return cardinality after bias correction
     */
    private long estimateBias(long count) {
        double[] rawEstForP = HLLConstants.rawEstimateData[p - 4];

        // compute distance and store it in sorted map
        Double2IntSortedMap estIndexMap = new Double2IntAVLTreeMap();
        double distance = 0;
        for (int i = 0; i < rawEstForP.length; i++) {
            distance = Math.pow(count - rawEstForP[i], 2);
            estIndexMap.put(distance, i);
        }

        // take top-k closest neighbors and compute the bias corrected cardinality
        long result = 0;
        double[] biasForP = HLLConstants.biasData[p - 4];
        double biasSum = 0;
        int kNeighbors = HLLConstants.K_NEAREST_NEIGHBOR;
        for (Map.Entry<Double, Integer> entry : estIndexMap.entrySet()) {
            biasSum += biasForP[entry.getValue()];
            kNeighbors--;
            if (kNeighbors <= 0) {
                break;
            }
        }

        // 0.5 added for rounding off
        result = (long) ((biasSum / HLLConstants.K_NEAREST_NEIGHBOR) + 0.5);
        return result;
    }

    public void setCount(long count) {
        this.cachedCount = count;
        this.invalidateCount = true;
    }

    private long linearCount(int mVal, long numZeros) {
        return (long) (Math.round(mVal * Math.log(mVal / ((double) numZeros))));
    }

    // refer paper
    public double getStandardError() {
        return 1.04 / Math.sqrt(m);
    }

    public HLLDenseRegister getHLLDenseRegister() {
        return denseRegister;
    }

    public HLLSparseRegister getHLLSparseRegister() {
        return sparseRegister;
    }

    /**
     * Reconstruct sparse map from serialized integer list
     * @param reg
     *          - uncompressed and delta decoded integer list
     */
    public void setHLLSparseRegister(int[] reg) {
        for (int i : reg) {
            int key = i >>> HLLConstants.Q_PRIME_VALUE;
            byte value = (byte) (i & 0x3f);
            sparseRegister.set(key, value);
        }
    }

    /**
     * Reconstruct dense registers from byte array
     * @param reg
     *          - unpacked byte array
     */
    public void setHLLDenseRegister(byte[] reg) {
        int i = 0;
        for (byte b : reg) {
            denseRegister.set(i, b);
            i++;
        }
    }

    /**
     * Converts sparse to dense hll register
     * @param sparseRegister
     *          - sparse register to be converted
     * @return converted dense register
     */
    //TODO: check behaviour because you did modifications without too much thinking
    private WHLLDenseRegister sparseToDenseRegister(HLLSparseRegister sparseRegister) {
        if (sparseRegister == null) {
            return null;
        }
        int p = sparseRegister.getP();
        int pMask = (1 << p) - 1;
        WHLLDenseRegister result = new WHLLDenseRegister(p, bitPacking);
        for (Map.Entry<Integer, Byte> entry : sparseRegister.getSparseMap().entrySet()) {
            int key = entry.getKey();
            int idx = key & pMask;
            result.set(idx, entry.getValue());
        }
        return result;
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Encoding: ");
        sb.append(encoding);
        sb.append(", p: ");
        sb.append(p);
        sb.append(", estimatedCardinality: ");
        sb.append(count());
        return sb.toString();
    }

    public String toStringExtended() {
        if (encoding.equals(HyperLogLog.EncodingType.DENSE)) {
            return toString() + ", " + denseRegister.toExtendedString();
        } else if (encoding.equals(HyperLogLog.EncodingType.SPARSE)) {
            return toString() + ", " + sparseRegister.toExtendedString();
        }

        return toString();
    }

    public int getNumRegisterIndexBits() {
        return p;
    }

    public WeightedHyperLogLog.EncodingType getEncoding() {
        return encoding;
    }

    public void setEncoding(WeightedHyperLogLog.EncodingType encoding) {
        this.encoding = encoding;
    }



    @Override
    public int hashCode() {
        int hashcode = 0;
        hashcode += 31 * p;
        hashcode += 31 * chosenHashBits;
        hashcode += encoding.hashCode();
        hashcode += 31 * count();
        if (encoding.equals(HyperLogLog.EncodingType.DENSE)) {
            hashcode += 31 * denseRegister.hashCode();
        }

        if (encoding.equals(HyperLogLog.EncodingType.SPARSE)) {
            hashcode += 31 * sparseRegister.hashCode();
        }
        return hashcode;
    }
}
