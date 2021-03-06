/*
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.jaxrs;

import java.util.UUID;

import org.joda.time.LocalDate;
import org.killbill.automaton.StateMachineConfig;
import org.killbill.billing.account.api.ImmutableAccountData;
import org.killbill.billing.api.FlakyRetryAnalyzer;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Catalog;
import org.killbill.billing.catalog.api.PriceListSet;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.client.RequestOptions;
import org.killbill.billing.client.model.Account;
import org.killbill.billing.client.model.PaymentMethod;
import org.killbill.billing.client.model.PaymentMethodPluginDetail;
import org.killbill.billing.client.model.Subscription;
import org.killbill.billing.client.model.Tenant;
import org.killbill.billing.overdue.api.OverdueConfig;
import org.killbill.billing.util.cache.Cachable.CacheType;
import org.killbill.billing.util.cache.CacheController;
import org.killbill.billing.util.config.tenant.PerTenantConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.io.Resources;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestCache extends TestJaxrsBase {

    @Test(groups = "slow", description = "Can Invalidate (clear) a Cache by name")
    public void testInvalidateCacheByName() throws Exception {
        // get Ehcache item with name "record-id"
        final CacheController<String, Long> cache = cacheControllerDispatcher.getCacheController(CacheType.RECORD_ID);
        // verify that it is not null and has one stored key (the default tenant created for all integration tests)
        assertNotNull(cache);
        Assert.assertEquals(cache.size(), 1);

        // invalidate the specified cache
        killBillClient.invalidateCacheByName(CacheType.RECORD_ID.getCacheName(), requestOptions);

        // verify that now the cache is empty and has no keys stored
        Assert.assertEquals(cache.size(), 0);
    }

    @Test(groups = "slow", description = "Can Invalidate (clear) all available Caches")
    public void testInvalidateAllCaches() throws Exception {
        // get Ehcache item with name "record-id"
        final CacheController<String, Long> cache = cacheControllerDispatcher.getCacheController(CacheType.RECORD_ID);
        // verify that it is not null and has one stored key (the default tenant created for all integration tests)
        assertNotNull(cache);
        Assert.assertEquals(cache.size(), 1);

        // invalidate all caches
        killBillClient.invalidateAllCaches(requestOptions);

        // verify that now the cache is empty and has no keys stored
        Assert.assertEquals(cache.size(), 0);
    }

    @Test(groups = "slow", description = "Can Invalidate (clear) all Account Caches by accountId")
    public void testInvalidateCacheByAccount() throws Exception {
        final Account input = createAccountNoPMBundleAndSubscription();

        // get all caches per account level
        final CacheController<String, Long> accountRecordIdCache = cacheControllerDispatcher.getCacheController(CacheType.ACCOUNT_RECORD_ID);
        final CacheController<Long, ImmutableAccountData> accountImmutableCache = cacheControllerDispatcher.getCacheController(CacheType.ACCOUNT_IMMUTABLE);
        final CacheController<UUID, Integer> accountBcdCache = cacheControllerDispatcher.getCacheController(CacheType.ACCOUNT_BCD);

        // verify that they are not null and have the accountId stored as a key (the account created before)
        assertTrue(accountRecordIdCache.isKeyInCache(input.getAccountId().toString()));
        final Long accountRecordId = accountRecordIdCache.get(input.getAccountId().toString(), null);
        assertTrue(accountImmutableCache.isKeyInCache(accountRecordId));
        assertTrue(accountBcdCache.isKeyInCache(input.getAccountId()));

        // invalidate caches per account level by accountId
        killBillClient.invalidateCacheByAccount(input.getAccountId().toString(), requestOptions);

        // verify that now the caches don't have the accountId key stored
        Assert.assertFalse(accountRecordIdCache.isKeyInCache(input.getAccountId().toString()));
        Assert.assertFalse(accountImmutableCache.isKeyInCache(accountRecordId));
        Assert.assertFalse(accountBcdCache.isKeyInCache(input.getAccountId()));
    }

    // Flaky, see https://github.com/killbill/killbill/issues/860
    @Test(groups = "slow", description = "Can Invalidate (clear) all Tenant Caches for current Tenant", retryAnalyzer = FlakyRetryAnalyzer.class)
    public void testInvalidateCacheByTenant() throws Exception {
        // creating a new Tenant for this test
        final String testApiKey = "testApiKey";
        final String testApiSecret = "testApiSecret";
        final Tenant tenant = new Tenant();
        tenant.setApiKey(testApiKey);
        tenant.setApiSecret(testApiSecret);
        loginTenant(testApiKey, testApiSecret);
        Tenant currentTenant = killBillClient.createTenant(tenant, false, requestOptions);

        // using custom RequestOptions with the new Tenant created before
        RequestOptions inputOptions = RequestOptions.builder()
                                                    .withCreatedBy(createdBy)
                                                    .withReason(reason)
                                                    .withComment(comment)
                                                    .withTenantApiKey(currentTenant.getApiKey())
                                                    .withTenantApiSecret(currentTenant.getApiSecret())
                                                    .build();

        // Uploading the test catalog using the new Tenant created before
        killBillClient.uploadXMLCatalog(Resources.getResource("SpyCarAdvanced.xml").getPath(), inputOptions);

        // creating an Account with PaymentMethod and a Subscription
        createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoiceWithInputOptions(inputOptions);

        // get all caches per tenant level
        final CacheController<String, Long> tenantRecordIdCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_RECORD_ID);
        final CacheController<String, StateMachineConfig> tenantPaymentStateMachineConfigCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_PAYMENT_STATE_MACHINE_CONFIG);
        final CacheController<String, org.killbill.billing.tenant.api.Tenant> tenantCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT);
        final CacheController<String, String> tenantKvCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_KV);
        final CacheController<Long, PerTenantConfig> tenantConfigCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_CONFIG);
        final CacheController<Long, OverdueConfig> tenantOverdueConfigCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_OVERDUE_CONFIG);
        final CacheController<Long, Catalog> tenantCatalogCache = cacheControllerDispatcher.getCacheController(CacheType.TENANT_CATALOG);

        // verify that they are not null and have the expected tenant information
        assertTrue(tenantRecordIdCache.isKeyInCache(currentTenant.getTenantId().toString()));
        final Long tenantRecordId = tenantRecordIdCache.get(currentTenant.getTenantId().toString(), null);

        assertTrue(hasKeysByTenantRecordId(tenantPaymentStateMachineConfigCache, tenantRecordId.toString()));
        assertTrue(tenantCache.isKeyInCache(testApiKey));
        assertTrue(hasKeysByTenantRecordId(tenantKvCache, tenantRecordId.toString()));
        assertTrue(tenantConfigCache.isKeyInCache(tenantRecordId));
        assertTrue(tenantOverdueConfigCache.isKeyInCache(tenantRecordId));
        assertTrue(tenantCatalogCache.isKeyInCache(tenantRecordId));

        // invalidate caches per tenant level
        killBillClient.invalidateCacheByTenant(inputOptions);

        // verify that now the caches don't have the previous values
        assertFalse(tenantRecordIdCache.isKeyInCache(currentTenant.getTenantId().toString()));
        assertFalse(hasKeysByTenantRecordId(tenantPaymentStateMachineConfigCache, tenantRecordId.toString()));
        assertFalse(tenantCache.isKeyInCache(testApiKey));
        assertFalse(hasKeysByTenantRecordId(tenantKvCache, tenantRecordId.toString()));
        assertFalse(tenantConfigCache.isKeyInCache(tenantRecordId));
        assertFalse(tenantOverdueConfigCache.isKeyInCache(tenantRecordId));
        assertFalse(tenantCatalogCache.isKeyInCache(tenantRecordId));
    }

    private boolean hasKeysByTenantRecordId(final CacheController<String, ?> tenantCache, final String tenantRecordId) {
        for (final String key : tenantCache.getKeys()) {
            if (key.endsWith("::" + tenantRecordId)) {
                return true;
            }
        }
        return false;
    }

    private void createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoiceWithInputOptions(final RequestOptions inputOptions) throws Exception {
        Account account = killBillClient.createAccount(getAccount(), inputOptions);

        final PaymentMethodPluginDetail info = new PaymentMethodPluginDetail();
        info.setProperties(null);
        final PaymentMethod paymentMethodJson = new PaymentMethod(null, UUID.randomUUID().toString(), account.getAccountId(), true, PLUGIN_NAME, info);
        killBillClient.createPaymentMethod(paymentMethodJson, inputOptions);

        final Subscription subscription = new Subscription();
        subscription.setAccountId(account.getAccountId());
        subscription.setExternalKey(UUID.randomUUID().toString());
        subscription.setProductName("Sports");
        subscription.setProductCategory(ProductCategory.BASE);
        subscription.setBillingPeriod(BillingPeriod.MONTHLY);
        subscription.setPriceList(PriceListSet.DEFAULT_PRICELIST_NAME);

        clock.resetDeltaFromReality();
        clock.setDay(new LocalDate(2013, 3, 1));
        final Subscription subscriptionJson = killBillClient.createSubscription(subscription, clock.getUTCToday(), DEFAULT_WAIT_COMPLETION_TIMEOUT_SEC, inputOptions);

        assertNotNull(subscriptionJson);
        clock.addDays(32);
        crappyWaitForLackOfProperSynchonization();
    }
}
