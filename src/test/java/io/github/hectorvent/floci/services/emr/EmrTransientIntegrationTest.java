package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EmrTransientIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void runJobFlowWithStepsTerminatesDespiteTerminationProtection() {
        String clusterId = emr("RunJobFlow",
                "{\"Name\":\"transient-initial\",\"Instances\":{\"TerminationProtected\":true},"
                        + "\"Steps\":[{\"Name\":\"initial\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(true))
                .body("Cluster.TerminationProtected", equalTo(true))
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"))
                .body("Cluster.Status.Timeline.EndDateTime", notNullValue());
    }

    @Test
    void addJobFlowStepsTerminatesTransientCluster() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"transient-later\",\"Instances\":{}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("WAITING"));

        emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"later\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200);
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"))
                .body("Cluster.Status.Timeline.EndDateTime", notNullValue());
    }

    @Test
    void keepAliveClusterWaitsAfterItsSteps() {
        String clusterId = emr("RunJobFlow",
                "{\"Name\":\"keep-alive\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true},"
                        + "\"Steps\":[{\"Name\":\"initial\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(false))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.Status.Timeline.ReadyDateTime", notNullValue())
                .body("Cluster.Status.Timeline.EndDateTime", nullValue());
    }

    @Test
    void transientClusterWithoutStepsRemainsWaiting() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"no-steps\",\"Instances\":{}}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(true))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.Status.Timeline.EndDateTime", nullValue());
    }
}
