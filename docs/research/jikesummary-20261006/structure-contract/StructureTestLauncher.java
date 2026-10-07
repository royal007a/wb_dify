import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs only the two lightweight structure test classes; no Spring, Maven or Docker. */
public final class StructureTestLauncher {
    public static void main(String[] args) {
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("com.hify.api.MavenStructureTest"),
            DiscoverySelectors.selectClass("com.hify.api.ModuleStructureContractTest")).build();
        var listener=new SummaryGeneratingListener();
        LauncherFactory.create().execute(request,listener);
        var summary=listener.getSummary();
        summary.printTo(new PrintWriter(System.out,true));
        summary.printFailuresTo(new PrintWriter(System.out,true));
        if(summary.getTestsFoundCount()!=22 || summary.getTestsSucceededCount()!=22
            || summary.getTestsFailedCount()!=0 || summary.getTestsSkippedCount()!=0
            || summary.getTestsAbortedCount()!=0 || summary.getContainersFailedCount()!=0)
            throw new AssertionError("Structure contract requires exactly 22 successful tests, no skips/abort/failure");
    }
}
