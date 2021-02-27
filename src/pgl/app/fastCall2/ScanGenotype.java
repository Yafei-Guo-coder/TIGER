package pgl.app.fastCall2;

import com.koloboke.collect.map.IntDoubleMap;
import com.koloboke.collect.map.hash.HashIntDoubleMaps;
import pgl.PGLConstraints;
import pgl.app.hapScanner.HapScanner;
import pgl.infra.dna.FastaBit;
import pgl.infra.dna.FastaRecordBit;
import pgl.infra.utils.Benchmark;
import pgl.infra.utils.IOUtils;
import pgl.infra.utils.PStringUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static cern.jet.math.Arithmetic.factorial;

class ScanGenotype {
    //Reference genome file with an index file (.fai). The reference should be in Fasta format. Chromosomes are labled as 1-based numbers (1,2,3,4,5...).
    String referenceFileS = null;
    //The taxaRefBam file containing information of taxon and its corresponding bam files. The bam file should have .bai file in the same folder
    String taxaRefBamFileS = null;
    //The genetic variation library file
    String libFileS = null;
    int chrom = Integer.MIN_VALUE;
    //Starting position of the specified region for variation calling, inclusive
    int regionStart = Integer.MIN_VALUE;
    //Ending position the specified regionfor variation calling, exclusive
    int regionEnd = Integer.MIN_VALUE;
    //combined: sequencing error and alignment error
    double combinedErrorRate = 0.05;
    //The path of samtools
    String samtoolsPath = null;
    //VCF output directory
    String outputDirS = null;
    //Number of threads (taxa number to be processed at the same time)
    int threadsNum = PGLConstraints.parallelLevel;

    String[] subDirS = {"mpileup", "indiVCF", "VCF"};

    HashMap<String, List<String>> taxaBamsMap = null;
    HashMap<String, Double> taxaCoverageMap = null;
    String[] taxaNames = null;

    IntDoubleMap factorialMap = null;
    int maxFactorial = 150;

    String vLibPosFileS = null;

    FastaBit genomeFa = null;
    int chromIndex = Integer.MIN_VALUE;
    VariationLibrary vl = null;
    int vlStartIndex = Integer.MIN_VALUE;
    int vlEndIndex = Integer.MIN_VALUE;
    HashMap<Integer, String> posRefMap = new HashMap<>();
//    HashMap<Integer, String[]> posAltMap = new HashMap<>();
    HashMap<Integer, byte[]> posCodedAlleleMap = new HashMap<>();
    int[] positions = null;

    public ScanGenotype (List<String> pLineList) {
        this.parseParameters(pLineList);
        this.mkDir();
        this.processVariationLibrary();
        this.creatFactorialMap();
        this.scanIndiVCFByThreadPool();
        this.mkFinalVCF();
    }

