/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.aws.partners.saasfactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

public class TenantService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantService.class);
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Origin", "*");
    private final TenantServiceDAL dal = new TenantServiceDAL();

    public APIGatewayProxyResponseEvent getTenants(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("TenantService::getTenants");
        List<Tenant> tenants = dal.getTenants();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(HttpURLConnection.HTTP_OK)
                .withHeaders(CORS)
                .withBody(Utils.toJson(tenants));
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::getTenants exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent getTenant(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        Map<String, String> params = event.getPathParameters();
        String tenantId = params.get("id");
        LOGGER.info("TenantService::getTenant " + tenantId);
        Tenant tenant = dal.getTenant(tenantId);
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(HttpURLConnection.HTTP_OK)
                .withHeaders(CORS)
                .withBody(Utils.toJson(tenant));
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::getTenant exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent insertTenant(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("TenantService::insertTenant");
        APIGatewayProxyResponseEvent response = null;
        Tenant tenant = Utils.fromJson(event.getBody(), Tenant.class);
        if (tenant == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        } else {
            tenant = dal.insertTenant(tenant);
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(HttpURLConnection.HTTP_OK)
                    .withHeaders(CORS)
                    .withBody(Utils.toJson(tenant));
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::insertTenant exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent updateTenant(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        APIGatewayProxyResponseEvent response = null;
        LOGGER.info("TenantService::updateTenant");
        Map<String, String> params = event.getPathParameters();
        String tenantId = params.get("id");
        LOGGER.info("TenantService::updateTenant " + tenantId);
        Tenant tenant = Utils.fromJson(event.getBody(), Tenant.class);
        if (tenant == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        } else {
            if (tenant.getId() == null || !tenant.getId().toString().equals(tenantId)) {
                response = new APIGatewayProxyResponseEvent()
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
            } else {
                tenant = dal.updateTenant(tenant);
                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(HttpURLConnection.HTTP_OK)
                        .withHeaders(CORS)
                        .withBody(Utils.toJson(tenant));
            }
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::updateTenant exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent deleteTenant(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        APIGatewayProxyResponseEvent response = null;
        LOGGER.info("TenantService::deleteTenant");
        Map<String, String> params = event.getPathParameters();
        String tenantId = params.get("id");
        LOGGER.info("TenantService::deleteTenant " + tenantId);
        Tenant tenant = Utils.fromJson(event.getBody(), Tenant.class);
        if (tenant == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        } else {
            if (tenant.getId() == null || !tenant.getId().toString().equals(tenantId)) {
                response = new APIGatewayProxyResponseEvent()
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
            } else {
                dal.deleteTenant(tenantId);
                //TODO remove Cognito UserPool
                //TODO remove tenant's parameters from SSM
                //TODO delete onboarding CFN stack
                response = new APIGatewayProxyResponseEvent()
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_OK);
            }
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::deleteTenant exec " + totalTimeMillis);
        return response;
    }

    /**
     * <b>Not</b> thread-safe! - just throw-away sample code to avoid the delay in provisioning
     * an RDS cluster when registering a tenant during the workshop.
     * @param event
     * @param context
     * @return
     */
    public APIGatewayProxyResponseEvent nextAvailableDatabase(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        APIGatewayProxyResponseEvent response = null;
        LOGGER.info("TenantService::nextAvailableDatabase");
        Map<String, String> rds = dal.nextAvailableDatabase();
        response = new APIGatewayProxyResponseEvent()
                .withBody(Utils.toJson(rds))
                .withHeaders(CORS)
                .withStatusCode(HttpURLConnection.HTTP_OK);
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::nextAvailableDatabase exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent updateDatabase(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        APIGatewayProxyResponseEvent response = null;
        LOGGER.info("TenantService::updateDatabase");
        Map<String, String> params = event.getPathParameters();
        String tenantId = params.get("id");
        LOGGER.info("TenantService::updateDatabase " + tenantId);
        Tenant tenant = Utils.fromJson(event.getBody(), Tenant.class);
        if (tenant == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        } else {
            if (tenant.getId() == null || !tenant.getId().toString().equals(tenantId)
                    || tenant.getDatabase() == null || tenant.getDatabase().isEmpty()) {
                response = new APIGatewayProxyResponseEvent()
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
            } else {
                tenant = dal.updateDatabase(tenant);
                response = new APIGatewayProxyResponseEvent()
                        .withBody(Utils.toJson(tenant))
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_OK);
            }
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::updateDatabase exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent updateUserPool(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        APIGatewayProxyResponseEvent response = null;
        LOGGER.info("TenantService::updateUserPool");
        Map<String, String> params = event.getPathParameters();
        String tenantId = params.get("id");
        LOGGER.info("TenantService::updateUserPool " + tenantId);
        Tenant tenant = Utils.fromJson(event.getBody(), Tenant.class);
        if (tenant == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        } else {
            if (tenant.getId() == null || !tenant.getId().toString().equals(tenantId)
                    || tenant.getUserPool() == null || tenant.getUserPool().isEmpty()) {
                response = new APIGatewayProxyResponseEvent()
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
            } else {
                tenant = dal.updateUserPool(tenant);
                response = new APIGatewayProxyResponseEvent()
                        .withBody(Utils.toJson(tenant))
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_OK);
            }
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("TenantService::updateUserPool exec " + totalTimeMillis);
        return response;
    }

}