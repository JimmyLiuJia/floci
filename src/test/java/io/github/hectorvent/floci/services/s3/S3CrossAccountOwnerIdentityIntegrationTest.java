package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(S3CrossAccountOwnerIdentityIntegrationTest.GlobalBucketNamespaceProfile.class)
class S3CrossAccountOwnerIdentityIntegrationTest {

    private static final String ACCOUNT_ONE = "000000000011";
    private static final String ACCOUNT_TWO = "000000000012";

    public static final class GlobalBucketNamespaceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.global-bucket-namespace", "true");
        }
    }

    @Test
    void listObjectsV2FetchOwnerUsesTheCrossAccountObjectWritersCanonicalUserId() {
        String bucket = "object-writer-v2-" + UUID.randomUUID();
        String key = "writer.txt";
        given().header("Authorization", authorization(ACCOUNT_ONE))
                .header("x-amz-object-ownership", "ObjectWriter")
        .when().put("/" + bucket)
        .then().statusCode(200);
        try {
            given().header("Authorization", authorization(ACCOUNT_TWO))
            .when().put("/" + bucket + "/" + key)
            .then().statusCode(200);

            given().header("Authorization", authorization(ACCOUNT_ONE))
                    .queryParam("list-type", "2")
                    .queryParam("fetch-owner", "true")
            .when().get("/" + bucket)
            .then().statusCode(200)
                    .body(containsString("<Key>" + key + "</Key>"))
                    .body(containsString("<Owner><ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_TWO)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"));

            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().get("/" + bucket + "/" + key + "?acl")
            .then().statusCode(200)
                    .body(containsString("<Owner><ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_TWO)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"));

            setOwnership(bucket, "BucketOwnerEnforced");
            assertListedOwner(bucket, key, ACCOUNT_ONE);
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().get("/" + bucket + "/" + key + "?acl")
            .then().statusCode(200)
                    .body(containsString("<Owner><ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_ONE)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"));

            setOwnership(bucket, "ObjectWriter");
            assertListedOwner(bucket, key, ACCOUNT_TWO);
        } finally {
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket + "/" + key)
            .then().statusCode(204);
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket)
            .then().statusCode(204);
        }
    }

    @Test
    void listObjectsV2FetchOwnerFollowsBucketOwnershipModes() {
        String enforcedBucket = "owner-enforced-" + UUID.randomUUID();
        createBucket(enforcedBucket, "BucketOwnerEnforced");
        try {
            putObject(enforcedBucket, "enforced.txt", null);
            assertListedOwner(enforcedBucket, "enforced.txt", ACCOUNT_ONE);
        } finally {
            deleteBucket(enforcedBucket, "enforced.txt");
        }

        String preferredBucket = "owner-preferred-" + UUID.randomUUID();
        createBucket(preferredBucket, "BucketOwnerPreferred");
        try {
            putObject(preferredBucket, "writer.txt", null);
            putObject(preferredBucket, "bucket-owner.txt", "bucket-owner-full-control");
            assertListedOwner(preferredBucket, "writer.txt", ACCOUNT_TWO);
            assertListedOwner(preferredBucket, "bucket-owner.txt", ACCOUNT_ONE);
        } finally {
            deleteBucket(preferredBucket, "writer.txt", "bucket-owner.txt");
        }

        String writerBucket = "owner-writer-acl-" + UUID.randomUUID();
        createBucket(writerBucket, "ObjectWriter");
        try {
            putObject(writerBucket, "writer-grant.txt", "bucket-owner-full-control");
            assertListedOwner(writerBucket, "writer-grant.txt", ACCOUNT_TWO);
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().get("/" + writerBucket + "/writer-grant.txt?acl")
            .then().statusCode(200)
                    .body(containsString("<Owner><ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_TWO)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"))
                    .body(containsString("<ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_ONE) + "</ID>"));

            given().header("Authorization", authorization(ACCOUNT_TWO))
                    .header("x-amz-acl", "bucket-owner-full-control")
            .when().put("/" + writerBucket + "/writer-grant.txt?acl")
            .then().statusCode(200);
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().get("/" + writerBucket + "/writer-grant.txt?acl")
            .then().statusCode(200)
                    .body(containsString("<Owner><ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_TWO)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"))
                    .body(containsString("<ID>" + AwsCanonicalUserId.forAccount(ACCOUNT_ONE) + "</ID>"));
        } finally {
            deleteBucket(writerBucket, "writer-grant.txt");
        }
    }

    @Test
    void deletingOwnershipControlsRestoresWriterOwnershipForNewObjects() {
        String bucket = "owner-controls-deleted-" + UUID.randomUUID();
        createBucket(bucket, "BucketOwnerPreferred");
        try {
            putObject(bucket, "before.txt", "bucket-owner-full-control");
            assertListedOwner(bucket, "before.txt", ACCOUNT_ONE);

            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket + "?ownershipControls")
            .then().statusCode(204);

            putObject(bucket, "after.txt", "bucket-owner-full-control");
            assertListedOwner(bucket, "before.txt", ACCOUNT_ONE);
            assertListedOwner(bucket, "after.txt", ACCOUNT_TWO);
        } finally {
            deleteBucket(bucket, "before.txt", "after.txt");
        }
    }

    @Test
    void copyAndMultipartCompletionKeepTheObjectWriterAsOwner() {
        String bucket = "owner-copy-mpu-" + UUID.randomUUID();
        createBucket(bucket, "ObjectWriter");
        try {
            putObject(bucket, "source.txt", null);
            given().header("Authorization", authorization(ACCOUNT_TWO))
                    .header("x-amz-copy-source", bucket + "/source.txt")
            .when().put("/" + bucket + "/copy.txt")
            .then().statusCode(200);

            String uploadId = given().header("Authorization", authorization(ACCOUNT_TWO))
            .when().post("/" + bucket + "/multipart.txt?uploads")
            .then().statusCode(200)
                    .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            String partETag = given().header("Authorization", authorization(ACCOUNT_TWO))
                    .body("part-one")
            .when().put("/" + bucket + "/multipart.txt?uploadId=" + uploadId + "&partNumber=1")
            .then().statusCode(200).extract().header("ETag");
            String completeXml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                    + partETag + "</ETag></Part></CompleteMultipartUpload>";
            given().header("Authorization", authorization(ACCOUNT_TWO))
                    .contentType("application/xml").body(completeXml)
            .when().post("/" + bucket + "/multipart.txt?uploadId=" + uploadId)
            .then().statusCode(200);

            assertListedOwner(bucket, "source.txt", ACCOUNT_TWO);
            assertListedOwner(bucket, "copy.txt", ACCOUNT_TWO);
            assertListedOwner(bucket, "multipart.txt", ACCOUNT_TWO);
        } finally {
            deleteBucket(bucket, "source.txt", "copy.txt", "multipart.txt");
        }
    }

    private static void createBucket(String bucket, String ownership) {
        given().header("Authorization", authorization(ACCOUNT_ONE))
                .header("x-amz-object-ownership", ownership)
        .when().put("/" + bucket)
        .then().statusCode(200);
    }

    private static void setOwnership(String bucket, String ownership) {
        given().header("Authorization", authorization(ACCOUNT_ONE))
                .body("<OwnershipControls><Rule><ObjectOwnership>" + ownership
                        + "</ObjectOwnership></Rule></OwnershipControls>")
        .when().put("/" + bucket + "?ownershipControls")
        .then().statusCode(200);
    }

    private static void putObject(String bucket, String key, String cannedAcl) {
        RequestSpecification request =
                given().header("Authorization", authorization(ACCOUNT_TWO));
        if (cannedAcl != null) {
            request.header("x-amz-acl", cannedAcl);
        }
        request.when().put("/" + bucket + "/" + key)
                .then().statusCode(200);
    }

    private static void assertListedOwner(String bucket, String key, String account) {
        String ownerId = given().header("Authorization", authorization(ACCOUNT_ONE))
                .queryParam("list-type", "2")
                .queryParam("fetch-owner", "true")
                .queryParam("prefix", key)
        .when().get("/" + bucket)
        .then().statusCode(200)
                .body(containsString("<Key>" + key + "</Key>"))
                .extract().xmlPath().getString("ListBucketResult.Contents.Owner.ID");
        assertEquals(AwsCanonicalUserId.forAccount(account), ownerId);

        String v1OwnerId = given().header("Authorization", authorization(ACCOUNT_ONE))
                .queryParam("prefix", key)
        .when().get("/" + bucket)
        .then().statusCode(200)
                .body(containsString("<Key>" + key + "</Key>"))
                .extract().xmlPath().getString("ListBucketResult.Contents.Owner.ID");
        assertEquals(AwsCanonicalUserId.forAccount(account), v1OwnerId);
    }

    private static void deleteBucket(String bucket, String... keys) {
        for (String key : keys) {
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket + "/" + key)
            .then().statusCode(204);
        }
        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().delete("/" + bucket)
        .then().statusCode(204);
    }

    private static String authorization(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261002/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
