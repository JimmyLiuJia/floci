package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class RamGetResourceSharesPaginationIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=777777777779/20260101/us-east-1/ram/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourceSharesPaginatesFilteredSharesWithoutReusingOtherFilterTokens() {
        String firstArn = createShare("pagination-first");
        String secondArn = createShare("pagination-second");
        String arns = "\"%s\",\"%s\"".formatted(firstArn, secondArn);

        Response first = getShares("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":["
                + arns + "],\"maxResults\":1}");
        assertEquals(200, first.statusCode(), first.asString());
        List<String> firstPage = first.jsonPath().getList("resourceShares.resourceShareArn");
        assertEquals(1, firstPage.size(), first.asString());
        String nextToken = first.jsonPath().getString("nextToken");
        assertNotNull(nextToken, first.asString());

        Response second = getShares("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\""
                + secondArn + "\",\"" + firstArn + "\"],\"maxResults\":1,\"nextToken\":\""
                + nextToken + "\"}");
        assertEquals(200, second.statusCode(), second.asString());
        List<String> secondPage = second.jsonPath().getList("resourceShares.resourceShareArn");
        assertEquals(1, secondPage.size(), second.asString());
        assertNotEquals(firstPage.getFirst(), secondPage.getFirst());
        assertEquals(Set.of(firstArn, secondArn), Set.of(firstPage.getFirst(), secondPage.getFirst()));
        assertNull(second.jsonPath().getString("nextToken"), second.asString());

        Response changedFilter = getShares("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\""
                + firstArn + "\"],\"maxResults\":1,\"nextToken\":\"" + nextToken + "\"}");
        assertEquals(400, changedFilter.statusCode(), changedFilter.asString());
        assertEquals("InvalidNextTokenException", changedFilter.jsonPath().getString("__type"));

        Response otherAccount = given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=777777777778/20260101/us-east-1/ram/aws4_request")
                .body("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[" + arns
                        + "],\"maxResults\":1,\"nextToken\":\"" + nextToken + "\"}")
                .post("/getresourceshares");
        assertEquals(400, otherAccount.statusCode(), otherAccount.asString());
        assertEquals("InvalidNextTokenException", otherAccount.jsonPath().getString("__type"));
    }

    @Test
    void getResourceSharesRejectsInvalidPageSizeAndToken() {
        for (String maxResults : List.of("0", "501", "1.5", "\"1\"")) {
            Response response = getShares("{\"resourceOwner\":\"SELF\",\"maxResults\":"
                    + maxResults + "}");
            assertEquals(400, response.statusCode(), response.asString());
            assertEquals("InvalidParameterException", response.jsonPath().getString("__type"));
        }

        Response invalidToken = getShares("{\"resourceOwner\":\"SELF\",\"nextToken\":\"bad-token\"}");
        assertEquals(400, invalidToken.statusCode(), invalidToken.asString());
        assertEquals("InvalidNextTokenException", invalidToken.jsonPath().getString("__type"));

        Response emptyToken = getShares("{\"resourceOwner\":\"SELF\",\"nextToken\":\"\"}");
        assertEquals(400, emptyToken.statusCode(), emptyToken.asString());
        assertEquals("InvalidNextTokenException", emptyToken.jsonPath().getString("__type"));

        Response numericToken = getShares("{\"resourceOwner\":\"SELF\",\"nextToken\":1}");
        assertEquals(400, numericToken.statusCode(), numericToken.asString());
        assertEquals("InvalidNextTokenException", numericToken.jsonPath().getString("__type"));
    }

    private static String createShare(String name) {
        Response response = given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("{\"name\":\"" + name + "\"}")
                .post("/createresourceshare");
        assertEquals(200, response.statusCode(), response.asString());
        return response.jsonPath().getString("resourceShare.resourceShareArn");
    }

    private static Response getShares(String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .post("/getresourceshares");
    }
}
