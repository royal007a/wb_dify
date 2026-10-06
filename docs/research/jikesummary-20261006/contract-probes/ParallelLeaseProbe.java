import com.hify.common.ExecutionControl;
import com.hify.runtime.StaleToolExecutionException;
import com.hify.runtime.ToolExecutionLease;
import java.util.concurrent.ConcurrentHashMap;

/** Offline design counterexample, not a production parallel-execution test. */
public class ParallelLeaseProbe {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static boolean usable(ToolExecutionLease lease) {
        try { lease.assertUsable(ExecutionControl.none()); return true; }
        catch (StaleToolExecutionException expected) { return false; }
    }
    public static void main(String[] args) {
        var active = new ConcurrentHashMap<String,String>();
        // Exact map operations from RunApplicationService's observer. Its DB
        // RUNNING/cancel/revision checks are deliberately NOT simulated here.
        var a = new ToolExecutionLease("probe-run", "a", "rev",
                () -> "a".equals(active.get("probe-run")));
        var b = new ToolExecutionLease("probe-run", "b", "rev",
                () -> "b".equals(active.get("probe-run")));
        active.put("probe-run", "a");
        require(usable(a), "serial first attempt must be usable");
        active.put("probe-run", "b");
        require(!usable(a) && usable(b), "overlapping start replaces first attempt");
        active.remove("probe-run", "a");
        require(usable(b), "old completion must not erase newer attempt");
        active.remove("probe-run", "b");
        require(!usable(b), "completed attempt must become stale");
        System.out.println("{\"observations\":4,\"allExpected\":true,"
                + "\"scope\":\"production lease plus copied single-active map semantics; not service E2E\"}");
    }
}
