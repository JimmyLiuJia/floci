package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ListContainerInstancesRequest;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EcsContainerInstanceFilterIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";

    @Inject
    EcsService service;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return call(action, body, 200);
    }

    private static Response call(String action, String body, int expectedStatus) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    @Test
    void listContainerInstancesHonorsRunningTaskCountFilter() {
        String cluster = "query-filter-running-tasks";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}");

        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 1\"}")
                .then().body("containerInstanceArns", hasSize(0));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 0\"}")
                .then().body("containerInstanceArns", hasSize(1));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount greater_than 0\"}")
                .then().body("containerInstanceArns", hasSize(0));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount <= 0\"}")
                .then().body("containerInstanceArns", hasSize(1));
    }

    @Test
    void filtersBeforePaginatingAndCombinesWithStatus() {
        String cluster = "query-filter-pagination";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        String first = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}")
                .jsonPath().getString("containerInstance.containerInstanceArn");
        String excluded = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}")
                .jsonPath().getString("containerInstance.containerInstanceArn");
        String last = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}")
                .jsonPath().getString("containerInstance.containerInstanceArn");
        call("RegisterTaskDefinition", "{\"family\":\"query-filter-task\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}");
        Response started = call("StartTask", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + excluded + "\"],"
                + "\"taskDefinition\":\"query-filter-task\"}");
        started.then().body("tasks[0].lastStatus", equalTo("RUNNING"));
        String task = started.jsonPath().getString("tasks[0].taskArn");
        call("DescribeContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"containerInstances\":[\"" + excluded + "\"]}")
                .then().body("containerInstances[0].runningTasksCount", equalTo(1));

        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 1\"}")
                .then().body("containerInstanceArns", hasSize(1))
                .body("containerInstanceArns[0]", equalTo(excluded));
        try (EcsClient client = AwsTestClients.create(EcsClient.builder(), "test", Region.US_EAST_1)) {
            assertEquals(List.of(excluded), client.listContainerInstances(ListContainerInstancesRequest.builder()
                    .cluster(cluster).filter("runningTasksCount == 1").build()).containerInstanceArns());
        }

        EcsTask taskModel = service.describeTasks(cluster, List.of(task), "us-east-1").getFirst();
        taskModel.setLastStatus("PENDING");
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 1\"}")
                .then().body("containerInstanceArns[0]", equalTo(excluded));
        taskModel.setLastStatus("RUNNING");
        taskModel.setDesiredStatus("STOPPED");
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 1\"}")
                .then().body("containerInstanceArns", hasSize(0));
        taskModel.setDesiredStatus("RUNNING");

        String request = "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 0\",\"maxResults\":1";
        Response firstPage = call("ListContainerInstances", request + "}");
        firstPage.then().body("containerInstanceArns", hasSize(1))
                .body("nextToken", notNullValue());
        String nextToken = firstPage.jsonPath().getString("nextToken");
        Response secondPage = call("ListContainerInstances", request
                + ",\"nextToken\":\"" + nextToken + "\"}");
        secondPage.then().body("containerInstanceArns", hasSize(1))
                .body("nextToken", nullValue());
        assertEquals(Set.of(first, last), Set.of(
                firstPage.jsonPath().getString("containerInstanceArns[0]"),
                secondPage.jsonPath().getString("containerInstanceArns[0]")));

        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster
                + "\",\"containerInstances\":[\"" + last + "\"],\"status\":\"DRAINING\"}");
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"status\":\"DRAINING\",\"filter\":\"runningTasksCount == 0\"}")
                .then().body("containerInstanceArns", hasSize(1))
                .body("containerInstanceArns[0]", equalTo(last));

        call("StopTask", "{\"cluster\":\"" + cluster + "\",\"task\":\"" + task + "\"}");
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 0\"}")
                .then().body("containerInstanceArns", hasSize(3));
        call("DescribeContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"containerInstances\":[\"" + excluded + "\"]}")
                .then().body("containerInstances[0].runningTasksCount", equalTo(0));
    }

    @Test
    void describesCountWithinTheRequestedClusterWhenInstanceArnsAreReused() {
        String firstCluster = "query-filter-owner-a";
        String secondCluster = "query-filter-owner-b";
        call("CreateCluster", "{\"clusterName\":\"" + firstCluster + "\"}");
        call("CreateCluster", "{\"clusterName\":\"" + secondCluster + "\"}");
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + firstCluster + "\"}")
                .jsonPath().getString("containerInstance.containerInstanceArn");
        call("RegisterContainerInstance", "{\"cluster\":\"" + secondCluster
                + "\",\"containerInstanceArn\":\"" + instance + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"query-filter-owner-task\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}");
        call("StartTask", "{\"cluster\":\"" + firstCluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"],"
                + "\"taskDefinition\":\"query-filter-owner-task\"}");

        call("ListContainerInstances", "{\"cluster\":\"" + secondCluster
                + "\",\"filter\":\"runningTasksCount == 0\"}")
                .then().body("containerInstanceArns", hasSize(1));
        call("DescribeContainerInstances", "{\"cluster\":\"" + secondCluster
                + "\",\"containerInstances\":[\"" + instance + "\"]}")
                .then().body("containerInstances[0].runningTasksCount", equalTo(0));
    }

    @Test
    void rejectsUnsupportedAndMalformedFilters() {
        String cluster = "query-filter-invalid";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");

        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount ==\"}", 400)
                .then().body("message", containsString("container instance filter"));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"agentConnected == true\"}", 400)
                .then().body("message", containsString("container instance filter"));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster
                + "\",\"filter\":\"runningTasksCount == 9223372036854775808\"}", 400)
                .then().body("message", containsString("container instance filter"));
    }
}
