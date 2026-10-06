import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;

public class LightweightTest {
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(java.util.Arrays.stream(args[0].split(",")).map(DiscoverySelectors::selectClass).toList()).build();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        var out = new PrintWriter(System.out, true);
        summary.printTo(out);
        summary.printFailuresTo(out);
        if (summary.getTestsFoundCount() != Long.parseLong(args[1])
            || summary.getTestsSkippedCount() != 0 || summary.getTestsAbortedCount() != 0
            || summary.getTotalFailureCount() != 0) System.exit(1);
    }
}
