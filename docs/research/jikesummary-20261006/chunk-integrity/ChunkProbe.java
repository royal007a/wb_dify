import com.hify.knowledge.application.RecursiveTextChunker;
public class ChunkProbe {
    static boolean validUtf16(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(i+1>=text.length() || !Character.isLowSurrogate(text.charAt(++i)))return false;
            } else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    public static void main(String[] args) {
        var chunker=new RecursiveTextChunker();
        var overlap=chunker.split("a".repeat(512),64,16);
        System.out.println("ascii.tokenCounts="+overlap.stream().map(c->c.tokenCount()).toList());
        System.out.println("ascii.exceedsConfiguredEstimate="+overlap.stream().anyMatch(c->c.tokenCount()>64));
        String unicode="a".repeat(255)+"\uD83D\uDE00"+"b".repeat(255);
        var split=chunker.split(unicode,64,16);
        System.out.println("unicode.inputWellFormed="+validUtf16(unicode));
        System.out.println("unicode.outputWellFormed="+split.stream().map(c->validUtf16(c.content())).toList());
        if(overlap.size()!=2 || overlap.get(1).tokenCount()!=80 || !validUtf16(unicode) || validUtf16(split.get(0).content()))
            throw new AssertionError("Expected defect reproductions changed; inspect before reporting");
    }
}
