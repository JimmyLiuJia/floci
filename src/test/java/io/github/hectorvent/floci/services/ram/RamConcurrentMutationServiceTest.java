package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RamConcurrentMutationServiceTest {

    private static final String OWNER = "111111111111";
    private static final String RESOURCE_ARN =
            "arn:aws:ec2:us-east-1:111111111111:transit-gateway/tgw-0abc";

    @Test
    void concurrentTagAndDisassociateDoNotLoseEitherMutation() throws Exception {
        CoordinatedStorageFactory storage = new CoordinatedStorageFactory();
        RamService service = new RamService(storage, null);
        service.initializeStorage();
        ResourceShare share = service.createResourceShare(
                "concurrent-mutation", List.of(), List.of(RESOURCE_ARN), false, "us-east-1", OWNER);
        storage.armShareScans();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> disassociateThread = new AtomicReference<>();
        AtomicReference<Thread> tagThread = new AtomicReference<>();
        try {
            Future<?> disassociate = executor.submit(() -> {
                disassociateThread.set(Thread.currentThread());
                service.disassociateResourceShare(share.getResourceShareArn(),
                        List.of(RESOURCE_ARN), List.of(), OWNER, "us-east-1");
            });
            assertTrue(storage.shareStorage.firstScanEntered.await(5, TimeUnit.SECONDS),
                    "the disassociation must reach its share scan");
            Future<?> tag = executor.submit(() -> {
                tagThread.set(Thread.currentThread());
                service.tagResource(share.getResourceShareArn(), Map.of("race", "preserve"),
                        OWNER, "us-east-1");
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (storage.shareStorage.releaseFirstScan.getCount() != 0
                    && !blockedOn(tagThread.get(), disassociateThread.get())
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(storage.shareStorage.releaseFirstScan.getCount() == 0
                            || blockedOn(tagThread.get(), disassociateThread.get()),
                    "the tag worker must scan or block on the service lock");
            storage.shareStorage.releaseFirstScan.countDown();
            disassociate.get(15, TimeUnit.SECONDS);
            tag.get(15, TimeUnit.SECONDS);
        } finally {
            storage.shareStorage.releaseFirstScan.countDown();
            executor.shutdownNow();
        }

        ResourceShare stored = service.getResourceShares(OWNER, "SELF", "us-east-1").getFirst();
        assertTrue(stored.getResourceArns().isEmpty(),
                "the disassociation must survive the concurrent tag update");
        assertTrue("preserve".equals(stored.getTags().get("race")),
                "the tag must survive the concurrent disassociation");
    }

    private static boolean blockedOn(Thread waiter, Thread owner) {
        if (waiter == null || owner == null) {
            return false;
        }
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == owner.threadId();
    }

    private static final class CoordinatedStorageFactory extends StorageFactory {
        private final CoordinatedStorage shareStorage = new CoordinatedStorage();
        private final Map<String, AccountAwareStorageBackend<Object>> stores = new HashMap<>();

        private CoordinatedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                         TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<Object> store = stores.computeIfAbsent(fileName, ignored -> {
                if (fileName.equals("ram-resource-shares.json")) {
                    return new AccountAwareStorageBackend<>(shareStorage, null, "000000000000");
                }
                return AccountAwareStorageBackend.inMemory("000000000000");
            });
            return (AccountAwareStorageBackend<V>) store;
        }

        private void armShareScans() {
            shareStorage.armScans();
        }
    }

    private static final class CoordinatedStorage extends InMemoryStorage<String, Object> {
        private final AtomicInteger coordinatedScans = new AtomicInteger();
        private volatile CountDownLatch firstScanEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirstScan = new CountDownLatch(0);

        private void armScans() {
            coordinatedScans.set(0);
            firstScanEntered = new CountDownLatch(1);
            releaseFirstScan = new CountDownLatch(1);
        }

        @Override
        public List<Object> scan(Predicate<String> keyFilter) {
            List<Object> snapshot = super.scan(keyFilter);
            int scanNumber = coordinatedScans.incrementAndGet();
            if (scanNumber == 1) {
                firstScanEntered.countDown();
                try {
                    assertTrue(releaseFirstScan.await(5, TimeUnit.SECONDS),
                            "the first share scan must be released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("share scan interrupted", e);
                }
            } else if (scanNumber == 2) {
                releaseFirstScan.countDown();
            }
            return snapshot;
        }
    }
}
