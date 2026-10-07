import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public final class DiagnosticTestLauncher {
    public static void main(String[] args) {
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("com.hify.api.ShutdownStartupDiagnosticsTest")).build();
        var listener=new SummaryGeneratingListener();
        LauncherFactory.create().execute(request,listener);
        var summary=listener.getSummary();
        summary.printTo(new PrintWriter(System.out,true));
        summary.printFailuresTo(new PrintWriter(System.out,true));
        if(summary.getTestsFoundCount()!=6 || summary.getTestsSucceededCount()!=6
            || summary.getTestsFailedCount()!=0 || summary.getTestsSkippedCount()!=0
            || summary.getTestsAbortedCount()!=0 || summary.getContainersFailedCount()!=0)
            throw new AssertionError("Require all six diagnostic helper tests; no context startup or recovery claims");
    }
}
