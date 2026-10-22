package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WafV2LoggingConfigurationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String ACL_KEY = "REGIONAL:acl-id";
    private static final String ACL_ARN =
            "arn:aws:wafv2:us-east-1:" + ACCOUNT_ID + ":regional/webacl/acl/acl-id";

    private InMemoryStorage<String, WebAcl> rawWebAcls;
    private WafV2Service service;

    @BeforeEach
    void setUp() {
        rawWebAcls = new InMemoryStorage<>();
        AccountAwareStorageBackend<WebAcl> webAcls =
                new AccountAwareStorageBackend<>(rawWebAcls, null, ACCOUNT_ID);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                "wafv2-webacls.json".equals(invocation.getArgument(1, String.class))
                        ? webAcls : AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        service = new WafV2Service(storageFactory,
                new RegionResolver("us-east-1", ACCOUNT_ID), new ObjectMapper());
    }

    @Test
    void putLoggingConfigurationFindsUnprefixedLegacyWebAcl() {
        WebAcl acl = webAcl(ACL_ARN);
        rawWebAcls.put(ACL_KEY, acl);

        assertEquals("{}", service.putLoggingConfiguration(ACL_ARN, "{}"));
        assertEquals("{}", service.getLoggingConfiguration(ACL_ARN));
        assertFalse(rawWebAcls.get(ACL_KEY).isPresent());
        assertEquals(acl, rawWebAcls.get(ACCOUNT_ID + "/" + ACL_KEY).orElseThrow());
    }

    @Test
    void associateWebAclFindsUnprefixedLegacyWebAcl() {
        WebAcl acl = webAcl(ACL_ARN);
        rawWebAcls.put(ACL_KEY, acl);
        String resourceArn = "arn:aws:apigateway:us-east-1::/restapis/test/stages/prod";

        service.associateWebAcl(ACL_ARN, resourceArn);

        assertEquals(acl, service.getWebAclForResource(resourceArn));
        assertFalse(rawWebAcls.get(ACL_KEY).isPresent());
    }

    @Test
    void putLoggingConfigurationDoesNotMigrateAnotherAccountsLegacyWebAcl() {
        String foreignArn = "arn:aws:wafv2:us-east-1:111111111111:regional/webacl/acl/acl-id";
        rawWebAcls.put(ACL_KEY, webAcl(foreignArn));

        AwsException error = assertThrows(AwsException.class,
                () -> service.putLoggingConfiguration(foreignArn, "{}"));

        assertEquals("WAFNonexistentItemException", error.getErrorCode());
        assertTrue(rawWebAcls.get(ACL_KEY).isPresent());
        assertFalse(rawWebAcls.get(ACCOUNT_ID + "/" + ACL_KEY).isPresent());
    }

    private static WebAcl webAcl(String arn) {
        WebAcl acl = new WebAcl();
        acl.setId("acl-id");
        acl.setName("acl");
        acl.setScope("REGIONAL");
        acl.setArn(arn);
        return acl;
    }
}
