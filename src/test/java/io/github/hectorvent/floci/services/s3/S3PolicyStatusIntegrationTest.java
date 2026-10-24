package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/** Regression coverage for the S3 GetBucketPolicyStatus REST subresource. */
@QuarkusTest
class S3PolicyStatusIntegrationTest {

    @Test
    void reportsNoSuchBucketPolicyWhenTheBucketHasNoPolicy() {
        String bucket = "policy-status-private-" + UUID.randomUUID();
        given().when().put("/" + bucket).then().statusCode(200);

        given().when().get("/" + bucket + "?policyStatus")
                .then()
                .statusCode(404)
                .body(containsString("<Code>NoSuchBucketPolicy</Code>"));
    }

    @Test
    void reportsWhetherTheBucketPolicyIsPublic() {
        String bucket = "policy-status-" + UUID.randomUUID();
        given().when().put("/" + bucket).then().statusCode(200);

        given().body("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(bucket))
                .when().put("/" + bucket + "?policy")
                .then().statusCode(200);

        given().when().get("/" + bucket + "?policyStatus")
                .then()
                .statusCode(200)
                .body(startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><PolicyStatus"))
                .body(containsString("<IsPublic>true</IsPublic>"));
    }
}