    public void mkFinalVCF () {

        String outfileS = new File(outputDirS, subDirS[2]).getAbsolutePath();
        outfileS = new File(outfileS, "chr"+PStringUtils.getNDigitNumber(3, chrom)+".vcf").getAbsolutePath();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS");
            Date dt = new Date();
            String S = sdf.format(dt);
            BufferedWriter bw = IOUtils.getTextWriter(outfileS);
            bw.write("##fileformat=VCFv4.1\n");
            bw.write("##fileDate="+S.split(" ")[0]+"\n");
            bw.write("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n");
            bw.write("##FORMAT=<ID=AD,Number=.,Type=Integer,Description=\"Allelic depths for the reference and alternate alleles in the order listed\">\n");
            bw.write("##FORMAT=<ID=GL,Number=G,Type=Integer,Description=\"Genotype likelihoods for 0/0, 0/1, 1/1, or  0/0, 0/1, 0/2, 1/1, 1/2, 2/2 if 2 alt alleles\">\n");
            bw.write("##INFO=<ID=DP,Number=1,Type=Integer,Description=\"Total Depth\">\n");
            bw.write("##INFO=<ID=NZ,Number=1,Type=Integer,Description=\"Number of taxa with called genotypes\">\n");
            bw.write("##INFO=<ID=AD,Number=.,Type=Integer,Description=\"Total allelelic depths in order listed starting with REF\">\n");
            bw.write("##INFO=<ID=AC,Number=.,Type=Integer,Description=\"Numbers of ALT alleles in order listed\">\n");
            bw.write("##INFO=<ID=IL,Number=.,Type=Integer,Description=\"Indel length of ALT alleles in order listed\">\n");
            bw.write("##INFO=<ID=GN,Number=.,Type=Integer,Description=\"Number of taxa with genotypes AA,AB,BB or AA,AB,AC,BB,BC,CC if 2 alt alleles\">\n");
            bw.write("##INFO=<ID=HT,Number=1,Type=Integer,Description=\"Number of heterozygotes\">\n");
            bw.write("##INFO=<ID=MAF,Number=1,Type=Float,Description=\"Minor allele frequency\">\n");
            bw.write("##ALT=<ID=DEL,Description=\"Deletion\">\n");
            bw.write("##ALT=<ID=INS,Description=\"Insertion\">\n");
            StringBuilder sb = new StringBuilder("#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT");
            for (int i = 0; i < taxaNames.length; i++) {
                sb.append("\t").append(taxaNames[i]);
            }
            bw.write(sb.toString());
            bw.newLine();
            BufferedReader[] brs = new BufferedReader[taxaNames.length];
            String indiVCFFolderS = new File(outputDirS, subDirS[1]).getAbsolutePath();
            for (int i = 0; i < brs.length; i++) {
                String indiVCFFileS = new File (indiVCFFolderS, taxaNames[i]+".chr"+PStringUtils.getNDigitNumber(3, chrom)+".indi.vcf").getAbsolutePath();
                brs[i] = new BufferedReader (new FileReader(indiVCFFileS), 4096);
            }
            byte[] codedAlts = null;
            String[] genoArray = new String[brs.length];
            int cnt = 0;
            for (int i = 0; i < positions.length; i++) {
                sb.setLength(0);
                sb.append(chrom).append("\t").append(positions[i]).append("\t").append(chrom).append("-").append(positions[i]).append("\t").append(posRefMap.get(positions[i])).append("\t");
                codedAlts = posCodedAlleleMap.get(positions[i]);
                for (int j = 0; j < codedAlts.length; j++) {
                    sb.append(FastCall2.getAlleleBaseFromCodedAllele(codedAlts[j])).append(",");
                }
                sb.deleteCharAt(sb.length()-1).append("\t.\t.\t.");
                for (int j = 0; j < brs.length; j++) {
                    genoArray[j]= brs[j].readLine();
                }
                sb.append(this.getInfo(genoArray, codedAlts)).append("\tGT:AD:GL");
                for (int j = 0; j < genoArray.length; j++) {
                    sb.append("\t").append(genoArray[j]);
                }
                bw.write(sb.toString());
                bw.newLine();
                cnt++;
                if (cnt%1000000 == 0) System.out.println(String.valueOf(cnt)+" SNPs output to " + outfileS);
            }
            bw.flush();
            bw.close();
            for (int i = 0; i < brs.length; i++) {
                brs[i].close();
            }
            for (int i = 0; i < taxaNames.length; i++) {
                String indiVCFFileS = new File (indiVCFFolderS, taxaNames[i]+".chr"+PStringUtils.getNDigitNumber(3, chrom)+".indi.vcf").getAbsolutePath();
                new File(indiVCFFileS).delete();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        new File(outputDirS, subDirS[0]).delete();
        new File(outputDirS, subDirS[1]).delete();
        System.out.println("Final VCF is completed at " + outfileS);
    }

    private String getInfo (String[] genoArray, byte[] codedAlts) {
        int dp = 0;
        int nz = 0;
        int nAlt = codedAlts.length;
        int[] adCnt = new int[1+nAlt];
        int[] acCnt = new int[1+nAlt];
        int[][] gnCnt = new int[1+nAlt][1+nAlt];
        int ht = 0;
        List<String> tempList = null;
        List<String> temList = null;
        for (int i = 0; i < genoArray.length; i++) {
            if (genoArray[i].startsWith(".")) {
                nz++;
                continue;
            }
            tempList = PStringUtils.fastSplit(genoArray[i], ":");
            temList = PStringUtils.fastSplit(tempList.get(1), ",");
            for (int j = 0; j < temList.size(); j++) {
                int c = Integer.parseInt(temList.get(j));
                dp+=c;
                adCnt[j] += c;
            }
            temList = PStringUtils.fastSplit(tempList.get(0), "/");
            for (int j = 0; j < temList.size(); j++) {
                int c = Integer.parseInt(temList.get(j));
                acCnt[c]++;
            }
            int index1 = Integer.parseInt(temList.get(0));
            int index2 = Integer.parseInt(temList.get(1));
            gnCnt[index1][index2]++;
            if (index1 != index2) ht++;
        }
        nz = genoArray.length - nz;
        int sum = 0;
        for (int i = 0; i < acCnt.length; i++) {
            sum+=acCnt[i];
        }
        float maf = (float)((double)acCnt[0]/sum);
        if (maf>0.5) maf = (float)(1-maf);
        StringBuilder sb = new StringBuilder();
        sb.append("DP=").append(dp).append(";NZ=").append(nz).append(";AD=");
        for (int i = 0; i < adCnt.length; i++) {
            sb.append(adCnt[i]).append(",");
        }
        sb.deleteCharAt(sb.length()-1);
        sb.append(";AC=");
        for (int i = 0; i < acCnt.length; i++) {
            sb.append(acCnt[i]).append(",");
        }
        sb.deleteCharAt(sb.length()-1);
        sb.append(";IL=");
        for (int i = 0; i < codedAlts.length; i++) {
            sb.append(FastCall2.getIndelLengthFromCodedAllele(codedAlts[i])).append(",");
        }
        sb.deleteCharAt(sb.length()-1);
//        sb.append(";GN=");
//        for (int i = 0; i < gnCnt.length; i++) {
//            for (int j = i + 1; j < gnCnt.length; j++) {
//                sb.append(gnCnt[i][j]).append(",");
//            }
//        }
//        sb.deleteCharAt(sb.length()-1);
        sb.append(";HT=").append(ht).append(";MAF=").append(maf);
        return sb.toString();
    }

    public void scanIndiVCFByThreadPool () {
        FastaRecordBit frb = genomeFa.getFastaRecordBit(chromIndex);
        posRefMap = new HashMap<>();
        posCodedAlleleMap = new HashMap<>();
        List<String> altList = new ArrayList<>();
        positions = new int[vlEndIndex-vlStartIndex];
        for (int i = vlStartIndex; i < vlEndIndex; i++) {
            posRefMap.put(vl.positions[i], String.valueOf(frb.getBase(vl.positions[i]-1)));
            posCodedAlleleMap.put(vl.positions[i], vl.codedAlleles[i]);
            positions[i-vlStartIndex] = vl.positions[i];
        }
        Set<String> taxaSet = taxaBamsMap.keySet();
        ArrayList<String> taxaList = new ArrayList(taxaSet);
        Collections.sort(taxaList);

//        int[][] indices = PArrayUtils.getSubsetsIndicesBySubsetSize(taxaList.size(), this.nThreads);
        LongAdder counter = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(this.threadsNum);
        List<Future<IndiVCF>> resultList = new ArrayList<>();
        for (int i = 0; i < taxaList.size(); i++) {
            String indiVCFFolderS = new File(outputDirS, subDirS[1]).getAbsolutePath();
            String indiVCFFileS = new File(indiVCFFolderS, taxaList.get(i) + ".chr" + PStringUtils.getNDigitNumber(3, chrom) + ".indi.vcf").getAbsolutePath();
            List<String> bamPaths = taxaBamsMap.get(taxaList.get(i));
            StringBuilder sb = new StringBuilder(samtoolsPath);
            sb.append(" mpileup -A -B -q 20 -Q 20 -f ").append(this.referenceFileS);
            for (int j = 0; j < bamPaths.size(); j++) {
                sb.append(" ").append(bamPaths.get(j));
            }
            sb.append(" -l ").append(vLibPosFileS).append(" -r ");
            sb.append(chrom);
            String command = sb.toString();
            IndiVCF idv = new IndiVCF(command, indiVCFFileS, posRefMap, posCodedAlleleMap, positions, bamPaths, counter);
            Future<IndiVCF> result = pool.submit(idv);
            resultList.add(result);
        }
        try {
            pool.shutdown();
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MICROSECONDS);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }

    class IndiVCF implements Callable<IndiVCF> {
        String command = null;
        String indiVCFFileS = null;
        HashMap<Integer, String> posRefMap = null;
        HashMap<Integer, byte[]> posCodedAlleleMap = null;
        int[] positions = null;
        List<String> bamPaths = null;
        LongAdder counter = null;
        public IndiVCF (String command, String indiVCFFileS, HashMap<Integer, String> posRefMap, HashMap<Integer, byte[]> posCodedAlleleMap, int[] positions, List<String> bamPaths, LongAdder counter) {
            this.command = command;
            this.indiVCFFileS = indiVCFFileS;
            this.posRefMap = posRefMap;
            this.posCodedAlleleMap = posCodedAlleleMap;
            this.positions = positions;
            this.bamPaths = bamPaths;
            this.counter = counter;
        }

        @Override
        public IndiVCF call() throws Exception {
            try {
                Runtime rt = Runtime.getRuntime();
                Process p = rt.exec(command);
                String temp = null;
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

                BufferedWriter bw = IOUtils.getTextWriter(indiVCFFileS);
                String current = br.readLine();
                List<String> currentList = null;
                int currentPosition = -1;
                if (current != null) {
                    currentList = PStringUtils.fastSplit(current);
                    currentPosition = Integer.parseInt(currentList.get(1));
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < positions.length; i++) {
                    if (current == null) {
                        bw.write("./.");
                        bw.newLine();
                    }
                    else {
                        if (positions[i] == currentPosition) {
                            String ref = posRefMap.get(currentPosition);
                            byte[] codedAlleles = this.posCodedAlleleMap.get(currentPosition);
                            String[] alts = new String[codedAlleles.length];
                            for (int j = 0; j < alts.length; j++) {
                                alts[j] = String.valueOf(FastCall2.getAlleleBaseFromCodedAllele(codedAlleles[j]));
                            }
                            char[] alleleC = new char[alts.length+1];
                            alleleC[0] = ref.charAt(0);
                            for (int j = 0; j < alts.length; j++) {
                                if (alts[j].startsWith("I") || alts[j].startsWith("<I")) {
                                    alleleC[j+1] = '+';
                                }
                                else if (alts[j].startsWith("D") || alts[j].startsWith("<D")) {
                                    alleleC[j+1] = '-';
                                }
                                else {
                                    alleleC[j+1] = alts[j].charAt(0);
                                }
                            }
                            int[] cnts = new int[alts.length+1];
                            sb.setLength(0);
                            for (int j = 0; j < bamPaths.size(); j++) {
                                sb.append(currentList.get(4+j*3));
                            }
                            StringBuilder ssb = new StringBuilder();
                            int curIndex = 0;
                            for (int j = 0; j < sb.length(); j++) {
                                char cChar = sb.charAt(j);
                                if (cChar == '+') {
                                    ssb.append(sb.subSequence(curIndex, j+1));
                                    curIndex = j+2+Character.getNumericValue(sb.charAt(j+1));
                                }
                                else if (cChar == '-') {
                                    ssb.append(sb.subSequence(curIndex, j+1));
                                    curIndex = j+2+Character.getNumericValue(sb.charAt(j+1));
                                }
                            }
                            ssb.append(sb.subSequence(curIndex, sb.length()));
                            sb = ssb;
                            String s = sb.toString().toUpperCase();
                            for (int j = 0; j < s.length(); j++) {
                                char cChar = s.charAt(j);
                                if (cChar == '.' || cChar == ',') {
                                    cnts[0]++;
                                    continue;
                                }
                                for (int k = 1; k < alleleC.length; k++) {
                                    if (cChar == alleleC[k]) cnts[k]++;
                                }
                            }
                            for (int j = 1; j < alleleC.length; j++) {
                                if (alleleC[j] == '+') cnts[0] = cnts[0]-cnts[j];
                                else if (alleleC[j] == '-') cnts[0] = cnts[0]-cnts[j];
                            }
                            String vcf = getGenotype(cnts);
                            bw.write(vcf);
                            bw.newLine();
                            current = br.readLine();
                            if (current != null) {
                                currentList = PStringUtils.fastSplit(current);
                                currentPosition = Integer.parseInt(currentList.get(1));
                            }
                        }
                        else if (positions[i] < currentPosition) {
                            bw.write("./.");
                            bw.newLine();
                        }
                        else {
                            System.out.println("Current position is greater than pileup position. It should not happen. Program quits");
                            System.exit(1);
                        }
                    }
                }

                BufferedReader bre = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((temp = bre.readLine()) != null) {
                    if (temp.startsWith("[m")) continue;
                    System.out.println(command);
                    System.out.println(temp);
                }
                bre.close();

                p.waitFor();
                bw.flush();
                bw.close();
                br.close();
            }
            catch (Exception ee) {
                ee.printStackTrace();
            }
            counter.increment();
            int cnt = counter.intValue();
            if (cnt%10 == 0) System.out.println("Finished individual genotyping in " + String.valueOf(cnt) + " taxa. Total: " + String.valueOf(taxaBamsMap.size()));
            return this;
        }
    }
    private String getGenotype (int[] cnt) {
        int n = cnt.length*(cnt.length+1)/2;
        int[] likelihood = new int[n];
        int sum = 0;
        for (int i = 0; i < cnt.length; i++) sum+=cnt[i];
        if (sum == 0) return "./.";
        else if (sum > this.maxFactorial) {
            double portion = (double)this.maxFactorial/sum;
            for (int i = 0; i < cnt.length; i++) {
                cnt[i] = (int)(cnt[i]*portion);
            }
            sum = this.maxFactorial;
        }
        double coe = this.factorialMap.get(sum);
        for (int i = 0; i < cnt.length; i++) coe = coe/this.factorialMap.get(cnt[i]);
        double max = Double.MAX_VALUE;
        int a1 = 0;
        int a2 = 0;
        for (int i = 0; i < cnt.length; i++) {
            for (int j = i; j < cnt.length; j++) {
                int index = (j*(j+1)/2)+i;
                double value = Double.MAX_VALUE;
                if (i == j) {
                    value = -Math.log10(coe*Math.pow((1-0.75*this.combinedErrorRate), cnt[i])*Math.pow(this.combinedErrorRate /4, (sum-cnt[i])));
                }
                else {
                    value = -Math.log10(coe*Math.pow((0.5-this.combinedErrorRate /4), cnt[i]+cnt[j])*Math.pow(this.combinedErrorRate /4, (sum-cnt[i]-cnt[j])));
                }
                if (value < max) {
                    max = value;
                    a1 = i;
                    a2 = j;
                }
                likelihood[index] = (int)Math.round(value);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(a1).append("/").append(a2).append(":");
        for (int i = 0; i < cnt.length; i++) sb.append(cnt[i]).append(",");
        sb.deleteCharAt(sb.length()-1); sb.append(":");
        for (int i = 0; i < likelihood.length; i++) sb.append(likelihood[i]).append(",");
        sb.deleteCharAt(sb.length()-1);
        return sb.toString();
    }

    private void processVariationLibrary () {
        StringBuilder sb = new StringBuilder();
        sb.append(this.chrom).append("_").append(this.regionStart).append("_").append(regionEnd).append(".pos.txt");
        this.vLibPosFileS = new File (this.outputDirS, sb.toString()).getAbsolutePath();
        this.vl = new VariationLibrary(this.libFileS);
        if (this.chrom != vl.getChrom()) {
            System.out.println("The chromosome number of library and the specified one do not match. Program quits.");
            System.exit(0);
        }
        try {
            vlStartIndex = vl.getStartIndex(this.regionStart);
            vlEndIndex = vl.getEndIndex(this.regionEnd);
            if (vlStartIndex == Integer.MIN_VALUE || vlEndIndex == Integer.MIN_VALUE) {
                System.out.println("The chromosome region was incorrectly set. Program quits.");
                System.exit(0);
            }
            BufferedWriter bw = IOUtils.getTextWriter(this.vLibPosFileS);
            for (int i = vlStartIndex; i < vlEndIndex; i++) {
                sb.setLength(0);
                sb.append(this.chrom).append("\t").append(vl.getPosition(i));
                bw.write(sb.toString());
                bw.newLine();
            }
            bw.flush();
            bw.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void creatFactorialMap () {
        this.factorialMap = HashIntDoubleMaps.getDefaultFactory().newMutableMap();
        for (int i = 0; i < this.maxFactorial+1; i++) {
            this.factorialMap.put(i, factorial(i));
        }
    }

    public void mkDir () {
        File f = new File (this.outputDirS);
        f.mkdir();
        for (int i = 0; i < subDirS.length; i++) {
            f = new File(outputDirS, subDirS[i]);
            f.mkdir();
        }
    }

    private void parseParameters (List<String> pLineList) {
        this.referenceFileS = pLineList.get(0);
        taxaRefBamFileS = pLineList.get(1);
        this.libFileS = pLineList.get(2);
        String[] tem = pLineList.get(3).split(":");
        this.chrom = Integer.parseInt(tem[0]);
        long start = System.nanoTime();
        System.out.println("Reading reference genome from "+ referenceFileS);
        genomeFa = new FastaBit(referenceFileS);
        System.out.println("Reading reference genome took " + String.format("%.2f", Benchmark.getTimeSpanSeconds(start)) + "s");
        chromIndex = genomeFa.getIndexByName(String.valueOf(this.chrom));
        if (tem.length == 1) {
            this.regionStart = 1;
            this.regionEnd = genomeFa.getSeqLength(chromIndex)+1;
        }
        else if (tem.length == 2) {
            tem = tem[1].split(",");
            this.regionStart = Integer.parseInt(tem[0]);
            this.regionEnd = Integer.parseInt(tem[1])+1;
        }
        this.combinedErrorRate = Double.parseDouble(pLineList.get(4));
        this.samtoolsPath = pLineList.get(5);
        this.threadsNum = Integer.parseInt(pLineList.get(6));
        this.outputDirS = pLineList.get(7);
        this.parseTaxaBamMap(this.taxaRefBamFileS);
    }

    private void parseTaxaBamMap(String taxaBamMapFileS) {
        this.taxaBamsMap = new HashMap<>();
        this.taxaCoverageMap = new HashMap<>();
        try {
            BufferedReader br = IOUtils.getTextReader(taxaBamMapFileS);
            String temp = br.readLine();
            ArrayList<String> taxaList = new ArrayList();
            ArrayList<String> pathList = new ArrayList();
            int nBam = 0;
            while ((temp = br.readLine()) != null) {
                String[] tem = temp.split("\t");
                taxaList.add(tem[0]);
                String[] bams = new String[tem.length-2] ;
                for (int i = 0; i < bams.length; i++) {
                    bams[i] = tem[i+2];
                    pathList.add(bams[i]);
                }
                Arrays.sort(bams);
                List<String> bamList = Arrays.asList(bams);
                taxaBamsMap.put(tem[0], bamList);
                taxaCoverageMap.put(tem[0], Double.valueOf(tem[1]));
                nBam+=bams.length;
            }
            taxaNames = taxaList.toArray(new String[taxaList.size()]);
            Arrays.sort(taxaNames);
            HashSet<String> taxaSet = new HashSet<>(taxaList);
            if (taxaSet.size() != taxaNames.length) {
                System.out.println("Taxa names are not unique. Programs quits");
                System.exit(0);
            }
            System.out.println("Created TaxaBamMap from" + taxaBamMapFileS);
            System.out.println("Taxa number:\t"+String.valueOf(taxaNames.length));
            System.out.println("Bam file number in TaxaBamMap:\t"+String.valueOf(nBam));
            System.out.println();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}