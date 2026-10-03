import com.hify.common.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class AuditProbe {
    @RestController
    public static class InputController {
        @GetMapping("/probe")
        public Result<String> get(@RequestParam("page") int page, @RequestHeader("Idempotency-Key") String key) {
            return Result.ok("ok");
        }
        @PostMapping(value="/probe", consumes="application/json")
        public Result<String> post(@RequestBody Map<String, Object> body) { return Result.ok("ok"); }
    }
    public static void main(String[] args) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InputController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var missing = mvc.perform(MockMvcRequestBuilders.get("/probe").param("page", "1")).andReturn();
        var malformed = mvc.perform(MockMvcRequestBuilders.get("/probe").param("page", "not-a-number")
                .header("Idempotency-Key", "probe")).andReturn();
        var method = mvc.perform(MockMvcRequestBuilders.delete("/probe")).andReturn();
        var media = mvc.perform(MockMvcRequestBuilders.post("/probe").contentType("text/plain").content("x")).andReturn();
        System.out.printf("A05 missingHeader=%d invalidPage=%d unsupportedMethod=%d unsupportedMedia=%d%n",
                missing.getResponse().getStatus(), malformed.getResponse().getStatus(),
                method.getResponse().getStatus(), media.getResponse().getStatus());
        var submissions = new AtomicInteger();
        var control = ExecutionControl.withTimeout(Duration.ofSeconds(5), () -> true);
        var client = new LlmHttpClient(command -> submissions.incrementAndGet());
        try { client.post("http://127.0.0.1/never-request", Map.of(), "{}", control); }
        catch (ExecutionCancelledException expected) { }
        System.out.println("A06 preCancelledPostSubmissions=" + submissions.get());
        var pool = Executors.newSingleThreadExecutor();
        var caller = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        try {
            var breaker = new CircuitBreakerService(CircuitBreakerRegistry.ofDefaults(), pool,
                    Duration.ofSeconds(20), 3, Duration.ZERO, 3, Duration.ZERO);
            var job = caller.submit(() -> breaker.execute("audit", ExecutionControl.withTimeout(Duration.ofSeconds(10), cancelled::get), () -> {
                entered.countDown();
                try { released.await(); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "worker result";
            }));
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker not started");
            cancelled.set(true);
            try { job.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException e) {
                System.out.println("A06 callerFailure=" + e.getCause().getClass().getSimpleName());
            }
            System.out.println("A06 workerInterruptedBeforeCleanup=" + interrupted.await(500, TimeUnit.MILLISECONDS));
        } finally {
            released.countDown(); pool.shutdownNow(); caller.shutdownNow();
        }
    }
}
