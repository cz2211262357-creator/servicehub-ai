package cn.servicehub.knowledge;
import java.util.*;
import java.util.regex.Pattern;
final class KeywordTerms {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}_]+");
    private KeywordTerms(){}
    static List<String> patterns(String query){
        int count=Math.min(256,query.codePointCount(0,query.length()));String bounded=query.substring(0,query.offsetByCodePoints(0,count));
        var terms=new LinkedHashSet<String>();var matcher=WORD.matcher(bounded);
        while(matcher.find()&&terms.size()<16){
            String word=matcher.group();int[] points=word.codePoints().toArray();boolean chinese=word.codePoints().anyMatch(c->c>=0x4e00&&c<=0x9fff);
            if(chinese){for(int i=0;i+1<points.length&&terms.size()<16;i++)terms.add(new String(points,i,2));}
            else if(points.length>=2)terms.add(word);
        }
        return terms.stream().map(s->"%"+s.replace("/","//").replace("%","/%").replace("_","/_")+"%").toList();
    }
}
