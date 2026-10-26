package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LambdaListFunctionsPaginationIntegrationTest {

    @Inject LambdaService service;

    @Test
    void listFunctionsHonorsMaxItemsAndMarker() {
        List<String> names = new ArrayList<>();
        try {
            String suffix = UUID.randomUUID().toString();
            for (String letter : List.of("b", "a", "c")) {
                String name = "issue-page-" + suffix + "-" + letter;
                given().contentType("application/json")
                        .body("""
                                {"FunctionName":"%s","Runtime":"nodejs20.x",
                                 "Role":"arn:aws:iam::000000000000:role/lambda-role",
                                 "Handler":"index.handler"}
                                """.formatted(name))
                        .post("/2015-03-31/functions").then().statusCode(201);
                names.add(name);
            }
            String firstMarker = given().queryParam("MaxItems", 1)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(1)))
                    .body("NextMarker", notNullValue())
                    .extract().path("NextMarker");
            String secondMarker = given().queryParam("MaxItems", 1).queryParam("Marker", firstMarker)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(0)))
                    .body("NextMarker", notNullValue())
                    .extract().path("NextMarker");
            given().queryParam("MaxItems", 1).queryParam("Marker", secondMarker)
                    .get("/2015-03-31/functions").then().statusCode(200)
                    .body("Functions.FunctionName", contains(names.get(2)))
                    .body("$", not(hasKey("NextMarker")));
        } finally {
            for (String name : names) {
                service.deleteFunction("us-east-1", name);
            }
        }
    }

    @Test
    void listFunctionsRejectsAnUnknownMarker() {
        given().queryParam("Marker", "not-an-issued-marker")
                .get("/2015-03-31/functions").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
    }

    @Test
    void listFunctionsRejectsOutOfRangeMaxItems() {
        for (int maxItems : List.of(0, 10001)) {
            given().queryParam("MaxItems", maxItems)
                    .get("/2015-03-31/functions").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
    }
}
