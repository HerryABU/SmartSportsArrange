import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;

import java.io.PrintWriter;

public class RunTests {
    public static void main(String[] args) {
        String[] classes = {
            "com.sports.security.JwtUtilTest",
            "com.sports.service.NumberRuleServiceTest",
            "com.sports.service.RegistrationServiceTest",
            "com.sports.service.ArrangementServiceTest",
            "com.sports.service.ResultServiceTest",
            "com.sports.service.RankingServiceTest",
            "com.sports.service.SystemServiceTest"
        };
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request();
        for (String c : classes) {
            b.selectors(selectClass(c));
        }
        LauncherDiscoveryRequest req = b.build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(req);

        PrintWriter pw = new PrintWriter(System.out);
        listener.getSummary().printTo(pw);
        listener.getSummary().printFailuresTo(pw, 100);
        pw.flush();

        long failed = listener.getSummary().getTotalFailureCount();
        System.out.println("\n==== RESULT: " + (failed == 0 ? "ALL PASSED" : (failed + " FAILED")) + " ====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
