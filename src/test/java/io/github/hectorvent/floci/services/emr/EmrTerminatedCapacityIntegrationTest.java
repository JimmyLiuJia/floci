package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EmrTerminatedCapacityIntegrationTest {

    private static final String GROUPS = "\"InstanceGroups\":["
            + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
            + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2}]";
    private static final String FLEETS = "\"InstanceFleets\":["
            + "{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
            + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
            + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2,"
            + "\"TargetSpotCapacity\":3,\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]";
    private static final String STEP = "{\"Name\":\"complete\","
            + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void initialStepsTerminateInstanceGroupsAndReleaseRunningCount() {
        String id = emr("RunJobFlow", "{\"Name\":\"group-auto\",\"Instances\":{"
                + GROUPS + "},\"Steps\":[" + STEP + "]}")
                .then().statusCode(200).extract().path("JobFlowId");

        assertTerminatedGroup(id);
    }

    @Test
    void addedStepsTerminateInstanceFleetsAndReleaseProvisionedCapacity() {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-auto\",\"Instances\":{"
                + FLEETS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String request = clusterRequest(id);
        emr("ListInstanceFleets", request).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(3));

        emr("AddJobFlowSteps", "{\"JobFlowId\":\"" + id + "\",\"Steps\":[" + STEP + "]}")
                .then().statusCode(200);
        assertTerminatedFleet(id);
    }

    @Test
    void manualTerminationReleasesInstanceGroups() {
        String id = emr("RunJobFlow", "{\"Name\":\"group-manual\",\"Instances\":{"
                + "\"KeepJobFlowAliveWhenNoSteps\":true," + GROUPS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("ListInstanceGroups", clusterRequest(id)).then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(2));

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}")
                .then().statusCode(200);
        assertTerminatedGroup(id);
    }

    @Test
    void manualTerminationReleasesInstanceFleets() {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-manual\",\"Instances\":{"
                + "\"KeepJobFlowAliveWhenNoSteps\":true," + FLEETS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("ListInstanceFleets", clusterRequest(id)).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(3));

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}")
                .then().statusCode(200);
        assertTerminatedFleet(id);
    }

    private static String clusterRequest(String id) {
        return "{\"ClusterId\":\"" + id + "\"}";
    }

    private static void assertTerminatedGroup(String id) {
        emr("DescribeCluster", clusterRequest(id)).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("ListInstanceGroups", clusterRequest(id)).then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.RequestedInstanceCount", equalTo(1))
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.RunningInstanceCount", equalTo(0))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RequestedInstanceCount", equalTo(2))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(0));
    }

    private static void assertTerminatedFleet(String id) {
        emr("DescribeCluster", clusterRequest(id)).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("ListInstanceFleets", clusterRequest(id)).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.TargetOnDemandCapacity", equalTo(1))
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.ProvisionedOnDemandCapacity", equalTo(0))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.TargetOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.TargetSpotCapacity", equalTo(3))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(0))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(0));
    }
}
