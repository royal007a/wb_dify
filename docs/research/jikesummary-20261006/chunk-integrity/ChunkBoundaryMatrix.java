import com.hify.knowledge.application.RecursiveTextChunker;
import java.util.List;

/** Characterizes the unchanged production class; PASS means the observation matched,
 * not that the product satisfies the proposed invariant. No DB/model/HTTP calls. */
public class ChunkBoundaryMatrix {
    record Case(String name, String input, int overlap,
                boolean exceedsEstimate, boolean malformedOutput) {}
    static boolean valid(String s) {
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    public static void main(String[] args) {
        var cases=List.of(
            new Case("empty", " \n\t",16,false,false),
            new Case("ascii-exact", "a".repeat(256),16,false,false),
            new Case("ascii-plus-one", "a".repeat(257),16,false,false),
            new Case("ascii-two-chunks-zero-overlap", "a".repeat(512),0,false,false),
            new Case("ascii-two-chunks-overlap", "a".repeat(512),16,true,false),
            new Case("ascii-near-max-overlap", "a".repeat(512),63,true,false),
            new Case("chinese-overlap", "中".repeat(512),16,true,false),
            new Case("emoji-split-zero-overlap", "a".repeat(255)+"😀"+"b".repeat(255),0,false,true),
            new Case("emoji-split-overlap", "a".repeat(255)+"😀"+"b".repeat(255),16,true,true),
            new Case("emoji-carry-only", "a".repeat(191)+"😀"+"b".repeat(63)+"c".repeat(256),16,true,true));
        var chunker=new RecursiveTextChunker();
        System.out.println("case\tcounts\texceeds-estimate\tmalformed-utf16\tdeterministic");
        for (Case c:cases) {
            if (!valid(c.input())) throw new AssertionError("Invalid fixture: "+c.name());
            var result=chunker.split(c.input(),64,c.overlap());
            boolean exceeds=result.stream().anyMatch(x->x.tokenCount()>64);
            boolean malformed=result.stream().anyMatch(x->!valid(x.content()));
            boolean deterministic=result.equals(chunker.split(c.input(),64,c.overlap()));
            System.out.println(c.name()+"\t"+result.stream().map(x->x.tokenCount()).toList()
                +"\t"+exceeds+"\t"+malformed+"\t"+deterministic);
            if(exceeds!=c.exceedsEstimate() || malformed!=c.malformedOutput() || !deterministic)
                throw new AssertionError("Characterization changed: "+c.name());
        }
        System.out.println("observations-matched="+cases.size()+"; this is NOT a product acceptance pass");
    }
}
