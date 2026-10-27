package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.MapRun;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineAlias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class StepFunctionsListExecutionsServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ARN = "arn:aws:states:us-east-1:" + ACCOUNT + ":stateMachine:pages";

    private AccountAwareStorageBackend<Execution> executionStore;
    private StepFunctionsService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        executionStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        doReturn(AccountAwareStorageBackend.<StateMachine>inMemory(ACCOUNT))
                .doReturn(executionStore)
                .doReturn(AccountAwareStorageBackend.<Activity>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<MapRun>inMemory(ACCOUNT))
                .doReturn(AccountAwareStorageBackend.<StateMachineAlias>inMemory(ACCOUNT))
                .when(factory).create(anyString(), anyString(), any());
        service = new StepFunctionsService(factory, new RegionResolver("us-east-1", ACCOUNT), null,
                new ObjectMapper(), mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class));

        put("new", 3.0, "SUCCEEDED");
        put("running", 2.0, "RUNNING");
        put("old", 1.0, "SUCCEEDED");
    }

    @Test
    void pagesNewestExecutionsAndResumesFromTheOpaqueToken() {
        PaginatedResult<Execution> first = service.listExecutions(ARN, null, 1, null);
        assertEquals(List.of("new"), first.items().stream().map(Execution::getName).toList());
        assertNotNull(first.nextToken());

        PaginatedResult<Execution> second = service.listExecutions(ARN, null, 1, first.nextToken());
        assertEquals(List.of("running"), second.items().stream().map(Execution::getName).toList());
        assertNotNull(second.nextToken());
    }

    @Test
    void appliesStatusFilterBeforePaging() {
        PaginatedResult<Execution> first = service.listExecutions(ARN, "SUCCEEDED", 1, null);
        assertEquals(List.of("new"), first.items().stream().map(Execution::getName).toList());
        assertNotNull(first.nextToken());

        PaginatedResult<Execution> second = service.listExecutions(ARN, "SUCCEEDED", 1, first.nextToken());
        assertEquals(List.of("old"), second.items().stream().map(Execution::getName).toList());
    }

    @Test
    void rejectsATokenIssuedForAnotherStateMachineOrFilter() {
        String token = service.listExecutions(ARN, null, 1, null).nextToken();
        assertThrows(AwsException.class, () -> service.listExecutions(
                ARN + "-other", null, 1, token));
        assertThrows(AwsException.class, () -> service.listExecutions(ARN, "SUCCEEDED", 1, token));
    }

    private void put(String name, double startDate, String status) {
        Execution execution = new Execution();
        execution.setExecutionArn(ARN + ":" + name);
        execution.setStateMachineArn(ARN);
        execution.setName(name);
        execution.setStatus(status);
        execution.setStartDate(startDate);
        executionStore.put(execution.getExecutionArn(), execution);
    }
}
