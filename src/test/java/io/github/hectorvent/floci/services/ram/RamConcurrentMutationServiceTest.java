package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> disassociate = executor.submit(() -> {
                workersReady.countDown();
                await(start);
                service.disassociateResourceShare(share.getResourceShareArn(),
                        List.of(RESOURCE_ARN), List.of(), OWNER, "us-east-1");
            });
            Future<?> tag = executor.submit(() -> {
                workersReady.countDown();
                await(start);
                service.tagResource(share.getResourceShareArn(), Map.of("race", "preserve"),
                        OWNER, "us-east-1");
            });
            assertTrue(workersReady.await(5, TimeUnit.SECONDS), "both workers must reach the start gate");
            start.countDown();
            disassociate.get(15, TimeUnit.SECONDS);
            tag.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ResourceShare stored = service.getResourceShares(OWNER, "SELF", "us-east-1").getFirst();
        assertTrue(stored.getResourceArns().isEmpty(),
                "the disassociation must survive the concurrent tag update");
        assertTrue("preserve".equals(stored.getTags().get("race")),
                "the tag must survive the concurrent disassociation");
    }

    private static void await(CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker interrupted", e);
        }
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
        private volatile CountDownLatch scanGate = new CountDownLatch(0);

        private void armScans() {
            coordinatedScans.set(0);
            scanGate = new CountDownLatch(2);
        }

        @Override
        public List<Object> scan(Predicate<String> keyFilter) {
            List<Object> snapshot = super.scan(keyFilter);
            if (coordinatedScans.incrementAndGet() <= 2) {
                CountDownLatch gate = scanGate;
                gate.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("share scan interrupted", e);
                }
            }
            return snapshot;
        }
    }
}
