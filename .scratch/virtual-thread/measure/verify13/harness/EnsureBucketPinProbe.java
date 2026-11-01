import com.betterschool.infrastructure.adapter.port.file.FileStoragePort;
import com.betterschool.infrastructure.adapter.port.file.MinioProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 票 13 一次性验证：并发虚拟线程同时首建桶，压 ensureBucket 的冷路径。
 * 同一份 harness 换 classpath 跑两臂——旧臂解析到 git HEAD 的 synchronized 版本，新臂解析到工作区版本。
 */
public class EnsureBucketPinProbe {

    public static void main(String[] args) throws Exception {
        String arm = args[0];
        String endpoint = args[1];
        String bucket = args[2];
        int n = Integer.parseInt(args[3]);

        MinioProperties props = new MinioProperties();
        props.setEndpoint(endpoint);
        props.setPublicUrl(endpoint);
        props.setAccessKey("bsadmin");
        props.setSecretKey("bs123456");
        props.setBucket(bucket);

        FileStoragePort port = new FileStoragePort(props);

        // 闸门：所有 VT 就位后再一起冲，让「首建桶」的争用最大化
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        gate.await();
                        String url = port.store(("probe-" + idx).getBytes(), "txt", "text/plain");
                        if (url != null && !url.isBlank()) {
                            ok.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        System.out.println("[probe] arm=" + arm + " n=" + n + " ok=" + ok.get() + " fail=" + errors.size());
        errors.forEach(e -> System.out.println("[probe] ERR " + e));
        System.exit(errors.isEmpty() ? 0 : 1);
    }
}
