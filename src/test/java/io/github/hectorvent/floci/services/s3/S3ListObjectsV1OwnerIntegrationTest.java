package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.github.hectorvent.floci.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Owner;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class S3ListObjectsV1OwnerIntegrationTest {

    @Test
    void listObjectsV1IncludesTheObjectOwnerWithoutAnOptIn() {
        String bucket = "owner-identity-list-v1-" + UUID.randomUUID();
        String key = "key.txt";
        try (S3Client client = S3TestClients.pathStyle()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("x"));

                Owner owner = client.listObjects(request -> request.bucket(bucket))
                        .contents().getFirst().owner();
                assertNotNull(owner);
                assertEquals(AwsCanonicalUserId.forAccount("741624100001"), owner.id());
            } finally {
                client.deleteObject(request -> request.bucket(bucket).key(key));
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }
}
