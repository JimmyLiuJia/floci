package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SsmParameterHistoryKeyIdIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParameterHistoryReturnsEachSecureVersionKeyId() {
        String name = "/history-key-id-" + UUID.randomUUID();
        try {
            put(name, "first", "alias/first-key", false);
            put(name, "second", "alias/second-key", true);
            put(name, "third", null, true);

            JsonPath history = given()
                    .header("X-Amz-Target", "AmazonSSM.GetParameterHistory")
                    .contentType(CONTENT_TYPE)
                    .body("{\"Name\":\"" + name + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath();

            assertEquals("alias/first-key", history.getString("Parameters[0].KeyId"));
            assertEquals("alias/second-key", history.getString("Parameters[1].KeyId"));
            assertEquals("alias/aws/ssm", history.getString("Parameters[2].KeyId"));
        } finally {
            given()
                    .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
                    .contentType(CONTENT_TYPE)
                    .body("{\"Name\":\"" + name + "\"}")
                    .when().post("/");
        }
    }

    private void put(String name, String value, String keyId, boolean overwrite) {
        String keyIdField = keyId == null ? "" : ",\"KeyId\":\"" + keyId + "\"";
        given()
                .header("X-Amz-Target", "AmazonSSM.PutParameter")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\",\"Value\":\"" + value
                        + "\",\"Type\":\"SecureString\"" + keyIdField + ",\"Overwrite\":" + overwrite + "}")
                .when().post("/")
                .then().statusCode(200);
    }
}
