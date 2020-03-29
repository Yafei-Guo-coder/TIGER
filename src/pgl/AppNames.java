/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package pgl;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Available apps in TIGER
 * @author feilu
 */
public enum AppNames implements Comparable <AppNames> {
    /**
     * SNP calling and genotyping from whole-genome sequence data
     */
    FastCall ("FastCall"),
    
    /**
     * Genotyping from bam files based on a genetic variation library
     */
    HapScanner ("HapScanner");
    
    public final String name;
    
    AppNames (String name) {
        this.name = name;
    }
    
    public String getName () {
        return name;
    }
}
