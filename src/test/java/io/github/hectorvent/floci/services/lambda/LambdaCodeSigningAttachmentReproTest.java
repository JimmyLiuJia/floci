package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaCodeSigningAttachmentReproTest {

    private static final String CONFIGS = "/2020-04-22/code-signing-configs";
    private static final String FUNCTION = "/2015-03-31/functions/signing-attachment-repro";
    private static final String ATTACHMENT =
            "/2020-06-30/functions/signing-attachment-repro/code-signing-config";
    private static final String PROFILE =
            "arn:aws:signer:us-east-1:000000000000:/signing-profiles/prof/AbCdEfGhIj";

    @Test
    void putFunctionCodeSigningConfigAttachesAnExistingConfig() {
        String arn = given().contentType("application/json")
                .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]}}")
                .when().post(CONFIGS)
                .then().statusCode(201)
                .extract().path("CodeSigningConfig.CodeSigningConfigArn");
        try {
            given().contentType("application/json")
                    .body("{\"FunctionName\":\"signing-attachment-repro\",\"Runtime\":\"nodejs20.x\","
                            + "\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\","
                            + "\"Handler\":\"index.handler\"}")
                    .when().post("/2015-03-31/functions")
                    .then().statusCode(201);
            try {
                given().contentType("application/json")
                        .body("{\"CodeSigningConfigArn\":\"" + arn + "\"}")
                        .when().put(ATTACHMENT)
                        .then().statusCode(200)
                        .body("CodeSigningConfigArn", equalTo(arn));
            } finally {
                given().when().delete(FUNCTION).then().statusCode(204);
            }
        } finally {
            given().when().delete(CONFIGS + "/" + arn).then().statusCode(204);
        }
    }
}
