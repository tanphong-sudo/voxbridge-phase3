package com.voxbridge.runtime;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.BreakIterator;
import java.util.*;

/** Exact tokenizer.json BPE/Unigram adapter for the two static MT candidates. No native JNI. */
public final class StaticMtTokenizer {
    private final JSONObject spec;
    private final Map<String,Integer> vocab = new HashMap<>();
    private final Map<Integer,String> pieces = new HashMap<>();
    private final Set<Integer> special = new HashSet<>();
    private final Map<String,Integer> merges = new HashMap<>();
    private final Trie trie = new Trie();
    private final boolean bpe;
    private final int unk, pad, eos;
    private double unknownScore = -100;
    private static final String SEP="\u0000";
    private static final class Trie {
        final Map<Character,Trie> next = new HashMap<>(); int id=-1; double score;
    }
    public static final class Encoded {
        public final int[] ids, mask;
        Encoded(int[] ids,int[] mask){this.ids=ids;this.mask=mask;}
    }
    public StaticMtTokenizer(File file) throws IOException, JSONException {
        spec=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        JSONObject model=spec.getJSONObject("model"); bpe=model.getString("type").equals("BPE");
        if(!bpe && !model.getString("type").equals("Unigram"))throw new IllegalArgumentException("Unsupported model tokenizer");
        double minScore=0;
        if(bpe){
            JSONObject v=model.getJSONObject("vocab"); Iterator<String> keys=v.keys(); while(keys.hasNext()){String s=keys.next();int id=v.getInt(s);vocab.put(s,id);pieces.put(id,s);}
            JSONArray m=model.getJSONArray("merges");for(int i=0;i<m.length();i++){
                Object item=m.get(i);String left,right;
                if(item instanceof JSONArray){left=((JSONArray)item).getString(0);right=((JSONArray)item).getString(1);}
                else {String s=item.toString();int split=s.indexOf(' ');left=s.substring(0,split);right=s.substring(split+1);}
                merges.put(left+SEP+right,i);
            }
        }else{
            JSONArray v=model.getJSONArray("vocab");for(int i=0;i<v.length();i++){
                JSONArray pair=v.getJSONArray(i);String s=pair.getString(0);double score=pair.getDouble(1);
                vocab.put(s,i);pieces.put(i,s);minScore=Math.min(minScore,score);
                Trie n=trie;for(int j=0;j<s.length();j++)n=n.next.computeIfAbsent(s.charAt(j),k->new Trie());n.id=i;n.score=score;
            }
            unknownScore=minScore-10;
        }
        JSONArray added=spec.getJSONArray("added_tokens");for(int i=0;i<added.length();i++){
            JSONObject t=added.getJSONObject(i);int id=t.getInt("id");String s=t.getString("content");vocab.put(s,id);pieces.put(id,s);if(t.getBoolean("special"))special.add(id);
        }
        unk=bpe?vocab.get(model.getString("unk_token")):model.getInt("unk_id");
        pad=vocab.get("<pad>");eos=vocab.get("</s>");
    }
    public boolean isEnvit5(){return bpe;}
    public int padId(){return pad;}
    public int eosId(){return eos;}
    public int startId(String target){return bpe?pad:languageId(target);}
    private int languageId(String lang){if(!lang.equals("vi")&&!lang.equals("en"))throw new IllegalArgumentException("Unsupported language");return vocab.get(lang.equals("vi")?"vi_VN":"en_XX");}
    public Encoded encode(String text,String source,int length) throws JSONException {
        if(length<1)throw new IllegalArgumentException("Invalid source length");
        String input=bpe?source+": "+text:text;
        // Avoid treating literal reserved control tokens as an instruction to the tokenizer.
        // Inputs containing these require a separate UI repeat instead of silently injecting BOS/EOS.
        for(int id:special){String token=pieces.get(id);if(input.contains(token))throw new IllegalArgumentException("Reserved token in source text");}
        String normalized=normalize(spec.getJSONObject("normalizer"),input);
        normalized=normalized.replace(" ","▁");if(!normalized.isEmpty()&&!normalized.startsWith("▁"))normalized="▁"+normalized;
        List<Integer> ids=new ArrayList<>();
        int start=0;for(int i=1;i<=normalized.length();i++){
            if(i==normalized.length() || normalized.charAt(i)=='▁'){
                String word=normalized.substring(start,i);ids.addAll(bpe?encodeBpe(word):encodeUnigram(word));start=i;
            }
        }
        ids.add(eos);if(!bpe)ids.add(languageId(source));
        if(ids.size()>length)throw new IllegalArgumentException("Source exceeds static token budget; no truncation");
        int[] output=new int[length],mask=new int[length];Arrays.fill(output,pad);for(int i=0;i<ids.size();i++){output[i]=ids.get(i);mask[i]=1;}
        return new Encoded(output,mask);
    }
    public String decode(int[] ids){
        StringBuilder out=new StringBuilder();for(int id:ids)if(!special.contains(id))out.append(pieces.getOrDefault(id,""));
        String text=out.toString().replace('▁',' ');if(text.startsWith(" "))text=text.substring(1);
        text=text.trim();if(bpe)text=text.replaceFirst("(?i)^\\s*(en|vi)\\s*:\\s*","").trim();return text;
    }
    private List<Integer> encodeBpe(String word){
        List<String> symbols=new ArrayList<>();word.codePoints().forEach(cp->symbols.add(new String(Character.toChars(cp))));
        while(symbols.size()>1){
            int best=Integer.MAX_VALUE,at=-1;for(int i=0;i<symbols.size()-1;i++){
                Integer rank=merges.get(symbols.get(i)+SEP+symbols.get(i+1));if(rank!=null && rank<best){best=rank;at=i;}
            }
            if(at<0)break;symbols.set(at,symbols.get(at)+symbols.remove(at+1));
        }
        List<Integer> ids=new ArrayList<>();for(String s:symbols){int id=vocab.getOrDefault(s,unk);if(id!=unk||ids.isEmpty()||ids.get(ids.size()-1)!=unk)ids.add(id);}return ids;
    }
    private List<Integer> encodeUnigram(String word){
        int n=word.length();double[] best=new double[n+1];Arrays.fill(best,Double.NEGATIVE_INFINITY);best[0]=0;
        int[] previous=new int[n+1],tokens=new int[n+1];
        for(int i=0;i<n;i++){
            if(!Double.isFinite(best[i]))continue;Trie node=trie;boolean single=false;int cpLength=Character.charCount(word.codePointAt(i));
            for(int j=i;j<n;j++){
                node=node.next.get(word.charAt(j));if(node==null)break;if(node.id<0)continue;
                if(j+1==i+cpLength)single=true;double score=best[i]+node.score;
                if(score>best[j+1]){best[j+1]=score;previous[j+1]=i;tokens[j+1]=node.id;}
            }
            if(!single){int end=i+cpLength;double score=best[i]+unknownScore;if(score>best[end]){best[end]=score;previous[end]=i;tokens[end]=unk;}}
        }
        LinkedList<Integer> output=new LinkedList<>();for(int at=n;at>0;at=previous[at])output.addFirst(tokens[at]);
        List<Integer> fused=new ArrayList<>();for(int id:output)if(id!=unk||fused.isEmpty()||fused.get(fused.size()-1)!=unk)fused.add(id);return fused;
    }
    private static String normalize(JSONObject rule,String value) throws JSONException {
        switch(rule.getString("type")){
            case "Sequence":JSONArray rules=rule.getJSONArray("normalizers");for(int i=0;i<rules.length();i++)value=normalize(rules.getJSONObject(i),value);return value;
            case "Precompiled":
                // Rust tokenizers applies the precompiled map exactly; do not add Java NFC/NFKC
                // here because decomposed sequences are intentionally observable in tokenizer
                // parity tests.
                return new CharsMap(Base64.getDecoder().decode(rule.getString("precompiled_charsmap"))).apply(value);
            case "Strip":if(rule.getBoolean("strip_left"))value=value.replaceFirst("^\\s+","");if(rule.getBoolean("strip_right"))value=value.replaceFirst("\\s+$","");return value;
            case "Replace":JSONObject pat=rule.getJSONObject("pattern");return pat.has("Regex")?value.replaceAll(pat.getString("Regex"),rule.getString("content")):value.replace(pat.getString("String"),rule.getString("content"));
            default:throw new IllegalArgumentException("Unsupported tokenizer normalization "+rule.getString("type"));
        }
    }
    private static final class CharsMap {
        final int[] units; final byte[] table;
        CharsMap(byte[] data){ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);int bytes=b.getInt();if(bytes<0||bytes%4!=0||bytes>data.length-4)throw new IllegalArgumentException("Invalid charsmap");units=new int[bytes/4];for(int i=0;i<units.length;i++)units[i]=b.getInt();table=Arrays.copyOfRange(data,bytes+4,data.length);}
        static int offset(int unit){return (unit>>>10)<<((unit&512)>>>6);}
        String apply(String text){
            // tokenizers' Precompiled normalizer consumes a whole grapheme for a matching
            // prefix. In particular, two decomposed Vietnamese marks must not be composed by
            // Java NFKC or by a longest-match traversal across both marks.
            BreakIterator graphemes=BreakIterator.getCharacterInstance(Locale.ROOT);graphemes.setText(text);
            ByteArrayOutputStream normalized=new ByteArrayOutputStream();
            int begin=graphemes.first();for(int end=graphemes.next();end!=BreakIterator.DONE;begin=end,end=graphemes.next()){
                byte[] cluster=text.substring(begin,end).getBytes(StandardCharsets.UTF_8);
                byte[] mapped=applyCluster(cluster);normalized.write(mapped,0,mapped.length);
            }
            return new String(normalized.toByteArray(),StandardCharsets.UTF_8);
        }
        byte[] applyCluster(byte[] input){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int pos=0;pos<input.length;){
                int node=offset(units[0]),length=0,value=-1;
                for(int i=pos;i<input.length;i++){
                    int label=input[i]&255;node^=label;if(node<0||node>=units.length)break;
                    int unit=units[node];if((unit&0x800000ff)!=label)break;node^=offset(unit);
                    if((unit&256)!=0 && node>=0 && node<units.length){length=input.length-pos;value=units[node]&0x7fffffff;break;}
                }
                if(value>=0){int end=value;while(end<table.length&&table[end]!=0)end++;if(value>=table.length||end==table.length)throw new IllegalArgumentException("Invalid charsmap leaf");out.write(table,value,end-value);pos+=length;}
                else {int count=(input[pos]&0x80)==0?1:(input[pos]&0xe0)==0xc0?2:(input[pos]&0xf0)==0xe0?3:4;out.write(input,pos,Math.min(count,input.length-pos));pos+=count;}
            }
            return out.toByteArray();
        }
    }
}
