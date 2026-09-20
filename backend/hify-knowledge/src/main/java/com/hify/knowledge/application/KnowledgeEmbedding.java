package com.hify.knowledge.application;

import java.util.Locale;

/** Replaceable deterministic bootstrap embedding; production providers can implement the same boundary. */
public final class KnowledgeEmbedding {
    public static final int DIMENSIONS = 64;
    private KnowledgeEmbedding() {}

    public static float[] embed(String text) {
        String value = normalize(text);
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < value.length(); i++) {
            add(vector, value.substring(i, i + 1), .35f);
            if (i + 1 < value.length()) add(vector, value.substring(i, i + 2), 1f);
            if (i + 2 < value.length()) add(vector, value.substring(i, i + 3), .55f);
        }
        double norm = 0;
        for (float item : vector) norm += item * item;
        if (norm > 0) {
            float divisor = (float)Math.sqrt(norm);
            for (int i=0;i<vector.length;i++) vector[i] /= divisor;
        }
        return vector;
    }
    public static double cosine(float[] left, float[] right) {
        double value=0; for(int i=0;i<Math.min(left.length,right.length);i++) value += left[i]*right[i]; return value;
    }
    public static String literal(float[] vector) {
        StringBuilder out=new StringBuilder("[");
        for(int i=0;i<vector.length;i++){ if(i>0)out.append(','); out.append(vector[i]); }
        return out.append(']').toString();
    }
    public static float[] parse(String literal) {
        String value=literal.substring(1,literal.length()-1); String[] parts=value.split(","); float[] out=new float[parts.length];
        for(int i=0;i<parts.length;i++) out[i]=Float.parseFloat(parts[i]); return out;
    }
    private static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("\\s+","").replaceAll("[^\\p{L}\\p{N}_-]","");}
    private static void add(float[] vector,String token,float weight){int hash=token.hashCode();vector[Math.floorMod(hash,vector.length)]+=((hash&1)==0?weight:-weight);}
}
